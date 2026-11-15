package com.hermesmarcano.zen.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.hermesmarcano.zen.core.AlarmSound
import com.hermesmarcano.zen.core.AlarmToneSynth
import com.hermesmarcano.zen.core.VolumeCurve
import com.hermesmarcano.zen.data.SoundChoice
import com.hermesmarcano.zen.sound.SoundStorage

/**
 * Reproduce el sonido de alarma en bucle por el flujo de alarma. Se maneja con un *nivel* (0..1,
 * sonoridad percibida) que aquí se convierte en ganancia con [VolumeCurve]. Si el sonido elegido
 * es un archivo y falla, cae al tono clásico: la alarma nunca se queda muda por un archivo roto.
 */
class TonePlayer(private val context: Context, private val choice: SoundChoice = SoundChoice.Preset(AlarmSound.CLASSIC.id)) {
    private var track: AudioTrack? = null
    private var media: MediaPlayer? = null
    private var fallback: Ringtone? = null

    /** Última ganancia lineal aplicada (visible para pruebas). */
    @Volatile
    var currentGain: Float = 0f
        private set

    @Volatile
    private var level: Float = 0f

    fun start(initialLevel: Float) {
        level = initialLevel
        currentGain = VolumeCurve.gain(initialLevel)
        val started = when (choice) {
            is SoundChoice.Custom -> startFile(choice)
            is SoundChoice.Preset -> false
        }
        if (!started) {
            val sound = (choice as? SoundChoice.Preset)?.let { AlarmSound.fromId(it.id) } ?: AlarmSound.CLASSIC
            startSynth(sound)
        }
    }

    /** [volume] es el nivel 0..1; la curva perceptual se aplica aquí. */
    fun setVolume(volume: Float) {
        level = volume
        val gain = VolumeCurve.gain(volume)
        currentGain = gain
        track?.setVolume(gain)
        try {
            media?.setVolume(gain, gain)
        } catch (_: IllegalStateException) {
            // El reproductor ya se liberó: se está parando.
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) fallback?.volume = gain
    }

    fun stop() {
        track?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        track = null
        media?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        media = null
        fallback?.stop()
        fallback = null
    }

    private fun startFile(custom: SoundChoice.Custom): Boolean {
        val file = SoundStorage.file(context, custom.fileName) ?: return false
        return try {
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            player.setDataSource(file.path)
            player.isLooping = true
            player.setOnErrorListener { _, what, extra ->
                // Archivo que deja de decodificar a mitad: sustituirlo por el tono clásico, sin silencio.
                Log.e(TAG, "MediaPlayer falló ($what/$extra); se usa el tono clásico")
                media?.release()
                media = null
                startSynth(AlarmSound.CLASSIC)
                true
            }
            player.prepare()
            player.setVolume(currentGain, currentGain)
            player.start()
            media = player
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo reproducir el archivo; se usa el tono clásico", e)
            media?.release()
            media = null
            false
        }
    }

    private fun startSynth(sound: AlarmSound) {
        try {
            track = StaticTrack.create(AlarmToneSynth.samples(sound, SAMPLE_RATE), SAMPLE_RATE, loop = true, gain = currentGain)
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack falló; se usa el tono del sistema", e)
            startFallback()
        }
    }

    private fun startFallback() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
            ringtone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.isLooping = true
                ringtone.volume = currentGain
            }
            ringtone.play()
            fallback = ringtone
        } catch (e: Exception) {
            Log.e(TAG, "Tampoco se pudo reproducir el tono del sistema", e)
        }
    }

    private companion object {
        const val TAG = "TonePlayer"
        const val SAMPLE_RATE = 44_100
    }
}
