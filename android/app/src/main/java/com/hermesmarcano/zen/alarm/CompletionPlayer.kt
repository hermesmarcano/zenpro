package com.hermesmarcano.zen.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hermesmarcano.zen.core.CompletionSound
import com.hermesmarcano.zen.core.CompletionToneSynth
import com.hermesmarcano.zen.core.SoundImportPolicy
import com.hermesmarcano.zen.data.SoundChoice
import com.hermesmarcano.zen.sound.SoundStorage

/** Sonido de "ya estás despierto": suena una vez (un archivo largo se corta a 10 s). */
class CompletionPlayer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var media: MediaPlayer? = null
    private val cutOff = Runnable { stop() }

    /** `true` si algo empezó a sonar (un fallo es silencioso: es un premio, no puede estorbar). */
    fun play(choice: SoundChoice): Boolean {
        stop()
        val ok = when (choice) {
            is SoundChoice.Custom -> playFile(choice) || playSynth(CompletionSound.CHIME)
            is SoundChoice.Preset -> playSynth(CompletionSound.fromId(choice.id))
        }
        if (ok) main.postDelayed(cutOff, SoundImportPolicy.COMPLETION_MAX_PLAY_MS)
        return ok
    }

    fun stop() {
        main.removeCallbacks(cutOff)
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
        media?.let {
            runCatching { it.stop() }
            it.release()
        }
        media = null
    }

    private fun playSynth(sound: CompletionSound): Boolean = try {
        track = StaticTrack.create(CompletionToneSynth.samples(sound, SAMPLE_RATE), SAMPLE_RATE, loop = false, gain = 1f)
        true
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo reproducir el sonido de éxito", e)
        false
    }

    private fun playFile(custom: SoundChoice.Custom): Boolean {
        val file = SoundStorage.file(context, custom.fileName) ?: return false
        return try {
            val player = MediaPlayer()
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            player.setDataSource(file.path)
            player.setOnCompletionListener { stop() }
            player.prepare()
            player.start()
            media = player
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo reproducir el archivo de éxito", e)
            false
        }
    }

    private companion object {
        const val TAG = "CompletionPlayer"
        const val SAMPLE_RATE = 44_100
    }
}
