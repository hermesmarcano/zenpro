package com.hermesmarcano.zen.alarm

import android.content.Context
import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.core.AlarmHealth
import com.hermesmarcano.zen.core.GameMessages
import com.hermesmarcano.zen.core.TripPolicy
import com.hermesmarcano.zen.data.AlarmPlanner
import com.hermesmarcano.zen.data.GameEngine
import com.hermesmarcano.zen.data.GameEvents
import com.hermesmarcano.zen.data.Trip
import com.hermesmarcano.zen.data.ZenState
import com.hermesmarcano.zen.zenApp
import java.io.IOException
import java.time.LocalDate

/** Operaciones sobre alarmas que mantienen coherentes el estado guardado y el AlarmManager. */
object AlarmActions {
    /** Guarda (crea o reemplaza) la alarma, activándola o no, y la programa. */
    @Throws(IOException::class)
    fun save(context: Context, alarm: AlarmEntity, enabled: Boolean): AlarmEntity {
        val now = System.currentTimeMillis()
        val prepared = if (enabled) AlarmPlanner.enable(alarm, now, pauseUntilDay = pauseUntilDay(context.zenApp.repository.state.value)) else AlarmPlanner.disable(alarm)
        context.zenApp.repository.update { state ->
            val exists = state.alarms.any { it.id == prepared.id }
            state.copy(alarms = if (exists) state.alarms.map { if (it.id == prepared.id) prepared else it } else state.alarms + prepared)
        }
        AlarmScheduler.apply(context, prepared)
        return prepared
    }

    /** Devuelve la alarma resultante (`null` si no existe): `enabled == false` tras pedir activarla significa que el modo viaje la omitió. */
    @Throws(IOException::class)
    fun setEnabled(context: Context, alarmId: String, enabled: Boolean): AlarmEntity? {
        val alarm = context.zenApp.repository.state.value.alarm(alarmId) ?: return null
        return save(context, alarm, enabled)
    }

    @Throws(IOException::class)
    fun delete(context: Context, alarmId: String) {
        context.zenApp.repository.update { it.copy(alarms = it.alarms.filterNot { a -> a.id == alarmId }) }
        AlarmScheduler.cancel(context, alarmId)
    }

    /** Último día de la pausa por viaje si hay una activa hoy. */
    fun pauseUntilDay(state: ZenState, today: LocalDate = LocalDate.now()): Long? =
        TripPolicy.pauseUntilDay(state.trip, today.toEpochDay())

    /** Empieza (o cambia) un viaje y reprograma las alarmas. Devuelve lo que el juego quiera celebrar (logro "Viajero"). */
    @Throws(IOException::class)
    fun startTrip(context: Context, trip: Trip): GameEvents {
        val repository = context.zenApp.repository
        var events = GameEvents()
        repository.update { state ->
            val result = GameEngine.startTrip(state, trip, LocalDate.now())
            events = result.events
            result.state
        }
        restoreAll(context)
        return events
    }

    /** Termina el viaje ya y reprograma (las repetitivas vuelven a su hora de siempre). */
    @Throws(IOException::class)
    fun endTrip(context: Context) {
        context.zenApp.repository.update { GameEngine.cancelTrip(it, LocalDate.now()) }
        restoreAll(context)
    }

    /**
     * Reprograma todo tras reinicio, cambio de hora/zona, actualización de la app o cambio del modo viaje.
     * Con [reason] se anota en el registro de alarmas por qué se reprogramó.
     */
    fun restoreAll(context: Context, reason: String? = null) {
        val repository = context.zenApp.repository
        val now = System.currentTimeMillis()
        if (reason != null) context.zenApp.alarmLog.record(AlarmHealth.Event(now, AlarmHealth.Kind.RESTORED, note = reason))
        val restored: ZenState = try {
            repository.update { state ->
                val pause = pauseUntilDay(state)
                state.copy(alarms = state.alarms.map { AlarmPlanner.restore(it, now, pauseUntilDay = pause) })
            }
        } catch (_: IOException) {
            repository.state.value
        }
        restored.alarms.forEach { AlarmScheduler.apply(context, it) }
    }

    /**
     * Alarmas activas que pasaron su hora hace más de [com.hermesmarcano.zen.data.AlarmPlanner.LATE_GRACE_MS] sin que el
     * sistema entregara el aviso: se anotan como perdidas y se reprograman (la repetitiva a su próxima vez; la de una sola
     * vez se desactiva). Sin esto la tarjeta se quedaba para siempre en "suena en menos de 1 min". No hace nada mientras suena
     * una alarma. Devuelve las perdidas.
     */
    fun healOverdue(context: Context, now: Long = System.currentTimeMillis()): List<AlarmEntity> {
        if (AlarmRuntime.ring.value != null) return emptyList()
        val overdue = context.zenApp.repository.state.value.alarms.filter { AlarmHealth.isOverdue(it, now) }
        if (overdue.isEmpty()) return emptyList()
        val log = context.zenApp.alarmLog
        overdue.forEach { log.record(AlarmHealth.Event(now, AlarmHealth.Kind.MISSED, it.id, it.nextFireAt)) }
        restoreAll(context)
        return overdue
    }

    /** Programa la prueba de alarma por el camino real (AlarmManager → receptor). Devuelve la hora a la que debe llegar. */
    fun startSelfTest(context: Context, now: Long = System.currentTimeMillis()): Long {
        val at = now + AlarmHealth.SELF_TEST_DELAY_MS
        AlarmScheduler.schedule(context, AlarmHealth.SELF_TEST_ID, at)
        return at
    }

    fun cancelSelfTest(context: Context) {
        AlarmScheduler.cancel(context, AlarmHealth.SELF_TEST_ID)
        context.zenApp.alarmLog.record(AlarmHealth.Event(System.currentTimeMillis(), AlarmHealth.Kind.TEST_CANCELLED, AlarmHealth.SELF_TEST_ID))
    }

    /** Llegó el aviso de la prueba: se anota con su retraso, se avisa al usuario y, si fue a tiempo, se da el logro. */
    fun completeSelfTest(context: Context, now: Long) {
        val app = context.zenApp
        val expected = app.alarmLog.events.value.lastOrNull { it.kind == AlarmHealth.Kind.TEST_SCHEDULED }?.expectedAt
        val event = AlarmHealth.Event(now, AlarmHealth.Kind.TEST_FIRED, AlarmHealth.SELF_TEST_ID, expected)
        app.alarmLog.record(event)
        val verdict = AlarmHealth.selfTestVerdict(AlarmHealth.latenessMs(event))
        if (!verdict.passed) {
            Notifications.postDiagnostic(context, verdict.title, verdict.text)
            return
        }
        var unlocked: List<String> = emptyList()
        try {
            app.repository.update { s -> com.hermesmarcano.zen.data.GameEngine.recordAlarmTest(s, LocalDate.now()).also { unlocked = GameMessages.describe(it.events) }.state }
        } catch (_: IOException) {
            unlocked = emptyList()
        }
        Notifications.postDiagnostic(context, verdict.title, verdict.text + if (unlocked.isNotEmpty()) " · " + unlocked.joinToString(" · ") else "")
    }
}
