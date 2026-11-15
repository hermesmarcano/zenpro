package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.ScanDebouncer
import com.hermesmarcano.zen.core.Weekday
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StateRepositoryTest {
    private val dir: File = Files.createTempDirectory("zen-test").toFile()
    private val file = File(dir, "state.json")

    private val qr = QrAnchor("a1", "Espejo del baño", "sec_ret-1", "042917")
    private val photo = PhotoAnchor("p1", "Patio", "000123", listOf("ref-0.jpg", "ref-1.jpg"))
    private val alarm = AlarmEntity("al1", 6, 30, setOf(Weekday.MONDAY, Weekday.FRIDAY), true, "a1", 1_700_000_000_000L)

    @Test fun missingFileStartsEmpty() {
        assertEquals(ZenState(), StateRepository(file).state.value)
    }

    @Test fun roundTripPreservesEverything() {
        val repo = StateRepository(file)
        repo.update { ZenState(listOf(alarm, alarm.copy(id = "al2", weekdays = emptySet(), anchorId = null, nextFireAt = null, enabled = false)), listOf(qr, photo)) }
        val loaded = StateRepository(file).state.value
        assertEquals(2, loaded.alarms.size)
        assertEquals(alarm, loaded.alarms[0])
        assertNull(loaded.alarms[1].anchorId)
        assertNull(loaded.alarms[1].nextFireAt)
        assertEquals(listOf<Anchor>(qr, photo), loaded.anchors)
    }

    @Test fun backupCodeKeepsLeadingZeros() {
        val repo = StateRepository(file)
        repo.update { it.copy(anchors = listOf(photo)) }
        assertEquals("000123", StateRepository(file).state.value.anchors.single().backupCode)
    }

    @Test fun corruptFileIsSetAsideAndTheAppStartsClean() {
        file.writeText("{esto no es json")
        val repo = StateRepository(file)
        assertEquals(ZenState(), repo.state.value)
        assertTrue(File(dir, "state.json.corrupt").exists())
        repo.update { it.copy(alarms = listOf(alarm)) }
        assertEquals(1, StateRepository(file).state.value.alarms.size)
    }

    @Test fun outOfRangeTimeCountsAsCorrupt() {
        file.writeText("""{"alarms":[{"id":"x","hour":99,"minute":0}],"anchors":[]}""")
        assertEquals(ZenState(), StateRepository(file).state.value)
        assertTrue(File(dir, "state.json.corrupt").exists())
    }

    @Test fun unknownAnchorTypesAreSkipped() {
        file.writeText("""{"alarms":[],"anchors":[{"type":"nfc","id":"n","label":"x","backupCode":"1"},{"type":"qr","id":"q","label":"Q","secret":"s","backupCode":"123456"}]}""")
        assertEquals(listOf("q"), StateRepository(file).state.value.anchors.map { it.id })
    }

    @Test fun invalidWeekdaysAreDropped() {
        file.writeText("""{"alarms":[{"id":"x","hour":6,"minute":0,"weekdays":[1,9,0,7],"enabled":true}],"anchors":[]}""")
        assertEquals(setOf(Weekday.MONDAY, Weekday.SUNDAY), StateRepository(file).state.value.alarms.single().weekdays)
    }

    @Test fun failedWriteDoesNotChangeInMemoryState() {
        // El "directorio padre" es en realidad un archivo: crear el temporal falla.
        val blocker = File(dir, "blocker").apply { writeText("x") }
        val repo = StateRepository(File(blocker, "state.json"))
        try {
            repo.update { it.copy(alarms = listOf(alarm)) }
            fail("debía lanzar")
        } catch (_: IOException) {
        }
        assertTrue(repo.state.value.alarms.isEmpty())
    }

    @Test fun settingsAndStatsRoundTrip() {
        val settings = ZenSettings(SoundChoice.Custom("alarm-1.audio", "Mi canción.mp3"), SoundChoice.Preset("coin"), 20)
        val stats = WakeStats(4, 9, 31, 20_500L, 75)
        StateRepository(file).update { it.copy(settings = settings, stats = stats) }
        val loaded = StateRepository(file).state.value
        assertEquals(settings, loaded.settings)
        assertEquals(stats, loaded.stats)
        assertEquals(0.20f, loaded.settings.startVolume, 1e-6f)
    }

    @Test fun oldFilesWithoutSettingsGetTheDefaults() {
        file.writeText("""{"version":1,"alarms":[],"anchors":[]}""")
        val state = StateRepository(file).state.value
        assertEquals(ZenSettings(), state.settings)
        assertEquals(WakeStats(), state.stats)
        assertEquals(15, state.settings.startVolumePercent)
    }

    @Test fun invalidSettingsFallBackSafely() {
        file.writeText("""{"alarms":[],"anchors":[],"settings":{"alarmSound":{"type":"custom"},"completionSound":{"type":"mystery"},"startVolumePercent":9000},"stats":{"currentStreak":-4,"bestStreak":2}}""")
        val state = StateRepository(file).state.value
        assertEquals(ZenSettings().alarmSound, state.settings.alarmSound)
        assertEquals(ZenSettings().completionSound, state.settings.completionSound)
        assertEquals(ZenSettings.MAX_START_VOLUME_PERCENT, state.settings.startVolumePercent)
        assertEquals(0, state.stats.currentStreak)
        assertEquals(2, state.stats.bestStreak)
    }

    @Test fun unknownPresetIdsFallBackToTheDefaultSound() {
        file.writeText("""{"alarms":[],"anchors":[],"settings":{"alarmSound":{"type":"preset","id":"dubstep"},"completionSound":{"type":"preset","id":"bowl"}}}""")
        val settings = StateRepository(file).state.value.settings
        assertEquals(SoundChoice.Preset("classic"), settings.alarmSound)
        assertEquals(SoundChoice.Preset("bowl"), settings.completionSound)
    }

    @Test fun startVolumeIsAlwaysWithinRange() {
        assertEquals(0.05f, ZenSettings(startVolumePercent = 0).startVolume, 1e-6f)
        assertEquals(0.5f, ZenSettings(startVolumePercent = 100).startVolume, 1e-6f)
    }

    @Test fun noOpUpdateDoesNotWrite() {
        val repo = StateRepository(file)
        repo.update { it }
        assertFalse(file.exists())
    }

    @Test fun noTemporaryFileLeftBehind() {
        StateRepository(file).update { it.copy(alarms = listOf(alarm)) }
        assertFalse(File(dir, "state.json.tmp").exists())
    }
}

