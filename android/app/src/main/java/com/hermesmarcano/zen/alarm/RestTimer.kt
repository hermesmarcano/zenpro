package com.hermesmarcano.zen.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hermesmarcano.zen.R
import com.hermesmarcano.zen.data.RestState
import com.hermesmarcano.zen.ui.MainActivity

/**
 * Cronómetro de descanso entre series. El estado (la hora de fin) vive en la sesión guardada; esto solo
 * lo refleja fuera de la app: una alarma que avisa al terminar y una notificación con cuenta atrás
 * que el propio sistema actualiza (no hace falta servicio en primer plano).
 */
object RestTimer {
    private const val TAG = "RestTimer"
    const val ACTION_DONE = "com.hermesmarcano.zen.action.REST_DONE"
    const val EXTRA_ENDS_AT = "endsAt"
    const val CHANNEL_RUNNING = "zen_rest_running"
    const val CHANNEL_DONE = "zen_rest_done"
    const val ID_NOTIFICATION = 2001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "Descanso en marcha", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Cuenta atrás del descanso entre series."
                setSound(null, null)
                enableVibration(false)
            },
        )
        // El sonido lo reproduce RestReceiver con volumen propio y bajo; el canal solo vibra.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Descanso terminado", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Aviso suave cuando termina el descanso."
                setSound(null, null)
                enableVibration(true)
            },
        )
    }

    /** Refleja [rest] fuera de la app: programa el aviso y la notificación, o los quita si no hay descanso en curso. */
    fun sync(context: Context, rest: RestState?, now: Long = System.currentTimeMillis()) {
        if (rest == null || rest.isOver(now)) {
            cancelAlarm(context)
            if (rest == null) cancelNotification(context)
            return
        }
        schedule(context, rest.endsAt)
        showRunning(context, rest)
    }

    fun cancel(context: Context) {
        cancelAlarm(context)
        cancelNotification(context)
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun schedule(context: Context, endsAt: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context, endsAt)
        try {
            // Con permiso de alarmas exactas (USE_EXACT_ALARM, concedido a las apps de alarma) avisa en el segundo exacto.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, pending)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "El sistema rechazó programar el aviso de descanso", e)
        }
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, 0L))
    }

    private fun alarmIntent(context: Context, endsAt: Long): PendingIntent {
        // El mismo PendingIntent para cualquier hora de fin: reprogramar sustituye al anterior (+15 s, nueva serie…).
        val intent = Intent(context, RestReceiver::class.java)
            .setAction(ACTION_DONE)
            .setData(Uri.parse("zen://rest"))
            .putExtra(EXTRA_ENDS_AT, endsAt)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_WORKOUT).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun showRunning(context: Context, rest: RestState) {
        val notification = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Descansando")
            .setContentText("Vuelve a la barra cuando llegue a cero.")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(rest.endsAt)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(rest.remainingMillis(System.currentTimeMillis()) + 1_000L)
            .setContentIntent(openApp(context))
            .build()
        notify(context, notification)
    }

    fun showDone(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Descanso terminado")
            .setContentText("Siguiente serie.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000L)
            .setVibrate(longArrayOf(0, 180, 120, 180))
            .setContentIntent(openApp(context))
            .build()
        notify(context, notification)
    }

    private fun notify(context: Context, notification: android.app.Notification) {
        try {
            context.getSystemService(NotificationManager::class.java).notify(ID_NOTIFICATION, notification)
        } catch (_: SecurityException) {
            // Sin permiso de notificaciones queda el cronómetro dentro de la app y el sonido.
        }
    }

    private fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_NOTIFICATION)
    }
}
