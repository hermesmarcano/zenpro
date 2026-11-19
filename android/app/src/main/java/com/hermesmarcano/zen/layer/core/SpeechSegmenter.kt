package com.hermesmarcano.zen.layer.core

import kotlin.math.sqrt

/** Un tramo de voz recortado del flujo continuo del micrófono. [startSample] es el índice absoluto de su primera muestra. */
class PcmSegment(val samples: ShortArray, val startSample: Long, val sampleRate: Int) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
}

/**
 * Parte el audio continuo del micrófono en tramos de voz, descartando el silencio. Así no se guarda ni
 * se envía horas de nada, y la batería y el ancho de banda se gastan solo cuando alguien habla.
 *
 * Detección por energía con umbral adaptativo (suelo de ruido × factor, con un mínimo absoluto), arranque
 * tras unas tramas seguidas, "cola" de silencio al final (para no cortar entre palabras), un poco de audio
 * previo y un máximo por tramo. No reconoce voz de verdad: un ruido fuerte y sostenido también cuenta.
 */
class SpeechSegmenter(
    private val sampleRate: Int = 16_000,
    frameMs: Int = 20,
    preRollMs: Int = 300,
    hangoverMs: Int = 900,
    minSpeechMs: Int = 400,
    maxSegmentMs: Int = 60_000,
    startMs: Int = 60,
    private val minThreshold: Double = 350.0,
    private val thresholdFactor: Double = 3.0,
) {
    private val frameLen = sampleRate * frameMs / 1000
    private val preRollFrames = maxOf(0, preRollMs / frameMs)
    private val hangoverFrames = maxOf(1, hangoverMs / frameMs)
    private val minSpeechFrames = maxOf(1, minSpeechMs / frameMs)
    private val maxFrames = maxOf(1, maxSegmentMs / frameMs)
    private val startFrames = maxOf(1, startMs / frameMs)

    private val pending = ShortArray(frameLen)
    private var pendingCount = 0
    private var frameIndex = 0L // número de tramas completas ya procesadas

    private var noiseFloor = 100.0
    private val preRoll = ArrayDeque<ShortArray>()
    private val candidate = ArrayList<ShortArray>()

    private var inSpeech = false
    private val body = ArrayList<ShortArray>()
    private var bodyStartFrame = 0L
    private var leadFrames = 0
    private var quietRun = 0

    /** Alimenta con audio PCM de 16 bits mono. Devuelve los tramos que se cerraron con esta entrada. */
    fun feed(samples: ShortArray, count: Int = samples.size): List<PcmSegment> {
        val out = ArrayList<PcmSegment>(0)
        var offset = 0
        val total = minOf(count, samples.size)
        while (offset < total) {
            val take = minOf(frameLen - pendingCount, total - offset)
            System.arraycopy(samples, offset, pending, pendingCount, take)
            pendingCount += take
            offset += take
            if (pendingCount == frameLen) {
                processFrame(pending.copyOf(), out)
                pendingCount = 0
            }
        }
        return out
    }

    /** Cierra el tramo en curso (al parar la grabación). La media trama que sobre se descarta. */
    fun flush(): PcmSegment? {
        val out = ArrayList<PcmSegment>(1)
        if (inSpeech) endSegment(out)
        return out.firstOrNull()
    }

    private fun processFrame(frame: ShortArray, out: MutableList<PcmSegment>) {
        val rms = rms(frame)
        val threshold = maxOf(minThreshold, noiseFloor * thresholdFactor)
        val loud = rms >= threshold
        if (!inSpeech && !loud) noiseFloor = (noiseFloor * 0.95 + rms * 0.05).coerceIn(10.0, minThreshold)

        if (inSpeech) {
            body += frame
            quietRun = if (loud) 0 else quietRun + 1
            if (quietRun >= hangoverFrames || body.size >= maxFrames) endSegment(out)
        } else if (loud) {
            candidate += frame
            if (candidate.size >= startFrames) beginSegment()
        } else {
            // Un chasquido corto no arranca nada: sus tramas pasan al audio previo.
            for (f in candidate) pushPreRoll(f)
            candidate.clear()
            pushPreRoll(frame)
        }
        frameIndex++
    }

    private fun beginSegment() {
        val lead = preRoll.size
        leadFrames = lead
        bodyStartFrame = frameIndex - (candidate.size - 1) - lead
        body.clear()
        body.addAll(preRoll)
        body.addAll(candidate)
        preRoll.clear()
        candidate.clear()
        inSpeech = true
        quietRun = 0
    }

    private fun endSegment(out: MutableList<PcmSegment>) {
        // Duración de la voz: del primer al último fotograma con voz, sin el audio previo ni la cola de silencio.
        val speechFrames = body.size - leadFrames - quietRun
        if (speechFrames >= minSpeechFrames) {
            val pcm = ShortArray(body.size * frameLen)
            body.forEachIndexed { i, f -> System.arraycopy(f, 0, pcm, i * frameLen, frameLen) }
            out += PcmSegment(pcm, bodyStartFrame * frameLen, sampleRate)
        }
        body.clear()
        inSpeech = false
        quietRun = 0
    }

    private fun pushPreRoll(frame: ShortArray) {
        if (preRollFrames == 0) return
        preRoll.addLast(frame)
        while (preRoll.size > preRollFrames) preRoll.removeFirst()
    }

    private fun rms(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        return sqrt(sum / frame.size)
    }
}

/** Empaqueta PCM16 mono en un WAV estándar (cabecera de 44 bytes, little-endian). */
object WavEncoder {
    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = pcm.size * 2
        val out = ByteArray(44 + dataBytes)
        fun int(offset: Int, value: Int) {
            for (i in 0 until 4) out[offset + i] = (value shr (8 * i)).toByte()
        }
        fun short(offset: Int, value: Int) {
            out[offset] = value.toByte()
            out[offset + 1] = (value shr 8).toByte()
        }
        "RIFF".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        int(4, 36 + dataBytes)
        "WAVEfmt ".forEachIndexed { i, c -> out[8 + i] = c.code.toByte() }
        int(16, 16) // tamaño del bloque fmt
        short(20, 1) // PCM
        short(22, 1) // mono
        int(24, sampleRate)
        int(28, sampleRate * 2) // bytes por segundo
        short(32, 2) // bytes por muestra
        short(34, 16) // bits por muestra
        "data".forEachIndexed { i, c -> out[36 + i] = c.code.toByte() }
        int(40, dataBytes)
        var p = 44
        for (s in pcm) {
            out[p++] = s.toInt().toByte()
            out[p++] = (s.toInt() shr 8).toByte()
        }
        return out
    }
}
