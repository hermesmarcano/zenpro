package com.hermesmarcano.zen.layer.capture

import android.app.Notification
import android.content.ComponentName
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hermesmarcano.zen.layer.core.Source

/** El texto útil de una notificación, ya extraído de sus `extras`. */
internal data class NotificationContent(val title: String, val body: String)

internal object NotificationText {
    private const val MAX_MESSAGES = 5

    /**
     * Título y cuerpo de una notificación. En las de mensajería (conversaciones) junta los últimos mensajes con su
     * remitente en vez de quedarse con "3 mensajes nuevos". `null` si no hay nada que contar.
     */
    fun from(notification: Notification): NotificationContent? {
        val extras = notification.extras ?: return null
        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString().orEmpty().trim()
        val messages = messagingLines(extras)
        val body = when {
            messages.isNotEmpty() -> messages.joinToString("\n")
            else -> (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        }
        if (title.isEmpty() && body.isEmpty()) return null
        return NotificationContent(title, body)
    }

    @Suppress("DEPRECATION")
    private fun messagingLines(extras: Bundle): List<String> {
        val raw = try {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return raw.filterIsInstance<Bundle>().takeLast(MAX_MESSAGES).mapNotNull { m ->
            val text = m.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val sender = m.getCharSequence("sender")?.toString()?.trim().orEmpty()
            if (sender.isEmpty()) text else "$sender: $text"
        }
    }

    /** Notificaciones que no son noticia: avisos de servicios en marcha, descargas, reproductores, resúmenes de grupo. */
    fun isNoise(notification: Notification, ongoing: Boolean): Boolean {
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        val category = notification.category
        if (category in NOISE_CATEGORIES) return true
        // Una notificación fija (en curso) solo importa si es un mensaje.
        return ongoing && category != Notification.CATEGORY_MESSAGE
    }

    private val NOISE_CATEGORIES = setOf(
        Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS, Notification.CATEGORY_NAVIGATION,
    )
}

/**
 * Lee las notificaciones y guarda las de apps permitidas (sin códigos de un solo uso, sin apps sensibles). Si una
 * notificación tiene botón de "Responder", guarda esa acción para poder contestar con una respuesta aprobada.
 */
class LayerNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        LayerLive.listenerConnected = true
    }

    override fun onListenerDisconnected() {
        LayerLive.listenerConnected = false
        // Xiaomi desconecta a menudo a los lectores de notificaciones: se pide volver a conectar.
        try {
            requestRebind(ComponentName(this, LayerNotificationListener::class.java))
        } catch (_: Exception) {
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        handle(sbn.packageName, sbn.key, notification, sbn.isOngoing)
    }

    /** La lógica, con datos simples (un `StatusBarNotification` no se puede fabricar en pruebas). */
    internal fun handle(pkg: String, sbnKey: String, notification: Notification, ongoing: Boolean) {
        val runtime = try {
            layer
        } catch (_: Exception) {
            return
        }
        if (pkg == packageName || NotificationText.isNoise(notification, ongoing)) return
        val content = NotificationText.from(notification) ?: return
        val key = "$pkg|$sbnKey"
        runtime.runIo {
            // Solo lo que la política permite (fuente encendida, sin pausa, app no sensible ni vetada) se guarda o se puede contestar.
            if (!runtime.pipeline.decision(Source.NOTIFICATIONS, pkg).allowed) return@runIo
            val canReply = runtime.replies.put(key, pkg, content.title.ifBlank { pkg }, notification)
            runtime.pipeline.onNotification(pkg, content.title, content.body, key.takeIf { canReply })
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val runtime = try {
            layer
        } catch (_: Exception) {
            return
        }
        runtime.replies.remove("${sbn.packageName}|${sbn.key}")
    }
}