class ModelTest {
    @Test fun qrPayloadAndMatching() {
        val a = QrAnchor("id1", "x", "secret", "123456")
        assertEquals("zen://anchor/id1/secret", a.payload)
        assertTrue(a.matches("zen://anchor/id1/secret"))
        assertFalse(a.matches("zen://anchor/id1/secreT"))
        assertFalse(a.matches("zen://anchor/id1/secret "))
        assertFalse(a.matches(""))
    }

    @Test fun backupCodeMatchingIsExact() {
        val a = PhotoAnchor("p", "x", "004200", emptyList())
        assertTrue(a.matchesBackupCode("004200"))
        assertFalse(a.matchesBackupCode("4200"))
        assertFalse(a.matchesBackupCode(""))
    }

    @Test fun secretsHaveTheExpectedShape() {
        val codes = (1..200).map { Secrets.backupCode() }
        assertTrue(codes.all { it.length == 6 && it.all(Char::isDigit) })
        assertTrue(codes.toSet().size > 150)
        val t = Secrets.token()
        assertTrue(t.length >= 21 && t.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(Secrets.token(), Secrets.token())
    }

    @Test fun anchorInUseLookup() {
        val state = ZenState(listOf(AlarmEntity("a", 6, 0, emptySet(), true, "q", null)), listOf(QrAnchor("q", "x", "s", "000000")))
        assertTrue(state.isAnchorInUse("q"))
        assertFalse(state.isAnchorInUse("z"))
        assertNotNull(state.anchor("q"))
        assertNull(state.anchor(null))
    }
}

class AlarmPlannerTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private fun millis(d: Int, h: Int, m: Int) =
        ZonedDateTime.of(LocalDateTime.of(2026, 3, d, h, m), zone).toInstant().toEpochMilli()

    private fun alarm(days: Set<Weekday> = emptySet(), enabled: Boolean = true, next: Long? = null) =
        AlarmEntity("a", 6, 30, days, enabled, "q", next)

    @Test fun enableSchedulesTheNextOccurrence() {
        val result = AlarmPlanner.enable(alarm(enabled = false), millis(2, 7, 0), zone)
        assertTrue(result.enabled)
        assertEquals(millis(3, 6, 30), result.nextFireAt)
    }

    @Test fun disableClearsTheSchedule() {
        val result = AlarmPlanner.disable(alarm(next = 5L))
        assertFalse(result.enabled)
        assertNull(result.nextFireAt)
    }

    @Test fun restoreKeepsAFutureOneShot() {
        val a = alarm(next = millis(3, 6, 30))
        assertEquals(a, AlarmPlanner.restore(a, millis(2, 22, 0), zone))
    }

    @Test fun restoreDisablesAMissedOneShot() {
        val result = AlarmPlanner.restore(alarm(next = millis(2, 6, 30)), millis(2, 9, 0), zone)
        assertFalse(result.enabled)
        assertNull(result.nextFireAt)
    }

    @Test fun restoreRecomputesRepeatingAlarms() {
        val a = alarm(days = setOf(Weekday.MONDAY, Weekday.TUESDAY), next = millis(2, 6, 30))
        val result = AlarmPlanner.restore(a, millis(2, 9, 0), zone)
        assertTrue(result.enabled)
        assertEquals(millis(3, 6, 30), result.nextFireAt)
    }

