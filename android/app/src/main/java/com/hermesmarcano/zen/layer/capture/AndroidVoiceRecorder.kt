package com.hermesmarcano.zen.layer.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.hermesmarcano.zen.layer.core.ChatRules
import com.hermesmarcano.zen.layer.link.VoiceRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Graba la voz del chat con el micrófono (16 kHz, mono, 16 bits: lo que el servidor espera), en memoria y hasta
 * [ChatRules.MAX_VOICE_MS]. Nada se escribe en disco. Una grabación a la vez; si el micrófono está ocupado, lo dice.
 *
 * Mientras graba, [LayerLive.chatRecording] está activo: la escucha ambiental (si estaba encendida) suelta el micrófono para no
 * oír ni guardar lo que se le dice al chat.
 *
 * Al llegar al tope (o si el micrófono se pierde), el hilo de captura avisa con `onLimit` y termina; quien recibe el aviso
 * llama a [stop] desde OTRO hilo (así nunca se espera a sí mismo).
 */
class AndroidVoiceRecorder(private val context: Context) : VoiceRecorder {
    /** Una grabación: su propio micrófono, su propio búfer y su propia bandera (una captura vieja atascada no puede cortar la siguiente). */
    private class Run(val record: AudioRecord, val samples: ShortArray) {
        val running = AtomicBoolean(true)

        @Volatile var count = 0
        var thread: Thread? = null
    }

    private val lock = Any()
    private var current: Run? = null

    override fun start(onLimit: () -> Unit): VoiceRecorder.Start {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return VoiceRecorder.Start.NO_PERMISSION
        }
        synchronized(lock) {
            if (current != null) return VoiceRecorder.Start.MIC_BUSY
            // El búfer (~1 MB) se reserva ANTES de levantar la bandera y de abrir el micrófono: si no hay memoria, no queda nada a medias.
            val samples = try {
                ShortArray((ChatRules.MAX_VOICE_MS * ChatRules.SAMPLE_RATE / 1000).toInt())
            } catch (_: OutOfMemoryError) {
                return VoiceRecorder.Start.MIC_BUSY
            }
            // La bandera va antes de abrir: así la escucha ambiental suelta su micrófono lo antes posible.
            LayerLive.chatRecordingAtMs = System.currentTimeMillis()
            LayerLive.chatRecording = true
            var opened = open()
            if (opened == null && LayerLive.audioRunning) {
                // La escucha ambiental tarda hasta ~100 ms en soltar el suyo (su lectura bloquea): un segundo intento corto.
                try {
                    Thread.sleep(REOPEN_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                opened = open()
            }
            if (opened == null) {
                LayerLive.chatRecording = false
                return VoiceRecorder.Start.MIC_BUSY
            }
            val run = Run(opened, samples)
            try {
                current = run
                run.thread = Thread({ capture(run, onLimit) }, "zen-chat-voice").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: Throwable) {
                // Sin hilo (o sin memoria) no hay grabación: se suelta todo y no queda la bandera levantada.
                current = null
                try {
                    opened.release()
                } catch (_: Exception) {
                }
                LayerLive.chatRecording = false
                return VoiceRecorder.Start.MIC_BUSY
            }
        }
        return VoiceRecorder.Start.STARTED
    }

    override fun stop(): ShortArray? = synchronized(lock) {
        val run = current ?: return null
        finish(run)
        run.samples.copyOf(run.count)
    }

    override fun cancel() {
        synchronized(lock) {
            val run = current ?: return
            finish(run)
        }
    }

    /** Para el hilo de captura y suelta el micrófono. Llamar con [lock] tomado. */
    private fun finish(run: Run) {
        run.running.set(false)
        try {
            try {
                run.thread?.join(JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            try {
                run.record.stop()
            } catch (_: IllegalStateException) {
            }
            run.record.release()
        } finally {
            // Pase lo que pase al soltar el micrófono, esta grabación ya no existe y la escucha ambiental puede volver.
            current = null
            LayerLive.chatRecording = false
        }
    }

    private fun capture(run: Run, onLimit: () -> Unit) {
        val chunk = ShortArray(ChatRules.SAMPLE_RATE / 10) // 100 ms
        try {
            while (run.running.get()) {
                val room = run.samples.size - run.count
                if (room <= 0) break // tope de duración
                val n = run.record.read(chunk, 0, minOf(chunk.size, room))
                if (n < 0) break // el micrófono se perdió (otra app lo tomó): se queda con lo grabado hasta ahora
                if (n > 0) {
                    System.arraycopy(chunk, 0, run.samples, run.count, n)
                    run.count += n
                }
            }
        } catch (_: Exception) {
            // Un fallo del audio no debe tumbar la app: se trata como el fin de la grabación.
        }
        // Salir del bucle sin que nadie lo pidiera (tope, micrófono perdido o fallo): hay que avisar para que se termine y se envíe.
        if (run.running.compareAndSet(true, false)) onLimit()
    }

    @Suppress("MissingPermission")
    private fun open(): AudioRecord? {
        var candidate: AudioRecord? = null
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(ChatRules.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) return null
            candidate = AudioRecord(
                MediaRecorder.AudioSource.MIC, ChatRules.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, ChatRules.SAMPLE_RATE),
            )
            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                candidate.release()
                return null
            }
            candidate.startRecording()
            if (candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                candidate
            } else {
                candidate.release()
                null
            }
        } catch (_: Exception) {
            try {
                candidate?.release()
            } catch (_: Exception) {
            }
            null
        }
    }

    private companion object {
        const val JOIN_MS = 1_000L
        const val REOPEN_DELAY_MS = 250L
    }
}
