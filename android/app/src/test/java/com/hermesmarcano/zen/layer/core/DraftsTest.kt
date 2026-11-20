package com.hermesmarcano.zen.layer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FakeSender(var result: ReplySendResult = ReplySendResult.SENT) : ReplySender {
    val known = mutableMapOf("wa|marta" to ReplyTarget("com.whatsapp", "Marta"))
    val sent = ArrayList<Pair<String, String>>()
    override fun target(conversationKey: String) = known[conversationKey]
    override fun send(conversationKey: String, text: String): ReplySendResult {
        sent += conversationKey to text
        return result
    }
}

internal class RecordingNotifier : Notifier {
    val notices = ArrayList<Pair<String, String>>()
    val drafts = ArrayList<Draft>()
    val nudges = ArrayList<LimitDecision.Nudge>()
    val blocked = ArrayList<BlockEntry>()
    val attention = ArrayList<Pair<String, String>>()
    override fun notice(title: String, text: String) { notices += title to text }
    override fun draftReady(draft: Draft) { drafts += draft }
    override fun nudge(nudge: LimitDecision.Nudge) { nudges += nudge }
    override fun blocked(entry: BlockEntry) { blocked += entry }
    override fun needsAttention(title: String, text: String) { attention += title to text }
}

private const val OWN_PKG = "com.hermesmarcano.zen"

class DraftControllerTest {
    private var now = 1_000_000L
    private var ids = 0
    private val state = MemoryState(LayerState(capture = CaptureSettings(screenText = true)))
    private val timeline = MemoryTimeline()
    private val sender = FakeSender()
    private val pipeline = LayerPipeline(state, timeline, MemoryOutbox(), OWN_PKG, { "{}" }, { now }, { "ev${++ids}" })
    private val controller = DraftController(state, sender, pipeline, { now }, { "d${++ids}" })

    private fun propose(text: String = "claro, a las 9") = controller.propose("wa|marta", text)!!

    @Test fun proposingCreatesAPendingDraftWithTheContactAndNeverSends() {
        val draft = propose()
        assertEquals("Marta", draft.contact)
        assertEquals("com.whatsapp", draft.pkg)
        assertEquals(DraftStatus.PENDING, draft.status)
        assertEquals(now + DraftBoard.TTL_MS, draft.expiresAtMs)
        assertTrue("proponer no es enviar", sender.sent.isEmpty())
    }

    @Test fun anUnknownConversationCannotGetADraft() {
        assertNull(controller.propose("wa|desconocido", "hola"))
        assertTrue(state.current.drafts.isEmpty())
    }

    @Test fun approvingSendsExactlyOnceAndLogsIt() {
        val draft = propose()
        assertEquals(DraftController.Result.SENT, controller.approve(draft.id))
        assertEquals(listOf("wa|marta" to "claro, a las 9"), sender.sent)
        assertEquals(DraftStatus.SENT, state.current.drafts.single().status)
        assertTrue(timeline.events.single().title.contains("Marta"))
        // Un segundo toque no reenvía.
        assertEquals(DraftController.Result.NOT_FOUND, controller.approve(draft.id))
        assertEquals(1, sender.sent.size)
    }

    @Test fun anEditedTextIsWhatGetsSent() {
        val draft = propose()
        controller.approve(draft.id, "  mejor a las 10  ")
        assertEquals("mejor a las 10", sender.sent.single().second)
    }

    @Test fun aBlankEditFallsBackToTheOriginalText() {
        val draft = propose()
        controller.approve(draft.id, "   ")
        assertEquals("claro, a las 9", sender.sent.single().second)
    }

    @Test fun anExpiredDraftIsNotSent() {
        val draft = propose()
        now += DraftBoard.TTL_MS
        assertEquals(DraftController.Result.EXPIRED, controller.approve(draft.id))
        assertTrue(sender.sent.isEmpty())
        assertEquals(DraftStatus.EXPIRED, state.current.drafts.single().status)
    }

    @Test fun aVanishedConversationMarksTheDraftFailed() {
        val draft = propose()
        sender.result = ReplySendResult.GONE
        assertEquals(DraftController.Result.GONE, controller.approve(draft.id))
        assertEquals(DraftStatus.FAILED, state.current.drafts.single().status)
        assertTrue("no se anota como enviada", timeline.events.isEmpty())
    }

    @Test fun aFailedSendMarksTheDraftFailed() {
        val draft = propose()
        sender.result = ReplySendResult.FAILED
        assertEquals(DraftController.Result.FAILED, controller.approve(draft.id))
        assertEquals(DraftStatus.FAILED, state.current.drafts.single().status)
    }

    @Test fun ifTheClaimCannotBeSavedNothingIsSent() {
        val draft = propose()
        state.failWrites = true
        assertEquals(DraftController.Result.FAILED, controller.approve(draft.id))
        assertTrue("sin poder reclamarlo, no se envía", sender.sent.isEmpty())
    }

    @Test fun discardingPreventsSending() {
        val draft = propose()
        controller.discard(draft.id)
        assertEquals(DraftController.Result.NOT_FOUND, controller.approve(draft.id))
        assertTrue(sender.sent.isEmpty())
    }

    @Test fun approvingAnUnknownIdDoesNothing() = assertEquals(DraftController.Result.NOT_FOUND, controller.approve("no-existe"))

    @Test fun aNewProposalForTheSameConversationReplacesTheOldOne() {
        val first = propose("uno")
        val second = propose("dos")
        assertEquals(DraftController.Result.NOT_FOUND, controller.approve(first.id))
        assertEquals(DraftController.Result.SENT, controller.approve(second.id))
        assertEquals("dos", sender.sent.single().second)
    }

