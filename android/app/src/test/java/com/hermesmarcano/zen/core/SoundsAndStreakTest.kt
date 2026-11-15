package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.data.WakeRecorder
import com.hermesmarcano.zen.data.WakeStats
import com.hermesmarcano.zen.data.ZenState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeCurveTest {
    @Test fun endpointsAndMonotonic() {
        assertEquals(0f, VolumeCurve.gain(0f), 0f)
        assertEquals(1f, VolumeCurve.gain(1f), 1e-6f)
        var previous = -1f
        for (i in 0..100) {
            val g = VolumeCurve.gain(i / 100f)
            assertTrue(g >= previous)
            previous = g
        }
    }

    /** El bug que reportó el usuario: "15 %" sonaba fuerte porque la ganancia era lineal (0.15 = −16 dB). */
    @Test fun fifteenPercentIsMuchQuieterThanTheOldLinearGain() {
        val gain = VolumeCurve.gain(0.15f)
        assertTrue("ganancia $gain", gain < 0.05f)
        assertTrue("dB ${VolumeCurve.decibels(0.15f)}", VolumeCurve.decibels(0.15f) < -24f)
        assertTrue(VolumeCurve.decibels(0.15f) < 20f * kotlin.math.log10(0.15f) - 8f) // al menos 8 dB por debajo de lo de antes
    }

    @Test fun halfLevelIsAboutTenDecibelsDown() = assertEquals(-10.2f, VolumeCurve.decibels(0.5f), 0.3f)

    @Test fun outOfRangeAndNaNAreSafe() {
        assertEquals(0f, VolumeCurve.gain(-1f), 0f)
        assertEquals(1f, VolumeCurve.gain(5f), 0f)
        assertEquals(0f, VolumeCurve.gain(Float.NaN), 0f)
    }

    @Test fun startLevelsTheUserCanPickAreAllQuiet() {
        for (percent in listOf(10, 15, 20, 30)) assertTrue("$percent %", VolumeCurve.gain(percent / 100f) < 0.15f)
    }

    @Test fun carriedLevelIsSlightlyBelowTheStartLevel() {
        for (start in listOf(0.10f, 0.15f, 0.20f, 0.30f)) {
            val profile = SmartVolumeProfile().withStartVolume(start)
            assertEquals(start, profile.startVolume, 0f)
            assertTrue(profile.carriedVolume <= start)
            assertTrue(profile.carriedVolume >= 0.05f)
        }
        assertEquals(0.09f, SmartVolumeProfile().withStartVolume(0.15f).carriedVolume, 1e-6f)
    }

    @Test fun controllerStartsAtTheChosenLevelAndDropsWhenCarried() {
        val profile = SmartVolumeProfile().withStartVolume(0.20f)
        val controller = SmartVolumeController(profile, startTime = 0.0)
        assertEquals(0.20f, controller.update(0.1, MotionState.STILL, false).volume, 0.01f)
        var now = 0.1
        var last = 0f
        while (now < 10.0) { now += 0.1; last = controller.update(now, MotionState.MOVING, false).volume }
        assertEquals(profile.carriedVolume, last, 1e-4f)
        assertTrue(last < 0.20f)
    }
}

class SoundSynthTest {
    private val rate = 22_050

    @Test fun everyAlarmSoundIsALoopThatStartsAndEndsSilent() {
        for (sound in AlarmSound.entries) {
            val s = AlarmToneSynth.samples(sound, rate)
            assertEquals("${sound.id} longitud", (AlarmToneSynth.loopSeconds(sound) * rate).toInt(), s.size)
            assertTrue("${sound.id} rango", s.all { it in -1f..1f && !it.isNaN() })
            assertTrue("${sound.id} pico ${s.maxOrNull()}", s.maxOf { kotlin.math.abs(it) } in 0.5f..0.81f)
            assertEquals("${sound.id} inicio", 0f, s.first(), 0.01f)
            assertEquals("${sound.id} final", 0f, s.last(), 0.01f)
            // Los últimos 20 ms en silencio: el bucle no hace clic al reiniciar.
            assertTrue("${sound.id} cola", s.takeLast(rate / 50).all { kotlin.math.abs(it) < 0.02f })
        }
    }

