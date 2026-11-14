package com.hermesmarcano.zen.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.hermesmarcano.zen.data.AlarmPlanner
import com.hermesmarcano.zen.zenApp
import java.io.IOException

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
        val repository = context.zenApp.repository
        val now = System.currentTimeMillis()

        // Un PendingIntent de una versión anterior de la alarma (editada o borrada) no debe sonar.
        if (!AlarmPlanner.shouldRing(repository.state.value.alarm(alarmId), now)) {
            Log.i(TAG, "Disparo descartado para $alarmId")
            return
        }

        // Primero sonar (lo urgente), después programar la siguiente.
        try {
            ContextCompat.startForegroundService(context, AlarmService.ringIntent(context, alarmId))
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el servicio de alarma", e)
            Notifications.postFallback(context)
        }

        try {
            val updated = repository.update { state ->
                state.copy(alarms = state.alarms.map { if (it.id == alarmId) AlarmPlanner.afterFiring(it, now) else it })
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