    @Test fun proposingWhenTheDiskFailsGivesNoDraft() {
        state.failWrites = true
        assertNull(controller.propose("wa|marta", "hola"))
    }
}

class CommandApplierTest {
    private var now = 10 * HOUR
    // El cerebro solo puede bloquear apps que el usuario ya puso bajo una regla.
    private val state = MemoryState(LayerState(capture = CaptureSettings(), limits = listOf(LimitRule("com.zhiliaoapp.musically", "TikTok", 0L))))
    private val timeline = MemoryTimeline()
    private val sender = FakeSender()
    private val notifier = RecordingNotifier()
    private val pipeline = LayerPipeline(state, timeline, MemoryOutbox(), OWN_PKG, { "{}" }, { now })
    private val blocks = BlockActions(state, UtcDays, OWN_PKG)
    private val drafts = DraftController(state, sender, pipeline, { now })
    private val applier = CommandApplier(state, blocks, drafts, notifier, OWN_PKG, { now })

    @Test fun aDraftReplyBecomesAPendingDraftAndNeverASend() {
        assertEquals(CommandApplier.Outcome.APPLIED, applier.apply(BrainCommand.DraftReply("c1", "wa|marta", "ok")))
        assertEquals(1, notifier.drafts.size)
        assertEquals(DraftStatus.PENDING, state.current.drafts.single().status)
        assertTrue("el cerebro no puede enviar", sender.sent.isEmpty())
    }

    @Test fun aDraftForAGoneConversationTellsTheUser() {
        applier.apply(BrainCommand.DraftReply("c1", "wa|nadie", "ok"))
        assertTrue(notifier.drafts.isEmpty())
        assertEquals("Respuesta no disponible", notifier.notices.single().first)
    }

    @Test fun blockAppliesAndNotifies() {
        assertEquals(CommandApplier.Outcome.APPLIED, applier.apply(BrainCommand.Block("c2", "com.zhiliaoapp.musically", 60)))
        val entry = state.current.blocks.single()
        assertEquals(now + HOUR, entry.untilMs)
        assertEquals(BlockReason.BRAIN, entry.reason)
        assertEquals(listOf(entry), notifier.blocked)
    }

    @Test fun blockingAProtectedAppIsRejected() {
        assertEquals(CommandApplier.Outcome.REJECTED, applier.apply(BrainCommand.Block("c3", "com.android.settings", 60)))
        assertTrue(state.current.blocks.isEmpty())
        assertTrue(notifier.blocked.isEmpty())
    }

    @Test fun anInvalidCommandIsRememberedSoItDoesNotComeBack() {
        assertEquals(CommandApplier.Outcome.REJECTED, applier.apply(BrainCommand.Block("c4", "a.b", 0)))
        assertEquals(CommandApplier.Outcome.DUPLICATE, applier.apply(BrainCommand.Block("c4", "a.b", 0)))
    }

    @Test fun aCommandRunsAtMostOnce() {
        assertEquals(CommandApplier.Outcome.APPLIED, applier.apply(BrainCommand.Notify("c5", "Hola", "texto")))
        assertEquals(CommandApplier.Outcome.DUPLICATE, applier.apply(BrainCommand.Notify("c5", "Hola", "texto")))
        assertEquals(1, notifier.notices.size)
    }

    @Test fun aBlankTitleFallsBackToZen() {
        applier.apply(BrainCommand.Notify("c6", "", "texto"))
        assertEquals("ZEN", notifier.notices.single().first)
    }

    @Test fun pauseExtendsButNeverShortensAnExistingPause() {
        applier.apply(BrainCommand.Pause("p1", 60))
        assertEquals(now + HOUR, state.current.capture.pausedUntilMs)
        applier.apply(BrainCommand.Pause("p2", 10))
        assertEquals("una pausa más corta no acorta la larga", now + HOUR, state.current.capture.pausedUntilMs)
        applier.apply(BrainCommand.Pause("p3", 120))
        assertEquals(now + 2 * HOUR, state.current.capture.pausedUntilMs)
    }

    @Test fun aDiskFailureRejectsTheCommandWithoutExecutingIt() {
        state.failWrites = true
        assertEquals(CommandApplier.Outcome.REJECTED, applier.apply(BrainCommand.Notify("c7", "Hola", "texto")))
        assertTrue("sin poder anotarla, no se ejecuta", notifier.notices.isEmpty())
    }

    @Test fun aDiskFailureInsideABlockDoesNotThrow() {
        // Anotar la orden funciona, pero la escritura del bloqueo falla: no debe lanzar hacia quien sincroniza.
        val flaky = object : LayerStateStore {
            var writes = 0
            override val current get() = state.current
            override fun update(transform: (LayerState) -> LayerState): LayerState {
                if (++writes > 1) throw java.io.IOException("disco")
                return state.update(transform)
            }
        }
        val applier2 = CommandApplier(flaky, BlockActions(flaky, UtcDays, OWN_PKG), DraftController(flaky, sender, pipeline, { now }), notifier, OWN_PKG, { now })
        assertEquals(CommandApplier.Outcome.REJECTED, applier2.apply(BrainCommand.Block("c8", "com.zhiliaoapp.musically", 30)))
        assertFalse(notifier.blocked.isNotEmpty())
        assertNotNull(flaky.current)
    }

    @Test fun remembersOnlyTheMostRecentIds() {
        repeat(CommandPolicy.REMEMBER_IDS + 5) { applier.apply(BrainCommand.Notify("n$it", "t", "x")) }
        assertEquals(CommandPolicy.REMEMBER_IDS, state.current.seenCommandIds.size)
    }
}
