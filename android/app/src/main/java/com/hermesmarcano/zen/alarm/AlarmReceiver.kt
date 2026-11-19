package com.hermesmarcano.zen.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.hermesmarcano.zen.core.AlarmHealth
import com.hermesmarcano.zen.data.AlarmPlanner
import com.hermesmarcano.zen.zenApp
import java.io.IOException

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
        val repository = context.zenApp.repository
        val now = System.currentTimeMillis()
        if (alarmId == AlarmHealth.SELF_TEST_ID) {
            AlarmActions.completeSelfTest(context, now)
            return
        }
        val log = context.zenApp.alarmLog

        // Un PendingIntent de una versión anterior de la alarma (editada o borrada) no debe sonar.
        val pause = AlarmActions.pauseUntilDay(repository.state.value)
        if (!AlarmPlanner.shouldRing(repository.state.value.alarm(alarmId), now, pause)) {
            Log.i(TAG, "Disparo descartado para $alarmId")
            val alarm = repository.state.value.alarm(alarmId)
            log.record(AlarmHealth.Event(now, AlarmHealth.Kind.DISCARDED, alarmId, alarm?.nextFireAt, if (alarm == null) "ya no existe" else if (!alarm.enabled) "estaba desactivada" else "aún no tocaba"))
            // Llegó en plena pausa por viaje (se programó antes de pausar): recolocar las alarmas tras la pausa.
            if (pause != null) AlarmActions.restoreAll(context)
            return
        }

        // La hora para la que estaba programada, antes de que el estado se actualice más abajo.
        val expectedAt = repository.state.value.alarm(alarmId)?.nextFireAt

        // Primero sonar (lo urgente), después anotar y programar la siguiente.
        try {
            ContextCompat.startForegroundService(context, AlarmService.ringIntent(context, alarmId))
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el servicio de alarma", e)
            Notifications.postFallback(context)
        }

        // Se anota cuándo llegó el aviso respecto a su hora: si el sistema lo retrasó, ahí queda constancia.
        log.record(AlarmHealth.Event(now, AlarmHealth.Kind.FIRED, alarmId, expectedAt))

        try {
            val updated = repository.update { state ->
                state.copy(alarms = state.alarms.map { if (it.id == alarmId) AlarmPlanner.afterFiring(it, now, pauseUntilDay = pause) else it })
            }
            updated.alarm(alarmId)?.let { AlarmScheduler.apply(context, it) }
        } catch (e: IOException) {
            Log.e(TAG, "No se pudo guardar el estado tras sonar $alarmId", e)
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
