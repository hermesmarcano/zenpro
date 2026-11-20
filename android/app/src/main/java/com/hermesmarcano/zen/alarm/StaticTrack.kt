package com.hermesmarcano.zen.alarm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Construye una pista PCM estática (todo el audio en memoria) por el flujo de alarma. Compartido por alarma y éxito. */
internal object StaticTrack {
    /**
     * Lanza si el sistema no puede crearla o cargarla. Un `write` que devuelve un código de error (< 0:
     * ERROR_INVALID_OPERATION, ERROR_BAD_VALUE, ERROR_DEAD_OBJECT…) deja una pista muda: se trata como
     * fallo para que quien llama use su plan B en vez de "sonar" en silencio. (En dispositivo `write` de
     * una pista estática devuelve las muestras escritas; el simulador de pruebas devuelve 0.)
     */
    fun create(
        samples: FloatArray,
        sampleRate: Int,
        loop: Boolean,
        gain: Float,
        sonification: Boolean = true,
        usage: Int = AudioAttributes.USAGE_ALARM,
    ): AudioTrack {
        require(samples.isNotEmpty()) { "sin muestras" }
        val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(if (sonification) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            val written = track.write(pcm, 0, pcm.size)
            check(written >= 0) { "write devolvió el error $written" }
            if (loop) track.setLoopPoints(0, pcm.size, -1)
            track.setVolume(gain)
            track.play()
            return track
        } catch (e: Exception) {
            track.release()
            throw e
        }
    }
}
