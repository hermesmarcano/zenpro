package com.hermesmarcano.zen.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodePolicyTest {
    @Test fun unavailableUntilTheDelayPasses() {
        assertFalse(BackupCodePolicy.isAvailable(since = 100.0, now = 100.0))
        assertFalse(BackupCodePolicy.isAvailable(100.0, 100.0 + BackupCodePolicy.DELAY_SECONDS - 0.5))
        assertTrue(BackupCodePolicy.isAvailable(100.0, 100.0 + BackupCodePolicy.DELAY_SECONDS))
    }

    @Test fun clockGoingBackwardsAppliesTheFullWait() =
        assertEquals(BackupCodePolicy.DELAY_SECONDS, BackupCodePolicy.remaining(100.0, 50.0), 0.0)

    @Test fun sanitizeKeepsAsciiDigitsOnlyAndCaps() {
        assertEquals("123456", BackupCodePolicy.sanitize("12a3-4 5678"))
        assertEquals("", BackupCodePolicy.sanitize("٣٤٥")) // dígitos árabes no valen
        assertEquals("", BackupCodePolicy.sanitize(""))
    }

    @Test fun countdownText() {
        assertEquals("2:00", BackupCodePolicy.countdownText(120.0))
        assertEquals("1:05", BackupCodePolicy.countdownText(64.2))
        assertEquals("0:00", BackupCodePolicy.countdownText(0.0))
    }
}

class NextFireCalculatorTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = madrid) =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone)

    // 2026-03-02 es lunes.
    @Test fun oneShotLaterTodayFiresToday() {
        val next = NextFireCalculator.nextFire(at(2026, 3, 2, 5, 0), 6, 30, emptySet())!!
        assertEquals(at(2026, 3, 2, 6, 30), next)
    }

    @Test fun oneShotAlreadyPassedFiresTomorrow() {
        val next = NextFireCalculator.nextFire(at(2026, 3, 2, 7, 0), 6, 30, emptySet())!!
        assertEquals(at(2026, 3, 3, 6, 30), next)
    }

    @Test fun strictlyAfterTheReferenceInstant() {
        val next = NextFireCalculator.nextFire(at(2026, 3, 2, 6, 30), 6, 30, emptySet())!!
        assertEquals(at(2026, 3, 3, 6, 30), next)
    }

    @Test fun weekdaysSkipToTheNextSelectedDay() {
        val days = setOf(Weekday.WEDNESDAY, Weekday.FRIDAY)
        assertEquals(at(2026, 3, 4, 6, 30), NextFireCalculator.nextFire(at(2026, 3, 2, 7, 0), 6, 30, days))
        assertEquals(at(2026, 3, 6, 6, 30), NextFireCalculator.nextFire(at(2026, 3, 4, 7, 0), 6, 30, days))
    }

    @Test fun singleWeekdayAlreadyPassedWrapsAWeek() {
        val next = NextFireCalculator.nextFire(at(2026, 3, 2, 7, 0), 6, 30, setOf(Weekday.MONDAY))!!
        assertEquals(at(2026, 3, 9, 6, 30), next)
    }

    @Test fun midnightAlarmRollsOver() {
        val next = NextFireCalculator.nextFire(at(2026, 3, 2, 23, 59), 0, 0, emptySet())!!
        assertEquals(at(2026, 3, 3, 0, 0), next)
    }

    @Test fun springForwardGapMovesAheadInsteadOfFailing() {
        // En Madrid el 29-mar-2026 a las 02:00 los relojes saltan a las 03:00: 02:30 no existe.
        val next = NextFireCalculator.nextFire(at(2026, 3, 28, 12, 0), 2, 30, setOf(Weekday.SUNDAY))!!
        assertEquals(29, next.dayOfMonth)
        assertTrue(next.hour >= 3 || (next.hour == 3 && next.minute >= 0))
    }

    @Test fun fallBackKeepsTheWallClockTime() {
        val next = NextFireCalculator.nextFire(at(2026, 10, 24, 12, 0), 6, 30, setOf(Weekday.SUNDAY))!!
        assertEquals(6, next.hour)
        assertEquals(30, next.minute)
        assertEquals(25, next.dayOfMonth)
    }

    @Test fun invalidTimeReturnsNull() {
        assertNull(NextFireCalculator.nextFire(at(2026, 3, 2, 7, 0), 24, 0, emptySet()))
        assertNull(NextFireCalculator.nextFire(at(2026, 3, 2, 7, 0), 6, 60, emptySet()))
        assertNull(NextFireCalculator.nextFire(at(2026, 3, 2, 7, 0), -1, 0, emptySet()))
    }

    @Test fun millisVariantMatches() {
        val after = at(2026, 3, 2, 7, 0)
        val millis = NextFireCalculator.nextFireMillis(after.toInstant().toEpochMilli(), 6, 30, emptySet(), madrid)
        assertEquals(at(2026, 3, 3, 6, 30).toInstant().toEpochMilli(), millis)
    }
}

