package com.hermesmarcano.zen.layer.link

import com.hermesmarcano.zen.layer.core.BlockActions
import com.hermesmarcano.zen.layer.core.BlockEntry
import com.hermesmarcano.zen.layer.core.BrainCommand
import com.hermesmarcano.zen.layer.core.CommandApplier
import com.hermesmarcano.zen.layer.core.Draft
import com.hermesmarcano.zen.layer.core.DraftController
import com.hermesmarcano.zen.layer.core.LayerPipeline
import com.hermesmarcano.zen.layer.core.LayerState
import com.hermesmarcano.zen.layer.core.LimitDecision
import com.hermesmarcano.zen.layer.core.MemoryOutbox
import com.hermesmarcano.zen.layer.core.MemoryState
import com.hermesmarcano.zen.layer.core.MemoryTimeline
import com.hermesmarcano.zen.layer.core.Notifier
import com.hermesmarcano.zen.layer.core.OutboxItem
import com.hermesmarcano.zen.layer.core.OutboxPolicy
import com.hermesmarcano.zen.layer.core.PcmSegment
import com.hermesmarcano.zen.layer.core.ReplySendResult
import com.hermesmarcano.zen.layer.core.ReplySender
import com.hermesmarcano.zen.layer.core.ReplyTarget
import com.hermesmarcano.zen.layer.core.UtcDays
import com.hermesmarcano.zen.layer.data.AudioFiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val HOUR = 3_600_000L

private class FakeApi : BrainApi {
    val eventResults = ArrayDeque<ApiResult>()
    var defaultEvent: ApiResult = ApiResult.Ok
    val eventCalls = ArrayList<List<OutboxItem>>()
    val audioResults = ArrayDeque<ApiResult>()
    var defaultAudio: ApiResult = ApiResult.Ok
    val audioCalls = ArrayList<Pair<OutboxItem, File>>()
    var commands = CommandsResult(ApiResult.Ok)
    val cursors = ArrayList<String>()
    var onPostEvents: () -> Unit = {}

    override fun postEvents(cfg: BrainConfig, events: List<OutboxItem>): ApiResult {
        eventCalls += events
        onPostEvents()
        return eventResults.removeFirstOrNull() ?: defaultEvent
    }

    override fun postAudio(cfg: BrainConfig, item: OutboxItem, file: File): ApiResult {
        audioCalls += item to file
        return audioResults.removeFirstOrNull() ?: defaultAudio
    }

    override fun fetchCommands(cfg: BrainConfig, cursor: String): CommandsResult {
        cursors += cursor
        return commands
    }
}

private class NullSender : ReplySender {
    override fun target(conversationKey: String): ReplyTarget? = null
    override fun send(conversationKey: String, text: String) = ReplySendResult.FAILED
}

private class QuietNotifier : Notifier {
    val notices = ArrayList<String>()
    override fun notice(title: String, text: String) { notices += title }
    override fun draftReady(draft: Draft) {}
    override fun nudge(nudge: LimitDecision.Nudge) {}
    override fun blocked(entry: BlockEntry) {}
    override fun needsAttention(title: String, text: String) {}
}

