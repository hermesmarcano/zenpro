package com.hermesmarcano.zen.layer.core

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RATE = 16_000

/** [ms] de un tono que simula voz (muy por encima del umbral). */
private fun tone(ms: Int, amplitude: Int = 3_000): ShortArray =
    ShortArray(RATE * ms / 1000) { (amplitude * sin(2 * PI * 220 * it / RATE)).toInt().toShort() }

/** Ruido de fondo bajo, muy por debajo del umbral mínimo. */
private fun quiet(ms: Int): ShortArray = ShortArray(RATE * ms / 1000) { (if (it % 2 == 0) 20 else -20).toShort() }

private fun concat(vararg parts: ShortArray): ShortArray = parts.fold(ShortArray(0)) { acc, p -> acc + p }

class SpeechSegmenterTest {
    @Test fun silenceProducesNothing() {
        val s = SpeechSegmenter()
        assertTrue(s.feed(quiet(5_000)).isEmpty())
        assertNull(s.flush())
    }

    @Test fun oneUtteranceIsOneSegmentWithPreRollAndTail() {
        val s = SpeechSegmenter()
        val out = s.feed(concat(quiet(1_000), tone(1_000), quiet(2_000)))
        assertEquals(1, out.size)
        val seg = out.single()
        // ~1 s de voz + hasta 300 ms previos + 900 ms de cola.
        assertTrue("duración ${seg.durationMs}", seg.durationMs in 1_000..2_400)
        // Empieza antes que la voz (que arranca en la muestra 16000) gracias al audio previo.
        assertTrue(seg.startSample in 10_000..16_000)
    }

    @Test fun aShortBlipIsIgnored() {
        val s = SpeechSegmenter()
        assertTrue(s.feed(concat(quiet(500), tone(100), quiet(2_000))).isEmpty())
    }

    @Test fun twoUtterancesSeparatedByLongSilenceAreTwoSegments() {
        val s = SpeechSegmenter()
        val out = s.feed(concat(quiet(500), tone(800), quiet(2_000), tone(800), quiet(2_000)))
        assertEquals(2, out.size)
        assertTrue(out[1].startSample > out[0].startSample + out[0].samples.size)
    }

    @Test fun aShortPauseDoesNotSplitASentence() {
        val s = SpeechSegmenter()
        val out = s.feed(concat(tone(600), quiet(400), tone(600), quiet(2_000)))
        assertEquals(1, out.size)
    }

    @Test fun veryLongSpeechIsSplitAtTheMaximum() {
        val s = SpeechSegmenter(maxSegmentMs = 10_000)
        val out = s.feed(concat(tone(35_000), quiet(2_000)))
        assertTrue("segmentos ${out.size}", out.size >= 3)
        assertTrue(out.all { it.durationMs <= 10_000 })
    }

    @Test fun chunkingDoesNotChangeTheResult() {
        val audio = concat(quiet(500), tone(1_200), quiet(2_000), tone(900), quiet(2_000))
        val bulk = SpeechSegmenter().feed(audio)
        val chunked = SpeechSegmenter()
        val pieces = ArrayList<PcmSegment>()
        var i = 0
        val sizes = listOf(1, 7, 333, 1_001, 4_097)
        var k = 0
        while (i < audio.size) {
            val n = minOf(sizes[k++ % sizes.size], audio.size - i)
            pieces += chunked.feed(audio.copyOfRange(i, i + n))
            i += n
        }
        assertEquals(bulk.size, pieces.size)
        for ((a, b) in bulk.zip(pieces)) {
            assertEquals(a.startSample, b.startSample)
            assertEquals(a.samples.size, b.samples.size)
        }
    }

    @Test fun flushClosesTheSegmentInProgress() {
        val s = SpeechSegmenter()
        assertTrue(s.feed(tone(1_000)).isEmpty())
        assertNotNull(s.flush())
        assertNull(s.flush())
    }

    @Test fun flushOfAShortBlipReturnsNothing() {
        val s = SpeechSegmenter()
        s.feed(tone(80))
        assertNull(s.flush())
    }

    @Test fun feedHonoursTheCountArgument() {
        val s = SpeechSegmenter()
        val buffer = concat(tone(1_000), quiet(2_000))
        // Solo se toman las primeras 1000 ms de la memoria intermedia (el resto es basura de otra lectura).
        assertTrue(s.feed(buffer, RATE).isEmpty())
        assertNotNull(s.flush())
    }
}

class WavEncoderTest {
    private fun u32(b: ByteArray, o: Int) = (0..3).sumOf { (b[o + it].toLong() and 0xff) shl (8 * it) }
    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8))

    @Test fun headerDescribesMono16BitPcm() {
        val wav = WavEncoder.encode(shortArrayOf(1, -2, 300), 16_000)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVEfmt ", String(wav, 8, 8))
        assertEquals("data", String(wav, 36, 4))
        assertEquals(44L + 6, wav.size.toLong())
        assertEquals(36L + 6, u32(wav, 4))
        assertEquals(16L, u32(wav, 16))
        assertEquals(1, u16(wav, 20)) // PCM
        assertEquals(1, u16(wav, 22)) // mono
        assertEquals(16_000L, u32(wav, 24))
        assertEquals(32_000L, u32(wav, 28))
        assertEquals(2, u16(wav, 32))
        assertEquals(16, u16(wav, 34))
        assertEquals(6L, u32(wav, 40))
    }

    @Test fun samplesAreLittleEndianAndSigned() {
        val wav = WavEncoder.encode(shortArrayOf(0x0102, -2), 8_000)
        assertEquals(0x02, wav[44].toInt())
        assertEquals(0x01, wav[45].toInt())
        assertEquals(0xFE, wav[46].toInt() and 0xff)
        assertEquals(0xFF, wav[47].toInt() and 0xff)
    }

    @Test fun emptyAudioIsJustTheHeader() {
        val wav = WavEncoder.encode(ShortArray(0), 16_000)
        assertEquals(44, wav.size)
        assertEquals(0L, u32(wav, 40))
    }
}