class WeekdayTest {
    @Test fun summaries() {
        assertEquals("Una vez", Weekday.summary(emptySet()))
        assertEquals("Todos los días", Weekday.summary(Weekday.entries.toSet()))
        assertEquals("L X V", Weekday.summary(setOf(Weekday.FRIDAY, Weekday.MONDAY, Weekday.WEDNESDAY)))
    }

    @Test fun isoRoundTrip() {
        for (d in Weekday.entries) assertEquals(d, Weekday.fromIso(d.iso))
        assertNull(Weekday.fromIso(0))
        assertNull(Weekday.fromIso(8))
    }

    @Test fun mondayFirst() = assertEquals(Weekday.MONDAY, Weekday.mondayFirst.first())
}

class AlarmToneSynthTest {
    @Test fun loopHasTheExpectedLengthAndRange() {
        val s = AlarmToneSynth.samples(44_100)
        assertEquals(88_200, s.size)
        assertTrue(s.all { it in -1f..1f })
        assertTrue(s.maxOrNull()!! > 0.5f)
    }

    @Test fun loopsWithoutClicks() {
        val s = AlarmToneSynth.samples(44_100)
        assertEquals(0f, s.first(), 1e-3f)
        assertEquals(0f, s.last(), 1e-3f)
    }

    @Test fun endsInSilenceBeforeTheLoopRestarts() {
        val s = AlarmToneSynth.samples(44_100)
        val tail = s.copyOfRange(s.size - 10_000, s.size)
        assertTrue(tail.all { it == 0f })
    }

    @Test fun otherSampleRatesWork() {
        assertEquals(96_000, AlarmToneSynth.samples(48_000).size)
    }
}

class PhotoMatchPolicyTest {
    @Test fun bestDistanceIgnoresInvalidValues() {
        assertEquals(0.4f, PhotoMatchPolicy.bestDistance(listOf(Float.NaN, 0f, -1f, Float.POSITIVE_INFINITY, 0.7f, 0.4f))!!, 0f)
        assertNull(PhotoMatchPolicy.bestDistance(listOf(0f, Float.NaN)))
        assertNull(PhotoMatchPolicy.bestDistance(emptyList()))
    }

    @Test fun matchIsStrictlyBelowThreshold() {
        val t = PhotoMatchPolicy.DEFAULT_THRESHOLD
        assertTrue(PhotoMatchPolicy.isMatch(listOf(t - 0.01f)))
        assertFalse(PhotoMatchPolicy.isMatch(listOf(t)))
        assertFalse(PhotoMatchPolicy.isMatch(listOf(t + 0.1f)))
        assertFalse(PhotoMatchPolicy.isMatch(emptyList()))
    }

    @Test fun degenerateZeroDistancesNeverMatch() = assertFalse(PhotoMatchPolicy.isMatch(listOf(0f, 0f)))

    @Test fun anyReferenceCanMatch() = assertTrue(PhotoMatchPolicy.isMatch(listOf(1.5f, 0.2f)))

    @Test fun hintAfterRepeatedFailures() {
        assertFalse(PhotoMatchPolicy.shouldShowHint(PhotoMatchPolicy.FAILURES_BEFORE_HINT - 1))
        assertTrue(PhotoMatchPolicy.shouldShowHint(PhotoMatchPolicy.FAILURES_BEFORE_HINT))
    }
}

