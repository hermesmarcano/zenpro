package com.hermesmarcano.zen.alarm

import android.content.Context
import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.data.AlarmPlanner
import com.hermesmarcano.zen.data.ZenState
import com.hermesmarcano.zen.zenApp
import java.io.IOException

/** Operaciones sobre alarmas que mantienen coherentes el estado guardado y el AlarmManager. */
object AlarmActions {
    /** Guarda (crea o reemplaza) la alarma, activándola o no, y la programa. */
    @Throws(IOException::class)
    fun save(context: Context, alarm: AlarmEntity, enabled: Boolean) {
        val now = System.currentTimeMillis()
        val prepared = if (enabled) AlarmPlanner.enable(alarm, now) else AlarmPlanner.disable(alarm)
        context.zenApp.repository.update { state ->
            val exists = state.alarms.any { it.id == prepared.id }
            state.copy(alarms = if (exists) state.alarms.map { if (it.id == prepared.id) prepared else it } else state.alarms + prepared)
        }
        AlarmScheduler.apply(context, prepared)
    }

    @Throws(IOException::class)
    fun setEnabled(context: Context, alarmId: String, enabled: Boolean) {
        val alarm = context.zenApp.repository.state.value.alarm(alarmId) ?: return
        save(context, alarm, enabled)
    }

    @Throws(IOException::class)
    fun delete(context: Context, alarmId: String) {
        context.zenApp.repository.update { it.copy(alarms = it.alarms.filterNot { a -> a.id == alarmId }) }
        AlarmScheduler.cancel(context, alarmId)
    }

    /** Reprograma todo tras reinicio, cambio de hora/zona o actualización de la app. */
    fun restoreAll(context: Context) {
        val repository = context.zenApp.repository
        val now = System.currentTimeMillis()
        val restored: ZenState = try {
            repository.update { state -> state.copy(alarms = state.alarms.map { AlarmPlanner.restore(it, now) }) }
        } catch (_: IOException) {
            repository.state.value
        }
        restored.alarms.forEach { AlarmScheduler.apply(context, it) }
    }
}
