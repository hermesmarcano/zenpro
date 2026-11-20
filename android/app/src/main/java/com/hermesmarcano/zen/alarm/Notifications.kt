package com.hermesmarcano.zen.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hermesmarcano.zen.R
import com.hermesmarcano.zen.ui.RingingActivity

object Notifications {
    const val CHANNEL_RINGING = "zen_ringing"
    const val CHANNEL_FALLBACK = "zen_ringing_fallback"
    const val ID_RINGING = 1001
    const val ID_FALLBACK = 1002
    const val CHANNEL_DIAGNOSTIC = "zen_diagnostic"
    const val ID_DIAGNOSTIC = 1003

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        // El sonido lo reproduce AlarmService con volumen propio: el canal es mudo a propósito.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RINGING, "Alarma sonando", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Aviso mientras suena una alarma de ZEN."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DIAGNOSTIC, "Prueba de alarma", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Resultado de la prueba de 1 minuto de la alarma."
                enableVibration(true)
            },
        )
        // Plan B si el sistema no deja arrancar el servicio: que al menos suene con el tono de alarma del sistema.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FALLBACK, "Alarma (aviso de emergencia)", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun ringingPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RingingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun ringing(context: Context, channel: String = CHANNEL_RINGING): Notification {
        val pending = ringingPendingIntent(context)
        val isFallback = channel == CHANNEL_FALLBACK
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ZEN · alarma sonando")
            .setContentText("Ve a tu ancla para apagarla.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // El aviso de emergencia no tiene servicio que lo retire: debe poder descartarse.
            .setOngoing(!isFallback)
            .setAutoCancel(isFallback)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        // Sin servicio que repita el tono, que al menos el sonido del canal se repita hasta atenderlo.
        if (isFallback) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        return notification
    }

    fun postFallback(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java)
                .notify(ID_FALLBACK, ringing(context, CHANNEL_FALLBACK))
        } catch (_: SecurityException) {
            // Sin permiso de notificaciones ya no queda nada más que intentar.
        }
    }

    /** Aviso del resultado de la prueba de alarma (llegó a tiempo, tarde…). */
    fun postDiagnostic(context: Context, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, com.hermesmarcano.zen.ui.MainActivity::class.java)
                .putExtra(com.hermesmarcano.zen.ui.MainActivity.EXTRA_ROUTE, com.hermesmarcano.zen.ui.MainActivity.ROUTE_RELIABILITY)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DIAGNOSTIC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(ID_DIAGNOSTIC, notification)
        } catch (_: SecurityException) {
            // Sin permiso de notificaciones el resultado se ve igualmente dentro de la app.
        }
    }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(ID_RINGING)
        manager.cancel(ID_FALLBACK)
    }
}
