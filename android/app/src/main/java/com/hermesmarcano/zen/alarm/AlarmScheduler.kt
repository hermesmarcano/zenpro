package com.hermesmarcano.zen.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.hermesmarcano.zen.core.AlarmHealth
import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.zenApp
import com.hermesmarcano.zen.ui.MainActivity

/**
 * Programa las alarmas con `setAlarmClock`: es la vía que el sistema trata como "alarma del
 * usuario" (sobrevive a Doze, muestra el icono de alarma en la barra de estado y no necesita el
 * permiso de alarmas exactas).
 */
object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
    const val ACTION_FIRE = "com.hermesmarcano.zen.action.FIRE"
    const val EXTRA_ALARM_ID = "alarmId"

    /** Programa o cancela según el estado de la alarma. */
    fun apply(context: Context, alarm: AlarmEntity) {
        val at = alarm.nextFireAt
        if (alarm.enabled && at != null) schedule(context, alarm.id, at) else cancel(context, alarm.id)
    }

    // `setAlarmClock` está exenta del permiso de alarmas exactas (SCHEDULE_EXACT_ALARM): lo exige solo setExact*.
    @SuppressLint("MissingPermission")
    fun schedule(context: Context, alarmId: String, atMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val showIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showIntent), firePendingIntent(context, alarmId))
            val kind = if (alarmId == AlarmHealth.SELF_TEST_ID) AlarmHealth.Kind.TEST_SCHEDULED else AlarmHealth.Kind.SCHEDULED
            context.zenApp.alarmLog.record(AlarmHealth.Event(System.currentTimeMillis(), kind, alarmId, atMillis))
        } catch (e: SecurityException) {
            Log.e(TAG, "El sistema rechazó programar la alarma $alarmId", e)
            context.zenApp.alarmLog.record(AlarmHealth.Event(System.currentTimeMillis(), AlarmHealth.Kind.DISCARDED, alarmId, atMillis, "el sistema rechazó programarla"))
        }
    }

    fun cancel(context: Context, alarmId: String) {
        context.getSystemService(AlarmManager::class.java).cancel(firePendingIntent(context, alarmId))
    }

    private fun firePendingIntent(context: Context, alarmId: String): PendingIntent {
        // El `data` distingue las alarmas entre sí aunque dos ids colisionaran en el requestCode.
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("zen://alarm/$alarmId"))
            .putExtra(EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