class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000L
    private val api = FakeApi()
    private val outbox = MemoryOutbox()
    private val notifier = QuietNotifier()
    private lateinit var state: MemoryState
    private lateinit var audio: AudioFiles
    private lateinit var engine: SyncEngine

    private fun build(initial: LayerState = LayerState(brainUrl = "https://brain.example.com", brainToken = "tok", deviceId = "dev-1")) {
        state = MemoryState(initial)
        audio = AudioFiles(File(tmp.root, "audio"))
        val pipeline = LayerPipeline(state, MemoryTimeline(), outbox, "own", { "{}" }, { now })
        val applier = CommandApplier(state, BlockActions(state, UtcDays, "own"), DraftController(state, NullSender(), pipeline, { now }), notifier, "own", { now })
        engine = SyncEngine(state, outbox, audio, api, applier, { now }, { 0.5 })
    }

    @Before fun setUp() = build()

    private fun event(id: String, at: Long = now) = OutboxItem(id, OutboxItem.Kind.EVENT, """{"id":"$id"}""", at)

    private fun segment() = PcmSegment(ShortArray(1_600) { 5 }, 0, 16_000)

    @Test fun notConfiguredDoesNothing() {
        build(LayerState())
        outbox.enqueue(event("a"))
        assertTrue(engine.run().skipped)
        assertTrue(api.eventCalls.isEmpty())
        assertEquals(1, outbox.count())
    }

    @Test fun aPublicCleartextAddressIsSkipped() {
        build(LayerState(brainUrl = "http://example.com", brainToken = "tok"))
        assertTrue(engine.run().skipped)
    }

    @Test fun sendsPendingEventsAndMarksTheSync() {
        outbox.enqueue(event("a")); outbox.enqueue(event("b"))
        val report = engine.run()
        assertEquals(2, report.eventsSent)
        assertNull(report.error)
        assertEquals(0, outbox.count())
        assertEquals(now, state.current.lastSyncOkAtMs)
        assertEquals("", state.current.lastSyncError)
    }

    @Test fun anEmptyQueueStillCountsAsAReachableServerIfCommandsAnswer() {
        assertEquals(0, engine.run().eventsSent)
        assertEquals(now, state.current.lastSyncOkAtMs)
    }

    @Test fun largeBacklogsGoOutInBatchesOfAHundred() {
        for (i in 0 until 250) outbox.enqueue(event("e$i", now - 1_000_000 + i))
        val report = engine.run()
        assertEquals(listOf(100, 100, 50), api.eventCalls.map { it.size })
        assertEquals(250, report.eventsSent)
    }

    @Test fun noNetworkKeepsEverythingAndPostponesIt() {
        api.defaultEvent = ApiResult.Transient("sin red")
        api.commands = CommandsResult(ApiResult.Transient("sin red"))
        outbox.enqueue(event("a"))
        val report = engine.run()
        assertEquals("sin red", report.error)
        val item = outbox.items.single()
        assertEquals(1, item.attempts)
        assertEquals(0, item.rejections)
        assertTrue(item.notBeforeMs > now)
        assertNull("sin conexión no cuenta como sincronizado", state.current.lastSyncOkAtMs)
        assertEquals("sin red", state.current.lastSyncError)
        // Y justo después no se vuelve a intentar: el elemento está en espera.
        api.eventCalls.clear()
        engine.run()
        assertTrue(api.eventCalls.isEmpty())
    }

    @Test fun aRejectedBatchIsIsolatedItemByItem() {
        outbox.enqueue(event("a", 1)); outbox.enqueue(event("b", 2)); outbox.enqueue(event("c", 3))
        api.eventResults += listOf(ApiResult.Rejected(400, "lote"), ApiResult.Ok, ApiResult.Rejected(422, "malo"), ApiResult.Ok)
        val report = engine.run()
        assertEquals(listOf(3, 1, 1, 1), api.eventCalls.map { it.size })
        assertEquals(2, report.eventsSent)
        val left = outbox.items.single()
        assertEquals("b", left.id)
        assertEquals(1, left.rejections)
        assertNotNull(state.current.lastSyncOkAtMs) // el servidor respondió
    }

    @Test fun aSingleRejectedItemIsPostponedAndEventuallyDropped() {
        outbox.enqueue(event("a"))
        api.defaultEvent = ApiResult.Rejected(422, "no")
        repeat(OutboxPolicy.MAX_REJECTIONS) {
            engine.run()
            now += 2 * HOUR // pasa la espera
        }
        assertEquals(0, outbox.count())
        assertEquals(OutboxPolicy.MAX_REJECTIONS, api.eventCalls.size)
    }

    @Test fun anUnauthorizedTokenStopsEverythingAndSaysSo() {
        api.defaultEvent = ApiResult.Unauthorized
        outbox.enqueue(event("a"))
        val report = engine.run()
        assertTrue(report.error!!.contains("token"))
        assertEquals(0, outbox.items.single().attempts)
        assertTrue("no se piden órdenes con un token rechazado", api.cursors.isEmpty())
        assertTrue(api.audioCalls.isEmpty())
        assertNull(state.current.lastSyncOkAtMs)
        assertTrue(state.current.lastSyncError.contains("token"))
    }

    @Test fun sendsAudioAndDeletesTheFileAfterwards() {
        val name = audio.save(segment())
        outbox.enqueue(OutboxItem("s1", OutboxItem.Kind.AUDIO, name, now))
        val report = engine.run()
        assertEquals(1, report.audioSent)
        assertEquals(0, outbox.count())
        assertNull(audio.file(name))
        assertEquals(name, api.audioCalls.single().second.name)
    }

    @Test fun anAudioItemWhoseFileVanishedIsDroppedWithoutACall() {
        outbox.enqueue(OutboxItem("s1", OutboxItem.Kind.AUDIO, "seg-00000000-0000-0000-0000-000000000000.wav", now))
        engine.run()
        assertEquals(0, outbox.count())
        assertTrue(api.audioCalls.isEmpty())
    }

    @Test fun anAudioNetworkFailureKeepsTheFile() {
        val name = audio.save(segment())
        outbox.enqueue(OutboxItem("s1", OutboxItem.Kind.AUDIO, name, now))
        api.defaultAudio = ApiResult.Transient("sin red")
        engine.run()
        assertNotNull(audio.file(name))
        assertEquals(1, outbox.items.single().attempts)
    }

    @Test fun aDroppedAudioItemDeletesItsFile() {
        val name = audio.save(segment())
        outbox.enqueue(OutboxItem("s1", OutboxItem.Kind.AUDIO, name, now))
        api.defaultAudio = ApiResult.Rejected(415, "formato")
        repeat(OutboxPolicy.MAX_REJECTIONS) {
            engine.run()
            now += 2 * HOUR
        }
        assertEquals(0, outbox.count())
        assertNull(audio.file(name))
    }

    @Test fun commandsAreAppliedTheCursorSavedAndRepeatsIgnored() {
        api.commands = CommandsResult(ApiResult.Ok, listOf(BrainCommand.Notify("n1", "Hola", "texto")), "cur1")
        val first = engine.run()
        assertEquals(1, first.commandsApplied)
        assertEquals("cur1", state.current.commandCursor)
        assertEquals(listOf("Hola"), notifier.notices)
        // El cerebro reenvía la misma orden: no se ejecuta otra vez.
        val second = engine.run()
        assertEquals(0, second.commandsApplied)
        assertEquals(1, notifier.notices.size)
        assertEquals(listOf("", "cur1"), api.cursors)
    }

    @Test fun aServerWithoutACommandsEndpointIsFine() {
        api.commands = CommandsResult(ApiResult.Rejected(404, "no hay"))
        outbox.enqueue(event("a"))
        val report = engine.run()
        assertNull(report.error)
        assertEquals(1, report.eventsSent)
    }

    @Test fun twoPassesNeverOverlap() {
        outbox.enqueue(event("a"))
        var inner: SyncEngine.Report? = null
        api.onPostEvents = { inner = engine.run() }
        val outer = engine.run()
        assertTrue("la pasada anidada no hace nada", inner!!.skipped)
        assertEquals(1, outer.eventsSent)
        assertEquals(1, api.eventCalls.size)
    }

    @Test fun aDiskFailureWhileRecordingTheResultDoesNotCrash() {
        outbox.enqueue(event("a"))
        state.failWrites = true
        assertEquals(1, engine.run().eventsSent)
        assertFalse(state.current.lastSyncOkAtMs != null)
    }
}