class QrCodecTest {
    private val payload = "zen://anchor/3f2b8c1e-5d4a-4e7b-9a10-aabbccddeeff/Zk3-secret_Q9"

    @Test fun roundTripThroughAnArgbRaster() {
        val matrix = QrCodec.encode(payload)
        val scale = 6
        val side = matrix.size * scale
        assertEquals(payload, QrCodec.decodeArgb(matrix.toArgb(scale), side, side))
    }

    @Test fun roundTripThroughALuminancePlaneWithRowPadding() {
        val matrix = QrCodec.encode(payload)
        val scale = 5
        val side = matrix.size * scale
        val argb = matrix.toArgb(scale)
        val stride = side + 24 // las cámaras reales rellenan cada fila
        val y = ByteArray(stride * side) { 0x80.toByte() }
        for (row in 0 until side) for (col in 0 until side) {
            y[row * stride + col] = if (argb[row * side + col] == QrMatrix.BLACK) 0 else 0xFF.toByte()
        }
        assertEquals(payload, QrCodec.decodeLuminance(y, side, side, stride))
    }

    @Test fun decodesAfterModerateDownscaleAndDimming() {
        val matrix = QrCodec.encode(payload)
        val scale = 3
        val side = matrix.size * scale
        val dim = matrix.toArgb(scale).map { if (it == QrMatrix.BLACK) 0xFF301818.toInt() else 0xFF8C8C8C.toInt() }.toIntArray()
        assertEquals(payload, QrCodec.decodeArgb(dim, side, side))
    }

    @Test fun quietZoneAddsFourModulesPerSideAndIsWhite() {
        val bare = QrCodec.encode(payload, quietZone = 0)
        val padded = QrCodec.encode(payload)
        assertEquals(bare.size + 8, padded.size)
        for (i in 0 until padded.size) {
            for (m in 0 until 4) {
                assertFalse(padded[i, m]); assertFalse(padded[m, i])
                assertFalse(padded[i, padded.size - 1 - m]); assertFalse(padded[padded.size - 1 - m, i])
            }
        }
    }

    @Test fun negativeQuietZoneIsTreatedAsNone() =
        assertEquals(QrCodec.encode(payload, 0).size, QrCodec.encode(payload, -3).size)

    @Test fun blankFrameDecodesToNull() {
        assertNull(QrCodec.decodeArgb(IntArray(200 * 200) { QrMatrix.WHITE }, 200, 200))
        assertNull(QrCodec.decodeLuminance(ByteArray(100 * 100), 100, 100))
    }

    @Test fun badBufferSizesAreRejectedNotThrown() {
        assertNull(QrCodec.decodeLuminance(ByteArray(10), 100, 100))
        assertNull(QrCodec.decodeArgb(IntArray(10), 100, 100))
        assertNull(QrCodec.decodeArgb(IntArray(0), 0, 0))
    }

    @Test fun differentPayloadsProduceDifferentCodes() {
        val a = QrCodec.encode("zen://anchor/a/1")
        val b = QrCodec.encode("zen://anchor/b/2")
        val side = maxOf(a.size, b.size) * 4
        assertNotNull(QrCodec.decodeArgb(a.toArgb(4), a.size * 4, a.size * 4))
        assertTrue(QrCodec.decodeArgb(b.toArgb(4), b.size * 4, b.size * 4) != QrCodec.decodeArgb(a.toArgb(4), a.size * 4, a.size * 4))
        assertTrue(side > 0)
    }
}

class RingCountdownFormatterTest {
    private fun min(m: Long) = m * 60_000

    @Test fun formats() {
        assertEquals("Suena en menos de 1 min", RingCountdownFormatter.format(30_000))
        assertEquals("Suena en menos de 1 min", RingCountdownFormatter.format(-5_000))
        assertEquals("Suena en 12 min", RingCountdownFormatter.format(min(12)))
        assertEquals("Suena en 7 h 12 min", RingCountdownFormatter.format(min(7 * 60 + 12)))
        assertEquals("Suena en 2 d 3 h", RingCountdownFormatter.format(min(2 * 24 * 60 + 3 * 60 + 10)))
    }

    @Test fun roundsUpPartialMinutes() = assertEquals("Suena en 13 min", RingCountdownFormatter.format(min(12) + 1_000))
}
