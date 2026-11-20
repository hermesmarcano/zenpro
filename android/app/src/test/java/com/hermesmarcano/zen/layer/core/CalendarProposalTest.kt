package com.hermesmarcano.zen.layer.core

import com.hermesmarcano.zen.layer.link.EventJson
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarProposalTest {
    private var now = 10 * HOUR
    private val own = "own"
    private val state = MemoryState(LayerState())
    private val notifier = RecordingNotifier()
    private val pipeline = LayerPipeline(state, MemoryTimeline(), MemoryOutbox(), own, { "{}" }, { now })
    private val applier = CommandApplier(state, BlockActions(state, UtcDays, own), DraftController(state, FakeSender(), pipeline, { now }), notifier, own, { now })

    private fun event(id: String = "c1", title: String = "Cena con Marta", start: Long = now + DAY, minutes: Int = 90, from: String = "Marta") =
        BrainCommand.CalendarEvent(id, title, start, start + minutes * MIN, from)

    private fun verdict(c: BrainCommand) = CommandPolicy.check(c, own, emptySet())

    @Test fun aWellFormedProposalIsAccepted() = assertEquals(CommandPolicy.Verdict.ACCEPT, verdict(event()))

    @Test fun malformedProposalsAreInvalid() {
        val bad = listOf(
            event(title = " "), event(title = "x".repeat(CommandPolicy.MAX_EVENT_TITLE_CHARS + 1)), event(from = "x".repeat(61)),
            event(start = 0), event(minutes = 0), event(minutes = -5), event(minutes = 13 * 60), event().copy(endMs = event().startMs),
        )
        for (command in bad) assertEquals(command.toString(), CommandPolicy.Verdict.INVALID, verdict(command))
        assertEquals(CommandPolicy.Verdict.LINK, verdict(event(title = "Mira http://malo.com")))
        assertEquals("el remitente puede llevar puntos (Dr.Pérez)", CommandPolicy.Verdict.ACCEPT, verdict(event(from = "Dr.Pérez")))
    }

    @Test fun anAcceptedProposalBecomesANotificationAndNeverAnEventInTheCalendar() {
        assertEquals(CommandApplier.Outcome.APPLIED, applier.apply(event()))
        assertEquals(listOf("Cena con Marta"), notifier.proposals.map { it.title })
        assertEquals(CommandApplier.Outcome.DUPLICATE, applier.apply(event()))
    }

    @Test fun datesOutsideTheWindowAreRejected() {
        assertEquals(CommandApplier.Outcome.REJECTED, applier.apply(event("old", start = now - 2 * HOUR)))
        assertEquals(CommandApplier.Outcome.REJECTED, applier.apply(event("far", start = now + 401 * DAY)))
        assertEquals("hace menos de una hora aún vale (acaba de empezar)", CommandApplier.Outcome.APPLIED, applier.apply(event("recent", start = now - 30 * MIN)))
        assertEquals(1, notifier.proposals.size)
    }

    @Test fun aFloodOfProposalsIsCutAtTenPerHour() {
        val outcomes = (1..13).map { applier.apply(event("e$it", title = "Cita $it")) }
        assertEquals(10, outcomes.count { it == CommandApplier.Outcome.APPLIED })
        now += HOUR + 1
        assertEquals(CommandApplier.Outcome.APPLIED, applier.apply(event("later", start = now + DAY)))
    }

    @Test fun theWireFormatFromTheAdapterIsReadByTheApp() {
        val body = """{"cursor":"c1.3","commands":[{"id":"x","type":"calendar_event","title":"Cena con Marta","start":1763500000000,"end":1763505400000,"from":"Marta"}]}"""
        val (commands, cursor) = EventJson.parseCommands(body)
        assertEquals("c1.3", cursor)
        assertEquals(listOf(BrainCommand.CalendarEvent("x", "Cena con Marta", 1763500000000L, 1763505400000L, "Marta")), commands)
        // Un campo que falta no tumba nada: queda vacío y la política lo rechaza.
        val (broken, _) = EventJson.parseCommands("""{"commands":[{"id":"y","type":"calendar_event","title":"sin fechas"}]}""")
        assertEquals(CommandPolicy.Verdict.INVALID, verdict(broken.single()))
    }
}
