package com.hermesmarcano.zen.layer.capture

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hermesmarcano.zen.layer.core.PcmSegment
import com.hermesmarcano.zen.layer.core.Source
import com.hermesmarcano.zen.layer.core.SpeechSegmenter

/**
 * Escucha continua del micrófono, visible y detenible. Corre como servicio en primer plano de tipo micrófono: el
 * aviso fijo "ZEN está escuchando" es el indicador (Android 11 no trae el punto verde de privacidad) y no se puede
 * quitar mientras escucha.
 *
 * Solo guarda tramos de voz (ver [SpeechSegmenter]) y los manda al cerebro, que los transcribe: sin cerebro no hay
 * quien los transcriba, así que ni siquiera arranca. Con la capa en pausa suelta el micrófono (no escucha) pero
 * mantiene el servicio para retomar solo cuando acabe la pausa, sin que haga falta volver a abrir la app.
 *
 * Se arranca SOLO con [start], que comprueba antes las precondiciones: lanzar un servicio en primer plano y no
 * llamar a `startForeground` a tiempo hace que Android 8+ cierre la app.
 */
class AmbientAudioService : Service() {
    /** Una apertura del micrófono con su propio segmentador: los índices de muestra y la hora de inicio van juntos. */
    private class Capture(val record: AudioRecord) {
        val segmenter = SpeechSegmenter(SAMPLE_RATE)
        val startedAtMs: Long = System.currentTimeMillis()
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = layer
        try {
            ServiceCompat.startForeground(
                this, AndroidNotifier.ID_LISTENING, runtime.notifier.listening(),
                // El tipo "micrófono" existe desde Android 11 (API 30); antes manda el declarado en el manifiesto.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (_: Exception) {
            // Android 12+ no deja arrancar un servicio con micrófono desde segundo plano.
            runtime.notifier.needsAttention("El micrófono no se pudo iniciar", "Abre ZEN y vuelve a activarlo desde la pantalla Capa.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (preconditions(this, runtime) != StartCheck.OK) {
            // Cambió algo entre el arranque y ahora (permiso retirado, fuente apagada): se cierra sin escuchar.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker == null) {
            running = true
            LayerLive.audioRunning = true
            acquireWakeLock()
            worker = Thread({ captureLoop(runtime) }, "zen-layer-audio").also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        LayerLive.audioRunning = false
        worker?.interrupt()
        try {
            worker?.join(1_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        worker = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun captureLoop(runtime: LayerRuntime) {
        val buffer = ShortArray(SAMPLE_RATE / 10) // 100 ms
        var capture: Capture? = null
        var lastCheckMs = 0L
        var busyNotified = false
        try {
            while (running) {
                val now = System.currentTimeMillis()
                if (now - lastCheckMs >= POLICY_CHECK_MS) {
                    lastCheckMs = now
                    val s = runtime.state.current
                    // Se apagó la fuente o se quitó el cerebro (sin él nadie transcribe el audio): se deja de escuchar.
                    if (!s.capture.ambientAudio || !s.brainConfigured) break
                    val allowed = runtime.pipeline.decision(Source.AUDIO, "").allowed
                    if (!allowed && capture != null) {
                        // En pausa: se suelta el micrófono (no se escucha) y se cierra el tramo en curso.
                        close(capture, runtime)
                        capture = null
                        showStatus(runtime.notifier.paused(s.capture.pausedUntilMs))
                    }
                    if (allowed && capture == null) {
                        val record = openRecord()
                        if (record != null) {
                            capture = Capture(record)
                            busyNotified = false
                            showStatus(runtime.notifier.listening())
                        } else if (!busyNotified) {
                            busyNotified = true
                            runtime.notifier.needsAttention("El micrófono está ocupado", "Otra app lo está usando. ZEN lo intentará de nuevo en un momento.")
                        }
                    }
                    renewWakeLock()
                }
                val active = capture
                if (active == null) {
                    Thread.sleep(IDLE_SLEEP_MS)
                    continue
                }
                val n = active.record.read(buffer, 0, buffer.size)
                when {
                    n < 0 -> {
                        // El micrófono se perdió (otra app lo tomó): se cierra y se reabre en la siguiente comprobación.
                        close(active, runtime)
                        capture = null
                        lastCheckMs = 0
                        Thread.sleep(IDLE_SLEEP_MS)
                    }
                    n > 0 -> for (segment in active.segmenter.feed(buffer, n)) enqueue(segment, runtime, active.startedAtMs)
                }
            }
        } catch (_: InterruptedException) {
            // onDestroy pidió parar.
        } catch (_: Exception) {
            // Un fallo del audio no debe tumbar la app.
        } finally {
            capture?.let { close(it, runtime) }
            running = false
            LayerLive.audioRunning = false
            stopSelf()
        }
    }

    @Suppress("MissingPermission")
    private fun openRecord(): AudioRecord? = try {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = if (minBuffer <= 0) {
            null
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, SAMPLE_RATE), // al menos 0,5 s
            )
        }
        when {
            record == null -> null
            record.state != AudioRecord.STATE_INITIALIZED -> {
                record.release()
                null
            }
            else -> {
                record.startRecording()
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record
                } else {
                    record.release()
                    null
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun close(capture: Capture, runtime: LayerRuntime) {
        capture.segmenter.flush()?.let { enqueue(it, runtime, capture.startedAtMs) }
        try {
            capture.record.stop()
        } catch (_: IllegalStateException) {
        }
        capture.record.release()
    }

    /** Guarda el tramo y lo encola, en el hilo de E/S para no frenar la lectura del micrófono. */
    private fun enqueue(segment: PcmSegment, runtime: LayerRuntime, recordingStartMs: Long) {
        // Hora real del tramo: cuándo empezó esta grabación más lo que había avanzado hasta el tramo.
        val startedAt = recordingStartMs + segment.startSample * 1000L / SAMPLE_RATE
        runtime.runIo {
            if (runtime.audioFiles.totalBytes() >= MAX_AUDIO_BYTES) return@runIo // tope de espacio de la capa: se descarta
            val name = runtime.audioFiles.save(segment)
            val outcome = runtime.pipeline.onAudioSegment(name, segment.durationMs, startedAt)
            if (!outcome.queued) runtime.audioFiles.delete(name) // sin cerebro (o fuente cerrada) el archivo no sirve
        }
    }

    private fun showStatus(notification: android.app.Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(AndroidNotifier.ID_LISTENING, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zen:layer-audio").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MS)
        }
    }

    /** El cierre de seguridad (10 min) se renueva mientras el servicio vive: nunca queda un wake lock huérfano. */
    private fun renewWakeLock() {
        try {
            wakeLock?.acquire(WAKE_LOCK_MS)
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    enum class StartCheck { OK, SOURCE_OFF, NO_BRAIN, NO_PERMISSION }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val POLICY_CHECK_MS = 5_000L
        private const val IDLE_SLEEP_MS = 2_000L
        private const val WAKE_LOCK_MS = 10 * 60_000L
        private const val MAX_AUDIO_BYTES = 200L * 1024 * 1024

        /** Las precondiciones para escuchar. Nada de esto se salta: sin cerebro no hay quien transcriba, sin permiso no hay audio. */
        fun preconditions(context: Context, runtime: LayerRuntime): StartCheck {
            val s = runtime.state.current
            return when {
                !s.capture.ambientAudio -> StartCheck.SOURCE_OFF
                !s.brainConfigured -> StartCheck.NO_BRAIN
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> StartCheck.NO_PERMISSION
                else -> StartCheck.OK
            }
        }

        /**
         * Arranca la escucha si se puede. Hay que llamarla con la app a la vista (una acción del usuario): Android lo exige
         * para el micrófono en primer plano. Devuelve por qué no, si no arrancó.
         */
        fun start(context: Context): StartCheck {
            val result = preconditions(context, context.layer)
            if (result == StartCheck.OK) ContextCompat.startForegroundService(context, Intent(context, AmbientAudioService::class.java))
            return result
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AmbientAudioService::class.java))
        }
    }
}
