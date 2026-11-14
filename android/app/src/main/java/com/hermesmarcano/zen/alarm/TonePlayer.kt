package com.hermesmarcano.zen.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.hermesmarcano.zen.core.AlarmToneSynth

/** Reproduce el tono de alarma en bucle por el flujo de alarma, con volumen propio (0..1). */
class TonePlayer(private val context: Context) {
    private var track: AudioTrack? = null
    private var fallback: Ringtone? = null

    fun start(initialVolume: Float) {
        try {
            val samples = AlarmToneSynth.samples(SAMPLE_RATE)
            val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val created = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            check(created.write(pcm, 0, pcm.size) == pcm.size) { "buffer incompleto" }
            created.setLoopPoints(0, pcm.size, -1)
            created.setVolume(initialVolume)
            created.play()
            track = created
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack falló; se usa el tono del sistema", e)
            startFallback()
        }
    }

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        track?.setVolume(v)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) fallback?.volume = v
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
        fallback?.stop()
        fallback = null
    }

    private fun startFallback() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
            ringtone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.isLooping = true
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
