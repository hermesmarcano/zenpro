package com.hermesmarcano.zen.layer.capture

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.hermesmarcano.zen.layer.core.DraftBoard
import com.hermesmarcano.zen.layer.core.ReplySendResult
import com.hermesmarcano.zen.layer.core.ReplySender
import com.hermesmarcano.zen.layer.core.ReplyTarget

/**
 * Las acciones de "Responder" de las notificaciones de mensajería (WhatsApp, Telegram, SMS...), guardadas por
 * conversación. Es la única vía por la que la capa escribe en otras apps sin accesibilidad: dispara la misma
 * acción que el botón "Responder" de la notificación, con el texto del borrador aprobado.
 *
 * Una conversación solo es contestable mientras su notificación siga ahí: al descartarse, [remove] la olvida.
 */
class ReplyActionCache(
    private val context: Context,
    private val ttlMs: Long = DraftBoard.TTL_MS,
    private val maxEntries: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReplySender {
    private class Entry(val pkg: String, val contact: String, val action: NotificationCompat.Action, val input: RemoteInput, val expiresAtMs: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
    }

    /** Guarda la acción de respuesta de [notification] para la conversación [key]. Devuelve `false` si no tiene una. */
    @Synchronized
    fun put(key: String, pkg: String, contact: String, notification: Notification): Boolean {
        val found = findReply(notification) ?: return false
        entries[key] = Entry(pkg, contact, found.first, found.second, clock() + ttlMs)
        return true
    }

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    override fun target(conversationKey: String): ReplyTarget? = live(conversationKey)?.let { ReplyTarget(it.pkg, it.contact) }

    override fun send(conversationKey: String, text: String): ReplySendResult {
        val entry = synchronized(this) { live(conversationKey) } ?: return ReplySendResult.GONE
        val pending = entry.action.actionIntent ?: return ReplySendResult.GONE
        return try {
            val intent = Intent()
            val results = Bundle().apply { putCharSequence(entry.input.resultKey, text) }
            RemoteInput.addResultsToIntent(arrayOf(entry.input), intent, results)
            pending.send(context, 0, intent)
            ReplySendResult.SENT
        } catch (_: PendingIntent.CanceledException) {
            // La app dueña de la notificación ya la retiró: no hay a quién contestar.
            remove(conversationKey)
            ReplySendResult.GONE
        } catch (_: Exception) {
            ReplySendResult.FAILED
        }
    }

    /** Devuelve la entrada si existe y no caducó (y la quita si caducó). Llamar con el candado tomado. */
    private fun live(key: String): Entry? {
        val entry = entries[key] ?: return null
        if (entry.expiresAtMs <= clock()) {
            entries.remove(key)
            return null
        }
        return entry
    }

    companion object {
        /**
         * La acción "Responder" de una notificación: la que tiene una entrada de texto libre. Se busca en las acciones
         * normales y en las de reloj (WearableExtender), donde algunas apps la ponen. Prefiere la marcada como respuesta.
         */
        fun findReply(notification: Notification): Pair<NotificationCompat.Action, RemoteInput>? {
            val candidates = ArrayList<NotificationCompat.Action>()
            for (i in 0 until NotificationCompat.getActionCount(notification)) NotificationCompat.getAction(notification, i)?.let(candidates::add)
            candidates += NotificationCompat.WearableExtender(notification).actions
            val usable = candidates.mapNotNull { action ->
                val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return@mapNotNull null
                if (action.actionIntent == null) null else action to input
            }
            return usable.firstOrNull { it.first.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY } ?: usable.firstOrNull()
        }
    }
}
