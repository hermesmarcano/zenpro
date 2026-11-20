package com.hermesmarcano.zen.layer.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.hermesmarcano.zen.R
import com.hermesmarcano.zen.layer.core.BlockEntry
import com.hermesmarcano.zen.layer.core.BlockReason
import com.hermesmarcano.zen.layer.core.Draft
import com.hermesmarcano.zen.layer.core.DraftBoard
import com.hermesmarcano.zen.layer.core.DraftController
import com.hermesmarcano.zen.layer.core.LimitDecision
import com.hermesmarcano.zen.layer.core.Notifier
import com.hermesmarcano.zen.ui.MainActivity
import java.text.DateFormat
import java.util.Date
import java.io.IOException

/**
 * Los avisos de la capa. Tono sobrio: dicen un hecho y ofrecen una acción, sin reproches. Las respuestas en borrador
 * ocultan su texto en la pantalla de bloqueo.
 */
class AndroidNotifier(private val context: Context) : Notifier {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Capa · estado", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aviso fijo mientras el micrófono está escuchando."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Capa · avisos", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Límites de tiempo, bloqueos, respuestas por aprobar y problemas que requieren atención."
            },
        )
    }

    override fun notice(title: String, text: String) = post(ID_NOTICE_BASE + (title.hashCode() and 0xFF), alert(title, text).build())

    override fun needsAttention(title: String, text: String) = post(ID_ATTENTION, alert(title, text).setOnlyAlertOnce(true).build())

    override fun draftReady(draft: Draft) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Respuesta para ${draft.contact}")
            .setContentText(draft.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(draft.text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(alert("ZEN", "Hay una respuesta esperando tu aprobación.").build())
            .setContentIntent(openLayer())
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(DraftBoard.TTL_MS)
            .addAction(0, "Enviar", action(LayerActionReceiver.ACTION_APPROVE_DRAFT, LayerActionReceiver.EXTRA_ID, draft.id))
            .addAction(0, "Descartar", action(LayerActionReceiver.ACTION_DISCARD_DRAFT, LayerActionReceiver.EXTRA_ID, draft.id))
        post(draftNotificationId(draft.id), builder.build())
    }

    override fun nudge(nudge: LimitDecision.Nudge) {
        val minutes = nudge.usedMs / 60_000
        val limit = nudge.rule.dailyLimitMs / 60_000
        val text = if (nudge.level >= 100) {
            "Llevas $minutes min en ${nudge.rule.label}. Tu límite de hoy era $limit min."
        } else {
            "Llevas $minutes min en ${nudge.rule.label}; tu límite de hoy es $limit min."
        }
        val builder = alert("Tiempo en ${nudge.rule.label}", text)
            .addAction(0, "Bloquear hasta mañana", action(LayerActionReceiver.ACTION_BLOCK_TODAY, LayerActionReceiver.EXTRA_PKG, nudge.rule.pkg))
        post(ID_NUDGE_BASE + (nudge.rule.pkg.hashCode() and 0xFFF), builder.build())
    }

    override fun blocked(entry: BlockEntry) {
        val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.untilMs))
        val text = "${label(entry.pkg)} está bloqueada hasta las $until."
        post(ID_BLOCK_BASE + (entry.pkg.hashCode() and 0xFFF), alert("App bloqueada", text).build())
    }

    fun cancelDraft(id: String) = manager.cancel(draftNotificationId(id))

    /** El aviso fijo del micrófono. Es el indicador visible de que se está escuchando: no se puede ocultar. */
    fun listening(): Notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("ZEN está escuchando")
        .setContentText("El micrófono está activo. Toca para ver o pausar.")
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openLayer())
        .addAction(0, "Pausar 1 h", action(LayerActionReceiver.ACTION_PAUSE, LayerActionReceiver.EXTRA_MINUTES, "60"))
        .addAction(0, "Detener", action(LayerActionReceiver.ACTION_STOP_AUDIO, LayerActionReceiver.EXTRA_ID, ""))
        .build()

    fun paused(untilMs: Long): Notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("ZEN en pausa")
        .setContentText("No se escucha hasta las ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(untilMs))}.")
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openLayer())
        .addAction(0, "Detener", action(LayerActionReceiver.ACTION_STOP_AUDIO, LayerActionReceiver.EXTRA_ID, ""))
        .build()

    private fun alert(title: String, text: String) = NotificationCompat.Builder(context, CHANNEL_ALERTS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .setContentIntent(openLayer())

    private fun post(id: Int, notification: Notification) {
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Sin permiso de notificaciones no hay nada más que hacer.
        }
    }

    private fun openLayer(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_LAYER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Cada acción lleva una URI propia para que Android no confunda las de distintos borradores o apps. */
    private fun action(action: String, extraKey: String, extraValue: String): PendingIntent {
        val intent = Intent(context, LayerActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("zenlayer://$action/${Uri.encode(extraValue)}"))
            .putExtra(extraKey, extraValue)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun label(pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        const val CHANNEL_STATUS = "zen_layer_status"
        const val CHANNEL_ALERTS = "zen_layer_alerts"
        const val ID_LISTENING = 2001
        const val ID_ATTENTION = 2002
        private const val ID_NOTICE_BASE = 2100
        private const val ID_NUDGE_BASE = 4000
        private const val ID_BLOCK_BASE = 8000
        private const val ID_DRAFT_BASE = 12000
        private const val REQUEST_OPEN = 77

        fun draftNotificationId(draftId: String): Int = ID_DRAFT_BASE + (draftId.hashCode() and 0xFFFF)
    }
}

/**
 * Recibe los botones de los avisos (Enviar / Descartar / Bloquear hoy / Pausar / Detener). No está exportado: solo
 * lo disparan las notificaciones de ZEN. Trabaja en segundo plano con `goAsync` para no bloquear el hilo principal.
 */
class LayerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runtime = context.layer
        // Fuera de un broadcast real (pruebas) goAsync() es null.
        val pending: PendingResult? = goAsync()
        runtime.runIo {
            try {
                handle(context, runtime, intent)
            } finally {
                pending?.finish()
            }
        }
    }

    private fun handle(context: Context, runtime: LayerRuntime, intent: Intent) {
        val now = System.currentTimeMillis()
        when (intent.action) {
            ACTION_APPROVE_DRAFT -> {
                val id = intent.getStringExtra(EXTRA_ID).orEmpty()
                val result = runtime.drafts.approve(id)
                runtime.notifier.cancelDraft(id)
                val message = when (result) {
                    DraftController.Result.SENT -> null
                    DraftController.Result.EXPIRED -> "La respuesta caducó antes de enviarse."
                    DraftController.Result.GONE -> "No se envió: la conversación ya no está disponible."
                    DraftController.Result.FAILED -> "No se pudo enviar la respuesta."
                    DraftController.Result.NOT_FOUND -> null // un segundo toque: ya se envió o se descartó
                }
                if (message != null) runtime.notifier.notice("Respuesta no enviada", message)
            }
            ACTION_DISCARD_DRAFT -> {
                val id = intent.getStringExtra(EXTRA_ID).orEmpty()
                runtime.drafts.discard(id)
                runtime.notifier.cancelDraft(id)
            }
            ACTION_BLOCK_TODAY -> {
                val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
                val blocked = try {
                    runtime.blockActions.blockUntilEndOfDay(pkg, BlockReason.MANUAL, now)
                } catch (_: IOException) {
                    runtime.notifier.notice("No se pudo bloquear", "No se pudo guardar el cambio. Inténtalo desde la app.")
                    return
                }
                if (!blocked) runtime.notifier.notice("No se puede bloquear", "Esa app está protegida.")
            }
            ACTION_PAUSE -> {
                val minutes = intent.getStringExtra(EXTRA_MINUTES)?.toIntOrNull()?.coerceIn(1, 24 * 60) ?: 60
                try {
                    runtime.state.update { it.copy(capture = it.capture.copy(pausedUntilMs = now + minutes * 60_000L)) }
                } catch (_: IOException) {
                    runtime.notifier.notice("No se pudo pausar", "No se pudo guardar el cambio. Inténtalo desde la app.")
                }
            }
            ACTION_STOP_AUDIO -> {
                // Primero se para el micrófono: que el ajuste no se pueda guardar no debe dejarlo grabando.
                AmbientAudioService.stop(context)
                try {
                    runtime.state.update { it.copy(capture = it.capture.copy(ambientAudio = false)) }
                } catch (_: IOException) {
                    runtime.notifier.notice("Micrófono parado", "No se pudo guardar el ajuste: apágalo también desde la app.")
                }
            }
        }
    }

    companion object {
        const val ACTION_APPROVE_DRAFT = "com.hermesmarcano.zen.layer.APPROVE_DRAFT"
        const val ACTION_DISCARD_DRAFT = "com.hermesmarcano.zen.layer.DISCARD_DRAFT"
        const val ACTION_BLOCK_TODAY = "com.hermesmarcano.zen.layer.BLOCK_TODAY"
        const val ACTION_PAUSE = "com.hermesmarcano.zen.layer.PAUSE"
        const val ACTION_STOP_AUDIO = "com.hermesmarcano.zen.layer.STOP_AUDIO"
        const val EXTRA_ID = "id"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_MINUTES = "minutes"
    }
}