    @Test fun alarmSoundsDifferFromEachOther() {
        val all = AlarmSound.entries.map { AlarmToneSynth.samples(it, rate).take(rate).toList() }
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun classicIsUnchanged() {
        assertEquals(88_200, AlarmToneSynth.samples().size)
        assertTrue(AlarmToneSynth.samples(AlarmSound.CLASSIC, 44_100).contentEquals(AlarmToneSynth.samples(44_100)))
    }

    @Test fun everyCompletionSoundIsAShortClickFreeJingle() {
        for (sound in CompletionSound.entries) {
            val s = CompletionToneSynth.samples(sound, rate)
            assertEquals((CompletionToneSynth.durationSeconds(sound) * rate).toInt(), s.size)
            assertTrue(s.all { it in -1f..1f && !it.isNaN() })
            assertTrue("${sound.id} pico", s.maxOf { kotlin.math.abs(it) } in 0.5f..0.81f)
            assertEquals(0f, s.first(), 0.01f)
            assertTrue("${sound.id} cola", s.takeLast(rate / 50).all { kotlin.math.abs(it) < 0.02f })
            assertTrue("${sound.id} dura <= 4 s", CompletionToneSynth.durationSeconds(sound) <= 4.0)
        }
    }

    @Test fun completionSoundsDiffer() {
        val all = CompletionSound.entries.map { CompletionToneSynth.samples(it, rate).take(rate / 2).toList() }
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun unknownIdsFallBackToTheDefaults() {
        assertEquals(AlarmSound.CLASSIC, AlarmSound.fromId("nope"))
        assertEquals(AlarmSound.CLASSIC, AlarmSound.fromId(null))
        assertEquals(CompletionSound.CHIME, CompletionSound.fromId("nope"))
        assertEquals(AlarmSound.BELL, AlarmSound.fromId("bell"))
    }
}

class SoundImportPolicyTest {
    @Test fun acceptsNormalAudio() = assertEquals(SoundImportPolicy.Result.Ok, SoundImportPolicy.validate(3_000_000, 180_000))

    @Test fun rejectsEmptyHugeUnreadableShortAndLong() {
        fun reason(size: Long, ms: Long?) = (SoundImportPolicy.validate(size, ms) as SoundImportPolicy.Result.Rejected).reason
        assertTrue(reason(0, 1000).contains("vacío"))
        assertTrue(reason(SoundImportPolicy.MAX_BYTES + 1, 1000).contains("pesa"))
        assertTrue(reason(1000, null).contains("audio"))
        assertTrue(reason(1000, 100).contains("corto"))
        assertTrue(reason(1000, SoundImportPolicy.MAX_DURATION_MS + 1).contains("dura"))
    }

    @Test fun boundariesAreInclusive() {
        assertEquals(SoundImportPolicy.Result.Ok, SoundImportPolicy.validate(SoundImportPolicy.MAX_BYTES, SoundImportPolicy.MIN_DURATION_MS))
        assertEquals(SoundImportPolicy.Result.Ok, SoundImportPolicy.validate(1, SoundImportPolicy.MAX_DURATION_MS))
    }

    @Test fun fileNamesCannotEscapeTheirFolder() {
        assertTrue(SoundImportPolicy.isSafeFileName("alarm-123.audio"))
        assertFalse(SoundImportPolicy.isSafeFileName("../state.json"))
        assertFalse(SoundImportPolicy.isSafeFileName("a/b"))
        assertFalse(SoundImportPolicy.isSafeFileName("a\\b"))
        assertFalse(SoundImportPolicy.isSafeFileName(""))
        assertFalse(SoundImportPolicy.isSafeFileName("a".repeat(81)))
    }
}

class StreakPolicyTest {
    private val monday = LocalDate.of(2026, 3, 2)
    private val everyDay = emptySet<Weekday>()
    private val weekdays = setOf(Weekday.MONDAY, Weekday.TUESDAY, Weekday.WEDNESDAY, Weekday.THURSDAY, Weekday.FRIDAY)

    private fun wake(stats: WakeStats, day: LocalDate, days: Set<Weekday> = everyDay, seconds: Int = 60) =
        StreakPolicy.record(stats, day, days, seconds)

    @Test fun firstWakeStartsAStreakOfOne() {
        val s = wake(WakeStats(), monday)
        assertEquals(1, s.currentStreak); assertEquals(1, s.bestStreak); assertEquals(1, s.totalWakeups)
        assertEquals(monday.toEpochDay(), s.lastWakeDay)
    }

    @Test fun consecutiveDaysGrowTheStreak() {
        var s = WakeStats()
        for (i in 0L until 5) s = wake(s, monday.plusDays(i))
        assertEquals(5, s.currentStreak); assertEquals(5, s.bestStreak); assertEquals(5, s.totalWakeups)
    }

    @Test fun missingADayResetsButKeepsTheBest() {
        var s = WakeStats()
        for (i in 0L until 4) s = wake(s, monday.plusDays(i))
        s = wake(s, monday.plusDays(6)) // saltó 4 y 5
        assertEquals(1, s.currentStreak); assertEquals(4, s.bestStreak); assertEquals(5, s.totalWakeups)
    }

    @Test fun secondWakeTheSameDayCountsInTheTotalOnly() {
        var s = wake(WakeStats(), monday)
        s = wake(s, monday, seconds = 30)
        assertEquals(1, s.currentStreak); assertEquals(2, s.totalWakeups); assertEquals(30, s.lastSolveSeconds)
    }

    @Test fun weekendDoesNotBreakAWeekdayStreak() {
        var s = WakeStats()
        for (i in 0L until 5) s = wake(s, monday.plusDays(i), weekdays) // lun-vie
        s = wake(s, monday.plusDays(7), weekdays) // lunes siguiente
        assertEquals(6, s.currentStreak)
    }

    @Test fun skippingAScheduledWeekdayStillBreaksIt() {
        var s = wake(WakeStats(), monday, weekdays)
        s = wake(s, monday.plusDays(2), weekdays) // se saltó el martes
        assertEquals(1, s.currentStreak)
    }

    @Test fun anExtraWakeOnADayOffKeepsTheStreakAlive() {
        var s = wake(WakeStats(), monday.plusDays(4), weekdays) // viernes
        s = wake(s, monday.plusDays(5), weekdays)               // sábado, no programado
        assertEquals(2, s.currentStreak)
        s = wake(s, monday.plusDays(7), weekdays)               // lunes
        assertEquals(3, s.currentStreak)
    }

    @Test fun clockGoingBackwardsRestartsInsteadOfCorrupting() {
        var s = wake(WakeStats(), monday.plusDays(10))
        s = wake(s, monday)
        assertEquals(1, s.currentStreak)
        assertTrue(s.bestStreak >= 1)
    }

    @Test fun negativeSolveTimeIsClamped() = assertEquals(0, wake(WakeStats(), monday, seconds = -5).lastSolveSeconds)

    @Test fun displayedStreakDecaysOnceADayIsMissed() {
        var s = WakeStats()
        for (i in 0L until 3) s = wake(s, monday.plusDays(i))
        assertEquals(3, StreakPolicy.displayStreak(s, monday.plusDays(2), everyDay)) // hoy
        assertEquals(3, StreakPolicy.displayStreak(s, monday.plusDays(3), everyDay)) // aún puede mantenerla hoy
        assertEquals(0, StreakPolicy.displayStreak(s, monday.plusDays(4), everyDay)) // ayer se la saltó
        assertEquals(0, StreakPolicy.displayStreak(WakeStats(), monday, everyDay))
        assertEquals(0, StreakPolicy.displayStreak(s, monday.minusDays(5), everyDay)) // reloj atrás
    }

    @Test fun weekendDoesNotDecayAWeekdayStreakDisplay() {
        var s = WakeStats()
        for (i in 0L until 5) s = wake(s, monday.plusDays(i), weekdays)
        assertEquals(5, StreakPolicy.displayStreak(s, monday.plusDays(6), weekdays)) // domingo
    }

    @Test fun milestonesAndTimeText() {
        assertEquals("Una semana entera.", StreakPolicy.milestone(7))
        assertNull(StreakPolicy.milestone(8))
        assertEquals("45 s", StreakPolicy.solveTimeText(45))
        assertEquals("1 min 12 s", StreakPolicy.solveTimeText(72))
        assertEquals("0 s", StreakPolicy.solveTimeText(-3))
    }

    @Test fun recorderUsesTheDaysOfEnabledRepeatingAlarmsOnly() {
        val state = ZenState(
            alarms = listOf(
                AlarmEntity("a", 7, 0, setOf(Weekday.MONDAY), true, null, null),
                AlarmEntity("b", 7, 0, setOf(Weekday.SATURDAY), false, null, null), // desactivada: no cuenta
                AlarmEntity("c", 7, 0, emptySet(), true, null, null),                // una vez: no cuenta
            ),
        )
        assertEquals(setOf(Weekday.MONDAY), WakeRecorder.scheduledDays(state))
        assertEquals(emptySet<Weekday>(), WakeRecorder.scheduledDays(ZenState()))
        val updated = WakeRecorder.record(state, monday, 42)
        assertEquals(1, updated.stats.currentStreak)
        assertEquals(1, WakeRecorder.currentStreak(updated, monday))
        assertNotNull(updated.stats.lastSolveSeconds)
    }
}
