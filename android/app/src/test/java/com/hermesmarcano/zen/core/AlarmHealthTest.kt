package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.core.AlarmHealth.Event
import com.hermesmarcano.zen.core.AlarmHealth.Kind
import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.data.AlarmLog
import com.hermesmarcano.zen.data.GameEngine
import com.hermesmarcano.zen.data.Progress
import com.hermesmarcano.zen.data.StateJson
import com.hermesmarcano.zen.data.ZenSettings
import com.hermesmarcano.zen.data.ZenState
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private val T0 = LocalDate.of(2026, 11, 19).atTime(5, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

class AlarmHealthTest {
    private val zone = ZoneOffset.UTC

    @Test fun latenessTextBuckets() {
        assertEquals("a tiempo", AlarmHealth.latenessText(0))
        assertEquals("a tiempo", AlarmHealth.latenessText(-5_000))
        assertEquals("a tiempo", AlarmHealth.latenessText(AlarmHealth.ON_TIME_MS))
        assertEquals("100 s tarde", AlarmHealth.latenessText(100_000))
        assertEquals("12 min tarde", AlarmHealth.latenessText(12 * MIN))
        assertEquals("1 h 24 min tarde", AlarmHealth.latenessText(84 * MIN))
    }

    @Test fun onlyDeliveredEventsHaveLateness() {
        assertEquals(84 * MIN, AlarmHealth.latenessMs(Event(T0 + 84 * MIN, Kind.FIRED, "a", T0)))
        assertEquals(1_000L, AlarmHealth.latenessMs(Event(T0 + 1_000, Kind.TEST_FIRED, "t", T0)))
        assertNull(AlarmHealth.latenessMs(Event(T0, Kind.SCHEDULED, "a", T0)))
        assertNull(AlarmHealth.latenessMs(Event(T0, Kind.FIRED, "a", null)))
        assertTrue(AlarmHealth.isLate(Event(T0 + 5 * MIN, Kind.FIRED, "a", T0)))
        assertFalse(AlarmHealth.isLate(Event(T0 + 30_000, Kind.FIRED, "a", T0)))
        assertFalse("llegar antes tampoco es tarde", AlarmHealth.isLate(Event(T0 - 10_000, Kind.FIRED, "a", T0)))
    }

    @Test fun describeReadsLikeASentence() {
        assertEquals("19 nov 06:54:12 · Recibida (era para las 05:30) · 1 h 24 min tarde",
            AlarmHealth.describe(Event(T0 + 84 * MIN + 12_000, Kind.FIRED, "a", T0), zone))
        assertEquals("19 nov 05:00:00 · Programada para las 05:30", AlarmHealth.describe(Event(T0 - 30 * MIN, Kind.SCHEDULED, "a", T0), zone))
        assertTrue(AlarmHealth.describe(Event(T0 + 84 * MIN, Kind.MISSED, "a", T0), zone).contains("No sonó la de las 05:30: el sistema no entregó el aviso"))
        assertTrue(AlarmHealth.describe(Event(T0, Kind.RESTORED, note = "cambio de hora"), zone).endsWith("Reprogramada · cambio de hora"))
        assertTrue(AlarmHealth.describe(Event(T0, Kind.DISCARDED, "a", T0, "estaba desactivada"), zone).contains("estaba desactivada"))
    }

    private fun alarm(next: Long?, enabled: Boolean = true) = AlarmEntity("a", 5, 30, emptySet(), enabled, "x", next)

    @Test fun overdueNeedsEnabledAndMoreThanTheGraceWindow() {
        assertTrue(AlarmHealth.isOverdue(alarm(T0), T0 + 6 * MIN))
        assertFalse("dentro del margen de 5 min aún puede llegar", AlarmHealth.isOverdue(alarm(T0), T0 + 4 * MIN))
        assertFalse(AlarmHealth.isOverdue(alarm(T0 + HOUR), T0))
        assertFalse(AlarmHealth.isOverdue(alarm(T0, enabled = false), T0 + HOUR))
        assertFalse(AlarmHealth.isOverdue(alarm(null), T0 + HOUR))
    }

    @Test fun problemsAreMissedOrLateAndRecentAndNotAcknowledged() {
        val missed = Event(T0 + 84 * MIN, Kind.MISSED, "a", T0)
        val late = Event(T0 + 3 * HOUR, Kind.FIRED, "a", T0 + 2 * HOUR)
        val onTime = Event(T0 + 5 * HOUR, Kind.FIRED, "a", T0 + 5 * HOUR - 5_000)
        val test = Event(T0 + 6 * HOUR, Kind.MISSED, AlarmHealth.SELF_TEST_ID, T0)
        val events = listOf(missed, late, onTime, test, Event(T0, Kind.SCHEDULED, "a", T0))
        val now = T0 + 7 * HOUR
        assertEquals(listOf(missed, late), AlarmHealth.problems(events, now, ackAt = 0))
        assertEquals("tras 'Entendido' solo quedan los posteriores", listOf(late), AlarmHealth.problems(events, now, ackAt = missed.at))
        assertTrue("pasadas 48 h dejan de molestar", AlarmHealth.problems(events, now + 49 * HOUR, 0).isEmpty())
    }

    @Test fun selfTestLifecycle() {
        val scheduled = Event(T0, Kind.TEST_SCHEDULED, AlarmHealth.SELF_TEST_ID, T0 + MIN)
        assertEquals(T0 + MIN, AlarmHealth.pendingTest(listOf(scheduled), T0 + 10_000))
        assertFalse(AlarmHealth.testFailed(listOf(scheduled), T0 + 10_000))
        // Pasada su hora con margen y sin llegar: ha fallado y ya no está pendiente.
        assertNull(AlarmHealth.pendingTest(listOf(scheduled), T0 + MIN + 2 * MIN))
        assertTrue(AlarmHealth.testFailed(listOf(scheduled), T0 + MIN + 2 * MIN))
        // Llegó: ni pendiente ni fallida, y es el último resultado.
        val fired = Event(T0 + MIN + 2_000, Kind.TEST_FIRED, AlarmHealth.SELF_TEST_ID, T0 + MIN)
        val done = listOf(scheduled, fired)
        assertNull(AlarmHealth.pendingTest(done, T0 + MIN + 5_000))
        assertFalse(AlarmHealth.testFailed(done, T0 + HOUR))
        assertEquals(fired, AlarmHealth.lastTestResult(done))
        // Una prueba nueva tras un resultado viejo vuelve a estar pendiente.
        val again = done + Event(T0 + HOUR, Kind.TEST_SCHEDULED, AlarmHealth.SELF_TEST_ID, T0 + HOUR + MIN)
        assertEquals(T0 + HOUR + MIN, AlarmHealth.pendingTest(again, T0 + HOUR + 1_000))
        assertFalse(AlarmHealth.testFailed(emptyList(), T0))
    }

    @Test fun aCancelledTestIsNeitherPendingNorFailed() {
        val scheduled = Event(T0, Kind.TEST_SCHEDULED, AlarmHealth.SELF_TEST_ID, T0 + MIN)
        val cancelled = Event(T0 + 5_000, Kind.TEST_CANCELLED, AlarmHealth.SELF_TEST_ID)
        assertNull(AlarmHealth.pendingTest(listOf(scheduled, cancelled), T0 + 6_000))
        assertFalse(AlarmHealth.testFailed(listOf(scheduled, cancelled), T0 + HOUR))
        assertTrue("una prueba nueva tras cancelar vuelve a poder fallar", AlarmHealth.testFailed(listOf(scheduled, cancelled, Event(T0 + 10_000, Kind.TEST_SCHEDULED, AlarmHealth.SELF_TEST_ID, T0 + 70_000)), T0 + HOUR))
        assertEquals("Prueba cancelada", AlarmHealth.describe(cancelled, ZoneOffset.UTC).substringAfter("· "))
    }

    @Test fun theVerdictSaysExactlyWhatWasChecked() {
        val ok = AlarmHealth.selfTestVerdict(2_000)
        assertTrue(ok.passed)
        assertTrue(ok.text.contains("No comprueba el sonido"))
        val late = AlarmHealth.selfTestVerdict(5 * MIN)
        assertFalse(late.passed)
        assertTrue(late.text.contains("5 min tarde"))
        assertFalse(AlarmHealth.selfTestVerdict(null).passed)
        assertFalse("justo en el límite sigue siendo a tiempo", AlarmHealth.selfTestVerdict(AlarmHealth.ON_TIME_MS).let { !it.passed })
    }

    @Test fun anAlarmTestGivesTheAchievementOnceAndNoXp() {
        val first = GameEngine.recordAlarmTest(ZenState(), LocalDate.of(2026, 11, 19))
        assertEquals(0, first.events.xpGained)
        assertEquals(listOf(Achievement.ALARM_CHECKED), first.events.unlocked)
        assertEquals(1, first.state.progress.alarmTestsPassed)
        assertEquals(0, first.state.progress.xp)
        val second = GameEngine.recordAlarmTest(first.state, LocalDate.of(2026, 11, 20))
        assertTrue(second.events.unlocked.isEmpty())
        assertEquals(2, second.state.progress.alarmTestsPassed)
        assertEquals(listOf("Logro: Alarma a prueba"), GameMessages.describe(first.events))
    }

    @Test fun newStateFieldsSurviveTheJson() {
        val state = ZenState(settings = ZenSettings(diagnosticsAckAt = 12345L), progress = Progress(alarmTestsPassed = 3))
        val back = StateJson.decode(StateJson.encode(state))
        assertEquals(12345L, back.settings.diagnosticsAckAt)
        assertEquals(3, back.progress.alarmTestsPassed)
        assertEquals(0L, StateJson.decode(StateJson.encode(ZenState())).settings.diagnosticsAckAt)
    }
}

class AlarmLogTest {
    private val dir: File = Files.createTempDirectory("zen-alarmlog").toFile()
    private val file = File(dir, "alarm_log.json")

    @Test fun startsEmptyAndPersists() {
        assertTrue(AlarmLog(file).events.value.isEmpty())
        val log = AlarmLog(file)
        log.record(Event(T0, Kind.FIRED, "a", T0 - 5_000, "nota"))
        val back = AlarmLog(file).events.value
        assertEquals(listOf(Event(T0, Kind.FIRED, "a", T0 - 5_000, "nota")), back)
    }

    @Test fun keepsOnlyTheMostRecentEvents() {
        val log = AlarmLog(file)
        repeat(AlarmHealth.MAX_EVENTS + 25) { log.record(Event(T0 + it, Kind.FIRED, "a", T0)) }
        val events = AlarmLog(file).events.value
        assertEquals(AlarmHealth.MAX_EVENTS, events.size)
        assertEquals(T0 + AlarmHealth.MAX_EVENTS + 24, events.last().at)
    }

    @Test fun reschedulingTheSameTimeDoesNotFillTheLog() {
        val log = AlarmLog(file)
        repeat(5) { log.record(Event(T0 + it, Kind.SCHEDULED, "a", T0 + HOUR)) }
        assertEquals(1, log.events.value.size)
        log.record(Event(T0 + 9, Kind.SCHEDULED, "a", T0 + 2 * HOUR))
        log.record(Event(T0 + 10, Kind.SCHEDULED, "b", T0 + 2 * HOUR))
        assertEquals(3, log.events.value.size)
    }

    @Test fun repeatedTimeChangesDoNotPushOutTheEventsThatExplainAFailure() {
        val log = AlarmLog(file)
        log.record(Event(T0, Kind.MISSED, "a", T0 - HOUR))
        repeat(200) { log.record(Event(T0 + MIN + it * 1_000L, Kind.RESTORED, note = "cambio de hora")) }
        assertEquals(1, log.events.value.count { it.kind == Kind.RESTORED })
        assertTrue(log.events.value.any { it.kind == Kind.MISSED })
        log.record(Event(T0 + HOUR, Kind.RESTORED, note = "cambio de hora"))
        assertEquals("pasado un rato sí se vuelve a anotar", 2, log.events.value.count { it.kind == Kind.RESTORED })
        log.record(Event(T0 + HOUR + 1, Kind.RESTORED, note = "reinicio del teléfono"))
        assertEquals("otro motivo se anota siempre", 3, log.events.value.count { it.kind == Kind.RESTORED })
    }

    @Test fun clearEmptiesTheLogAndTheFile() {
        val log = AlarmLog(file)
        log.record(Event(T0, Kind.FIRED, "a", T0))
        log.clear()
        assertTrue(log.events.value.isEmpty())
        assertTrue(AlarmLog(file).events.value.isEmpty())
    }

    @Test fun aBrokenFileIsSetAsideAndNeverBreaksAnAlarm() {
        file.writeText("{esto no es json")
        val log = AlarmLog(file)
        assertTrue(log.events.value.isEmpty())
        assertTrue(File(dir, "alarm_log.json.corrupt").exists())
        log.record(Event(T0, Kind.FIRED, "a", T0))
        assertEquals(1, log.events.value.size)
    }

    @Test fun anUnwritableLogStillKeepsEventsInMemory() {
        File(dir, "alarm_log.json.tmp").mkdirs() // impide escribir
        val log = AlarmLog(file)
        log.record(Event(T0, Kind.FIRED, "a", T0))
        assertEquals(1, log.events.value.size)
    }

    @Test fun unknownKindsAreIgnoredWhenLoading() {
        file.writeText("""[{"at":1,"kind":"desconocido"},{"at":2,"kind":"fired","alarmId":"a","expectedAt":1,"note":"x"}]""")
        assertEquals(listOf(Event(2, Kind.FIRED, "a", 1, "x")), AlarmLog(file).events.value)
    }
}
