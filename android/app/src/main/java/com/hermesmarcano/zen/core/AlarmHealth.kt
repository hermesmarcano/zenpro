package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.data.AlarmPlanner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Lo que se sabe de cada alarma: programada, recibida (¿a tiempo o tarde?), descartada o perdida. Sirve para
 * enseñar al usuario qué hizo el teléfono con su alarma, en vez de adivinar. Puro: el reloj y la zona se inyectan.
 */
object AlarmHealth {
    /** Un aviso que llega con menos retraso que esto cuenta como "a tiempo" (los redondeos del sistema). */
    const val ON_TIME_MS = 90_000L

    /** Alarma de prueba: pasa por el mismo camino que las reales (AlarmManager → receptor) pero no suena de verdad. */
    const val SELF_TEST_ID = "zen-selftest"
    const val SELF_TEST_DELAY_MS = 60_000L

    /** Los problemas solo se muestran durante este tiempo. */
    const val PROBLEM_WINDOW_MS = 48L * 60 * 60 * 1000

    const val MAX_EVENTS = 60

    enum class Kind(val id: String) {
        SCHEDULED("scheduled"),
        FIRED("fired"),
        DISCARDED("discarded"),
        /** Pasó su hora y el sistema nunca entregó el aviso: se detecta al abrir la app. */
        MISSED("missed"),
        /** Reprogramada tras reinicio, cambio de hora o actualización. */
        RESTORED("restored"),
        TEST_SCHEDULED("test_scheduled"),
        TEST_FIRED("test_fired"),
        /** El usuario canceló la prueba antes de que llegara: no es un fallo. */
        TEST_CANCELLED("test_cancelled");

        companion object {
            fun fromId(id: String?): Kind? = entries.firstOrNull { it.id == id }
        }
    }

    data class Event(val at: Long, val kind: Kind, val alarmId: String? = null, val expectedAt: Long? = null, val note: String = "")

    /** Cuánto tardó en llegar el aviso respecto a su hora (negativo = antes), solo para los que llegaron. */
    fun latenessMs(e: Event): Long? =
        if ((e.kind == Kind.FIRED || e.kind == Kind.TEST_FIRED) && e.expectedAt != null) e.at - e.expectedAt else null

    fun isLate(e: Event): Boolean = (latenessMs(e) ?: 0L) > ON_TIME_MS

    /** "a tiempo", "45 s tarde", "1 h 24 min tarde". */
    fun latenessText(ms: Long): String {
        if (ms <= ON_TIME_MS) return "a tiempo"
        val totalSeconds = ms / 1000
        return when {
            totalSeconds < 120 -> "$totalSeconds s tarde"
            totalSeconds < 3600 -> "${totalSeconds / 60} min tarde"
            else -> "${totalSeconds / 3600} h ${(totalSeconds % 3600) / 60} min tarde"
        }
    }

    private fun clock(millis: Long, zone: ZoneId, seconds: Boolean = false): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern(if (seconds) "HH:mm:ss" else "HH:mm", Locale.ROOT))

    private val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale("es"))

    /** Una línea del registro: "19 nov 06:54:12 · Recibida (era para las 05:30) · 1 h 24 min tarde". */
    fun describe(e: Event, zone: ZoneId): String {
        val stamp = Instant.ofEpochMilli(e.at).atZone(zone).format(dayFormat) + " " + clock(e.at, zone, seconds = true)
        val expected = e.expectedAt?.let { clock(it, zone) }
        val what = when (e.kind) {
            Kind.SCHEDULED -> "Programada" + (expected?.let { " para las $it" } ?: "")
            Kind.FIRED -> "Recibida" + (expected?.let { " (era para las $it)" } ?: "") + (latenessMs(e)?.let { " · " + latenessText(it) } ?: "")
            Kind.DISCARDED -> "Aviso descartado" + (expected?.let { " (era para las $it)" } ?: "")
            Kind.MISSED -> "No sonó" + (expected?.let { " la de las $it" } ?: "") + ": el sistema no entregó el aviso"
            Kind.RESTORED -> "Reprogramada"
            Kind.TEST_SCHEDULED -> "Prueba programada" + (expected?.let { " para las ${clock(e.expectedAt!!, zone, seconds = true)}" } ?: "")
            Kind.TEST_FIRED -> "Prueba recibida" + (latenessMs(e)?.let { " · " + latenessText(it) } ?: "")
            Kind.TEST_CANCELLED -> "Prueba cancelada"
        }
        return "$stamp · $what" + if (e.note.isNotBlank()) " · ${e.note}" else ""
    }

    /** Alarmas que debieron sonar hace más de la tolerancia y siguen sin procesarse. */
    fun isOverdue(alarm: AlarmEntity, now: Long): Boolean {
        val next = alarm.nextFireAt ?: return false
        return alarm.enabled && now - next > AlarmPlanner.LATE_GRACE_MS
    }

    /** Eventos que el usuario debe ver: alarmas perdidas o llegadas tarde, recientes y posteriores a [ackAt]. */
    fun problems(events: List<Event>, now: Long, ackAt: Long): List<Event> =
        events.filter { e ->
            e.alarmId != SELF_TEST_ID && e.at > ackAt && now - e.at <= PROBLEM_WINDOW_MS &&
                (e.kind == Kind.MISSED || (e.kind == Kind.FIRED && isLate(e)))
        }

    /** Última prueba de 1 minuto con resultado, si la hubo. */
    fun lastTestResult(events: List<Event>): Event? = events.lastOrNull { it.kind == Kind.TEST_FIRED }

    /** ¿Hay una prueba programada que aún no llegó? Devuelve su hora. */
    fun pendingTest(events: List<Event>, now: Long): Long? {
        val scheduled = events.lastOrNull { it.kind == Kind.TEST_SCHEDULED } ?: return null
        val expected = scheduled.expectedAt ?: return null
        if (testEnded(events, scheduled)) return null
        // Pasada su hora con margen sin llegar, deja de estar pendiente: ha fallado (ver testFailed).
        return expected.takeIf { now - it <= ON_TIME_MS }
    }

    /** La prueba falló: se programó, pasó su hora con margen y nunca llegó. */
    fun testFailed(events: List<Event>, now: Long): Boolean {
        val scheduled = events.lastOrNull { it.kind == Kind.TEST_SCHEDULED } ?: return false
        val expected = scheduled.expectedAt ?: return false
        return !testEnded(events, scheduled) && now - expected > ON_TIME_MS
    }

    /** La prueba ya terminó: llegó o la canceló el usuario después de programarla. */
    private fun testEnded(events: List<Event>, scheduled: Event): Boolean =
        events.any { (it.kind == Kind.TEST_FIRED || it.kind == Kind.TEST_CANCELLED) && it.at >= scheduled.at }

    /** Lo que se le cuenta al usuario al llegar el aviso de la prueba. */
    data class Verdict(val passed: Boolean, val title: String, val text: String)

    /** [latenessMs] `null` = no se sabe para qué hora estaba programada. */
    fun selfTestVerdict(latenessMs: Long?): Verdict =
        if (latenessMs == null || latenessMs > ON_TIME_MS) {
            val how = latenessMs?.let { latenessText(it) } ?: "sin hora de referencia"
            Verdict(false, "La prueba llegó tarde", "El aviso llegó $how. El sistema está retrasando las alarmas de ZEN: abre «Fiabilidad de la alarma» para ver cómo evitarlo.")
        } else {
            Verdict(true, "Prueba superada", "El aviso llegó a su hora: el teléfono entrega las alarmas de ZEN. No comprueba el sonido ni la pantalla de apagado.")
        }
}
