package com.hermesmarcano.zen.layer.capture

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.hermesmarcano.zen.layer.core.DraftBoard
import com.hermesmarcano.zen.layer.core.OutboxItem
import java.io.IOException

/**
 * Tareas periódicas con `JobScheduler` (sin dependencias nuevas): un mantenimiento cada ~15 min y un envío poco
 * después de que haya algo nuevo y red. Los trabajos sobreviven a un reinicio (`setPersisted`), pero no a una
 * actualización de la app: por eso `BootReceiver` vuelve a programarlos.
 */
object LayerScheduler {
    const val JOB_MAINTENANCE = 7101
    const val JOB_FLUSH = 7102
    private const val MAINTENANCE_EVERY_MS = 15 * 60_000L
    private const val FLUSH_DELAY_MS = 30_000L
    private const val FLUSH_DEADLINE_MS = 10 * 60_000L

    /** Deja programado el mantenimiento si la capa está en uso, y lo cancela si no. Idempotente. */
    fun sync(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val s = context.layer.state.current
        val inUse = s.capture.screenText || s.capture.notifications || s.capture.ambientAudio ||
            s.limits.isNotEmpty() || s.blocks.isNotEmpty() || s.brainConfigured
        if (!inUse) {
            scheduler.cancel(JOB_MAINTENANCE)
            scheduler.cancel(JOB_FLUSH)
            return
        }
        if (scheduler.getPendingJob(JOB_MAINTENANCE) != null) return
        val job = JobInfo.Builder(JOB_MAINTENANCE, ComponentName(context, LayerJobService::class.java))
            .setPeriodic(MAINTENANCE_EVERY_MS)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    /** Pide un envío pronto. Si ya hay uno esperando no se reprograma (si no, un flujo constante de eventos lo aplazaría siempre). */
    fun requestFlush(context: Context) {
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_FLUSH) != null) return
            val job = JobInfo.Builder(JOB_FLUSH, ComponentName(context, LayerJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(FLUSH_DELAY_MS)
                .setOverrideDeadline(FLUSH_DEADLINE_MS)
                .build()
            scheduler.schedule(job)
        } catch (_: Exception) {
            // Si no se puede programar, el mantenimiento periódico enviará igualmente.
        }
    }
}

class LayerJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val runtime = layer
        runtime.runJob {
            try {
                if (params.jobId == LayerScheduler.JOB_MAINTENANCE) LayerMaintenance.run(applicationContext) else runtime.sync.run()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    /** El sistema corta el trabajo (batería, red): el siguiente periodo lo retoma. */
    override fun onStopJob(params: JobParameters): Boolean = false
}

/** Lo que se hace cada ~15 minutos aunque nadie abra la app. Cada paso es independiente: uno que falle no impide los demás. */
object LayerMaintenance {
    fun run(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val runtime = context.layer
        step { runtime.ensureDeviceId() }
        step { tidy(runtime, nowMs) }
        step { AccessibilityHealer.run(context, nowMs) }
        step { rebindListener(context, runtime) }
        step { watchAudio(runtime) }
        step { runtime.sync.run() }
    }

    private fun tidy(runtime: LayerRuntime, nowMs: Long) {
        runtime.timeline.prune(nowMs)
        runtime.outbox.prune(nowMs).filter { it.kind == OutboxItem.Kind.AUDIO }.forEach { runtime.audioFiles.delete(it.payload) }
        // Los archivos de audio que ya no están en la cola (un cierre a medias) se borran.
        val queued = runtime.outbox.due(OutboxItem.Kind.AUDIO, Long.MAX_VALUE, Int.MAX_VALUE).map { it.payload }.toSet()
        runtime.audioFiles.deleteOrphans(queued, nowMs)
        try {
            runtime.state.update { it.copy(drafts = DraftBoard.expire(it.drafts, nowMs)) }
        } catch (_: IOException) {
        }
    }

    /** Xiaomi desconecta a menudo al lector de notificaciones: si está activado en Ajustes pero no conectado, se pide reconectar. */
    private fun rebindListener(context: Context, runtime: LayerRuntime) {
        if (!runtime.state.current.capture.notifications) return
        if (LayerStatus.isListenerEnabled(context) && !LayerLive.listenerConnected) {
            android.service.notification.NotificationListenerService.requestRebind(ComponentName(context, LayerNotificationListener::class.java))
        }
    }

    /**
     * Si el micrófono debería estar escuchando y no lo está (Xiaomi cerró el servicio), se avisa: desde segundo plano
     * Android no deja reiniciarlo, hay que tocar el aviso para abrir ZEN.
     */
    private fun watchAudio(runtime: LayerRuntime) {
        val s = runtime.state.current
        if (s.capture.ambientAudio && s.brainConfigured && !LayerLive.audioRunning) {
            runtime.notifier.needsAttention("El micrófono se detuvo", "Abre ZEN para volver a escuchar.")
        }
    }

    private inline fun step(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }
}