    @Test fun restoreKeepsAnAlarmThatJustExpiredSoItRingsNow() {
        val due = millis(2, 6, 30)
        val oneShot = alarm(next = due)
        assertEquals(oneShot, AlarmPlanner.restore(oneShot, due + 30_000, zone))
        val repeating = alarm(days = setOf(Weekday.MONDAY), next = due)
        assertEquals(repeating, AlarmPlanner.restore(repeating, due + AlarmPlanner.LATE_GRACE_MS, zone))
    }

    @Test fun restoreDropsAnAlarmOlderThanTheGrace() {
        val due = millis(2, 6, 30)
        assertFalse(AlarmPlanner.restore(alarm(next = due), due + AlarmPlanner.LATE_GRACE_MS + 1, zone).enabled)
    }

    @Test fun restoreLeavesDisabledAlarmsDisabled() {
        assertEquals(alarm(enabled = false, next = null), AlarmPlanner.restore(alarm(enabled = false, next = 99L), 0L, zone))
    }

    @Test fun shouldRingRejectsStaleOrMissingAlarms() {
        val at = millis(3, 6, 30)
        assertFalse(AlarmPlanner.shouldRing(null, at))
        assertFalse(AlarmPlanner.shouldRing(alarm(enabled = false, next = at), at))
        assertFalse(AlarmPlanner.shouldRing(alarm(next = null), at))
        assertFalse(AlarmPlanner.shouldRing(alarm(next = at), at - 5 * 60_000))
        assertTrue(AlarmPlanner.shouldRing(alarm(next = at), at))
        assertTrue(AlarmPlanner.shouldRing(alarm(next = at), at - 30_000))
        assertTrue(AlarmPlanner.shouldRing(alarm(next = at), at + 3_600_000))
    }

    @Test fun afterFiringOneShotTurnsOff() {
        val at = millis(3, 6, 30)
        val result = AlarmPlanner.afterFiring(alarm(next = at), at + 1_000, zone)
        assertFalse(result.enabled)
    }

    @Test fun afterFiringRepeatingMovesToTheNextDay() {
        val at = millis(3, 6, 30) // martes
        val a = alarm(days = setOf(Weekday.TUESDAY, Weekday.WEDNESDAY), next = at)
        assertEquals(millis(4, 6, 30), AlarmPlanner.afterFiring(a, at + 1_000, zone).nextFireAt)
    }

    @Test fun afterFiringEarlyDoesNotRepeatTheSameOccurrence() {
        // Disparo recibido 20 s antes (tolerancia): no debe recalcular hacia la misma ocurrencia.
        val at = millis(3, 6, 30)
        val a = alarm(days = setOf(Weekday.TUESDAY), next = at)
        val result = AlarmPlanner.afterFiring(a, at - 20_000, zone)
        assertEquals(millis(10, 6, 30), result.nextFireAt)
    }
}

class ScanDebouncerTest {
    @Test fun firstReadingAlwaysNotifies() = assertTrue(ScanDebouncer().shouldNotify("a", 0))

    @Test fun sameCodeIsSuppressedUntilTheIntervalPasses() {
        val d = ScanDebouncer(2_000)
        assertTrue(d.shouldNotify("a", 1_000))
        assertFalse(d.shouldNotify("a", 1_500))
        assertFalse(d.shouldNotify("a", 2_999))
        assertTrue(d.shouldNotify("a", 3_000))
    }

    @Test fun aDifferentCodeNotifiesImmediately() {
        val d = ScanDebouncer(2_000)
        d.shouldNotify("a", 1_000)
        assertTrue(d.shouldNotify("b", 1_100))
        assertTrue(d.shouldNotify("a", 1_200))
    }

    @Test fun clockGoingBackwardsDoesNotBlockReadings() {
        val d = ScanDebouncer(2_000)
        d.shouldNotify("a", 10_000)
        assertTrue(d.shouldNotify("a", 5_000))
    }

    @Test fun resetForgetsTheLastCode() {
        val d = ScanDebouncer(2_000)
        d.shouldNotify("a", 1_000)
        d.reset()
        assertTrue(d.shouldNotify("a", 1_001))
    }
}

class LocaleIndependenceTest {
    @Test fun backupCodeAndCountdownStayAsciiInAnyLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "hi-IN", "es-ES")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                val code = Secrets.backupCode()
                assertTrue("$tag: $code", code.length == 6 && code.all { it in '0'..'9' })
                assertEquals("1:05", com.hermesmarcano.zen.core.BackupCodePolicy.countdownText(64.2))
                assertEquals(code, com.hermesmarcano.zen.core.BackupCodePolicy.sanitize(code))
            }
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
