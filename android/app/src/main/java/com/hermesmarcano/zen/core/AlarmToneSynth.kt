package com.hermesmarcano.zen.core

import kotlin.math.PI
import kotlin.math.sin

/**
 * Sintetiza el tono de la alarma (sin assets ni licencias): cuatro pitidos alternando dos
 * notas, seguidos de un silencio, en un bucle de 2 s. Empieza y termina en cero para que el
 * bucle no tenga clics.
 */
object AlarmToneSynth {
    const val LOOP_DURATION = 2.0
    private const val BEEP_DURATION = 0.12
    private val beepStarts = doubleArrayOf(0.0, 0.2, 0.4, 0.6)
    private val frequencies = doubleArrayOf(880.0, 1175.0, 880.0, 1175.0)
    private const val ENVELOPE = 0.008
    private const val AMPLITUDE = 0.8f

    fun samples(sampleRate: Int = 44_100): FloatArray {
        val frameCount = (LOOP_DURATION * sampleRate).toInt()
        val buffer = FloatArray(frameCount)
        val beepFrames = (BEEP_DURATION * sampleRate).toInt()
        val envelopeFrames = maxOf(1, (ENVELOPE * sampleRate).toInt())

        for ((index, start) in beepStarts.withIndex()) {
            val firstFrame = (start * sampleRate).toInt()
            val frequency = frequencies[index]
            for (offset in 0 until beepFrames) {
                val frame = firstFrame + offset
                if (frame >= frameCount) break
                val fadeIn = minOf(1f, offset.toFloat() / envelopeFrames)
                val fadeOut = minOf(1f, (beepFrames - 1 - offset).toFloat() / envelopeFrames)
                val phase = 2 * PI * frequency * offset / sampleRate
                buffer[frame] = AMPLITUDE * fadeIn * fadeOut * sin(phase).toFloat()
            }
        }
        return buffer
    }
}
