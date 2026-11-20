package com.hermesmarcano.zen.layer.link

import com.hermesmarcano.zen.layer.core.BrainCommand
import com.hermesmarcano.zen.layer.core.ChatFailure
import com.hermesmarcano.zen.layer.core.ChatMessage
import com.hermesmarcano.zen.layer.core.ChatRole
import com.hermesmarcano.zen.layer.core.ChatRules
import com.hermesmarcano.zen.layer.core.ChatSnapshot
import com.hermesmarcano.zen.layer.core.ChatStore
import com.hermesmarcano.zen.layer.core.CommandApplier
import com.hermesmarcano.zen.layer.core.OutboxItem
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeChatApi : BrainApi {
    data class Call(val cfg: BrainConfig, val session: String, val text: String?, val wav: ByteArray?)

    val calls = ArrayList<Call>()
    val script = ArrayDeque<ChatResult>()

    override fun postEvents(cfg: BrainConfig, events: List<OutboxItem>): ApiResult = ApiResult.Ok
    override fun postAudio(cfg: BrainConfig, item: OutboxItem, wav: ByteArray): ApiResult = ApiResult.Ok
    override fun fetchCommands(cfg: BrainConfig, cursor: String) = CommandsResult(ApiResult.Ok)

    override fun chat(cfg: BrainConfig, session: String, text: String?, wav: ByteArray?): ChatResult {
        calls += Call(cfg, session, text, wav)
        return script.removeFirstOrNull() ?: ChatResult.Failed(ChatFailure.UNAVAILABLE)
    }
}

private class MemoryChatStore : ChatStore {
    var stored: ChatSnapshot? = null
    var failLoad = false
    var failSave = false
    val saves = ArrayList<ChatSnapshot>()
    var cleared = 0
    var failClear = false

    override fun load(): ChatSnapshot? {
        if (failLoad) throw IOException("Keystore caído")
        return stored
    }

    override fun save(snapshot: ChatSnapshot) {
        if (failSave) throw IOException("disco lleno")
        stored = snapshot
        saves += snapshot
    }

    override fun clear() {
        if (failClear) throw IOException("no se pudo borrar")
        stored = null
        cleared++
    }
}

private class FakeRecorder : VoiceRecorder {
    var start = VoiceRecorder.Start.STARTED
    var pcm: ShortArray? = ShortArray(16_000) { 7 }
    var recording = false
    var canceled = 0
    var stops = 0
    var onLimit: (() -> Unit)? = null

    override fun start(onLimit: () -> Unit): VoiceRecorder.Start {
        this.onLimit = onLimit
        if (start == VoiceRecorder.Start.STARTED) recording = true
        return start
    }

    override fun stop(): ShortArray? {
        stops++
        if (!recording) return null
        recording = false
        return pcm
    }

    override fun cancel() {
        recording = false
        canceled++
    }
}

/** Guarda las tareas hasta que se le pide ejecutarlas: así se prueba lo que pasa MIENTRAS se espera la respuesta. */
private class ManualExecutor : Executor {
    val queue = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) {
        queue += command
    }

    fun runAll() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }
}

/** Ejecuta las tareas en orden INVERSO: así un guardado que hubiera tomado su estado al encolarse dejaría lo viejo al final. */
private class ReverseExecutor : Executor {
    val queue = ArrayList<Runnable>()
    override fun execute(command: Runnable) {
        queue += command
    }

    fun runAllReversed() {
        queue.reversed().forEach { it.run() }
        queue.clear()
    }
}

private val direct = Executor { it.run() }

class ChatControllerTest {
    private val api = FakeChatApi()
    private val store = MemoryChatStore()
    private val recorder = FakeRecorder()
    private var config: BrainConfig? = BrainConfig("https://brain.example.com", "tok", "dev-1")
    private var paused = false
    private var now = 1_000_000L
    private var ids = 0
    private val applied = ArrayList<BrainCommand>()
    private var outcome = CommandApplier.Outcome.APPLIED
    private val turns = ArrayList<Boolean>()
    private var earned = listOf("Conversación del día · +5 XP")
    private var net: Executor = direct
    private var io: Executor = direct
    private var watched = false

    private var onApply: (BrainCommand) -> Unit = {}

    private fun controller(): ChatController = ChatController(
        api = api,
        brain = { config },
        paused = { paused },
        store = store,
        recorder = recorder,
        applyCommand = { onApply(it); applied += it; outcome },
        appLabel = { "App($it)" },
        onTurn = { voice -> turns += voice; earned },
        net = net,
        io = io,
        clock = { now++ },
        newId = { "id${++ids}" },
    )

    private fun loaded(): ChatController = controller().also { it.ensureLoaded() }

    private fun reply(text: String = "Claro.", transcript: String? = null, vararg commands: BrainCommand) =
        ChatResult.Reply(text, transcript, commands.toList())

    // ---------- Texto ----------

    @Test fun aTextMessageGetsItsReplyAndIsSaved() {
        val chat = loaded()
        api.script += reply("Te ayudo con eso.")
        assertEquals(ChatSend.SENT, chat.sendText("  ayúdame a organizar mañana  "))
        val ui = chat.ui()
        assertEquals(listOf(ChatRole.USER, ChatRole.BRAIN), ui.messages.map { it.role })
        assertEquals(listOf("ayúdame a organizar mañana", "Te ayudo con eso."), ui.messages.map { it.text })
        assertEquals(ChatPhase.IDLE, ui.phase)
        assertEquals("tok", api.calls.single().cfg.token)
        assertEquals(store.stored?.messages, ui.messages)
        assertEquals("id1", store.stored?.session) // la sesión del servidor viaja con cada mensaje
        assertEquals("id1", api.calls.single().session)
    }

    @Test fun onlyARealConversationEarnsAndShowsTheCelebration() {
        val chat = loaded()
        api.script += reply("Hola.")
        chat.sendText("hola")
        assertTrue(turns.isEmpty())
        assertTrue(chat.ui().celebration.isEmpty())

        api.script += reply("Listo.")
        chat.sendText("agéndame la cena de mañana")
        assertEquals(listOf(false), turns)
        assertEquals(listOf("Conversación del día · +5 XP"), chat.ui().celebration)

        api.script += reply("Vale.")
        chat.sendText("otra pregunta distinta")
        assertEquals(listOf(false, false), turns) // el tope diario lo aplica el juego; aquí cada intercambio avisa
    }

    @Test fun invalidInputIsRefusedWithoutTouchingTheNetwork() {
        val chat = loaded()
        assertEquals(ChatSend.INVALID, chat.sendText("   "))
        assertEquals(ChatSend.INVALID, chat.sendText("x".repeat(ChatRules.MAX_TEXT_CHARS + 1)))
        assertTrue(api.calls.isEmpty())
        assertTrue(chat.ui().messages.isEmpty())
    }

    @Test fun nothingIsSentWithoutABrainOrWhilePaused() {
        val chat = loaded()
        config = null
        assertEquals(ChatSend.NO_BRAIN, chat.sendText("hola de nuevo amigo"))
        assertEquals(ChatAvailability.NO_BRAIN, chat.ui().availability)
        config = BrainConfig("https://brain.example.com", "tok", "dev-1")
        paused = true
        assertEquals(ChatSend.PAUSED, chat.sendText("hola de nuevo amigo"))
        assertEquals(ChatAvailability.PAUSED, chat.ui().availability)
        assertEquals(ChatStart.PAUSED, chat.startRecording())
        assertTrue(api.calls.isEmpty())
        assertTrue(chat.ui().messages.isEmpty())
        assertFalse(recorder.recording)
    }

    @Test fun nothingIsSentBeforeTheHistoryIsLoaded() {
        val chat = controller()
        assertEquals(ChatSend.NOT_READY, chat.sendText("hola de nuevo amigo"))
        assertEquals(ChatStart.NOT_READY, chat.startRecording())
    }

    @Test fun aSecondMessageWhileWaitingIsRefused() {
        net = ManualExecutor()
        val chat = loaded()
        assertEquals(ChatSend.SENT, chat.sendText("primera pregunta larga"))
        assertEquals(ChatPhase.WAITING, chat.ui().phase)
        assertEquals(ChatSend.BUSY, chat.sendText("segunda pregunta larga"))
        assertEquals(ChatStart.BUSY, chat.startRecording())
        assertEquals(1, chat.ui().messages.size)
    }

    // ---------- Fallos y reintento ----------

    @Test fun aFailureMarksTheMessageAndOffersARetryThatDoesNotDuplicateIt() {
        val chat = loaded()
        api.script += ChatResult.Failed(ChatFailure.TIMEOUT)
        chat.sendText("explícame esto despacio")
        val failed = chat.ui().messages.single()
        assertEquals(ChatFailure.TIMEOUT, failed.failure)
        assertEquals(ChatPhase.IDLE, chat.ui().phase)

        api.script += reply("Ahora sí.")
        assertEquals(ChatSend.SENT, chat.retry(failed.id))
        val ui = chat.ui()
        assertEquals(2, ui.messages.size)
        assertNull(ui.messages.first().failure)
        assertEquals(listOf("explícame esto despacio", "explícame esto despacio"), api.calls.map { it.text })
    }

    @Test fun aRejectionThatWaitingCannotFixIsNotRetried() {
        val chat = loaded()
        api.script += ChatResult.Failed(ChatFailure.UNAUTHORIZED)
        chat.sendText("explícame esto despacio")
        assertEquals(ChatSend.INVALID, chat.retry(chat.ui().messages.single().id))
        assertEquals(1, api.calls.size)
    }

    @Test fun anExceptionFromTheClientNeverLeavesTheChatWaiting() {
        val chat = ChatController(
            api = object : BrainApi {
                override fun postEvents(cfg: BrainConfig, events: List<OutboxItem>): ApiResult = ApiResult.Ok
                override fun postAudio(cfg: BrainConfig, item: OutboxItem, wav: ByteArray): ApiResult = ApiResult.Ok
                override fun fetchCommands(cfg: BrainConfig, cursor: String) = CommandsResult(ApiResult.Ok)
                override fun chat(cfg: BrainConfig, session: String, text: String?, wav: ByteArray?): ChatResult = throw IllegalStateException("boom")
            },
            brain = { config }, paused = { paused }, store = store, recorder = recorder,
            applyCommand = { CommandApplier.Outcome.APPLIED }, appLabel = { it }, onTurn = { emptyList() }, net = direct, io = direct,
        )
        chat.ensureLoaded()
        chat.sendText("esto va a fallar seguro")
        assertEquals(ChatPhase.IDLE, chat.ui().phase)
        assertEquals(ChatFailure.UNAVAILABLE, chat.ui().messages.single().failure)
    }

    @Test fun aGameHookThatBlowsUpDoesNotLoseTheAnswer() {
        val chat = ChatController(
            api = api, brain = { config }, paused = { paused }, store = store, recorder = recorder,
            applyCommand = { CommandApplier.Outcome.APPLIED }, appLabel = { it }, onTurn = { throw IllegalStateException("xp") }, net = direct, io = direct,
        )
        chat.ensureLoaded()
        api.script += reply("Aquí estoy.")
        chat.sendText("cuéntame algo bueno hoy")
        assertEquals("Aquí estoy.", chat.ui().messages.last().text)
        assertEquals(ChatPhase.IDLE, chat.ui().phase)
    }

    // ---------- Órdenes ----------

    private val event = BrainCommand.CalendarEvent("c1", "Cena con Marta", 5_000_000L, 8_600_000L, "chat")

    @Test fun aCalendarProposalBecomesACardNotAnAutomaticEvent() {
        val chat = loaded()
        api.script += reply("Te propongo la cena.", null, event)
        chat.sendText("agéndame la cena con Marta")
        assertEquals(listOf<BrainCommand>(event), applied) // pasó por el aplicador (validación y topes)
        val card = chat.ui().messages.last()
        assertEquals("Cena con Marta", card.proposal?.title)
        assertEquals(5_000_000L, card.proposal?.startMs)
        assertEquals(8_600_000L, card.proposal?.endMs)
        assertEquals("c1", card.proposal?.commandId)
    }

    @Test fun onlyCalendarBlockAndPauseCommandsAreAppliedAndOnlyThree() {
        val chat = loaded()
        val extras = listOf(
            BrainCommand.Notify("n1", "ZEN", "aviso"),
            BrainCommand.DraftReply("d1", "wa|x", "hola"),
            event,
            BrainCommand.Pause("p1", 15),
            BrainCommand.Block("b1", "com.tiktok", 30),
            BrainCommand.Pause("p2", 20),
        )
        api.script += ChatResult.Reply("Hecho.", null, extras)
        chat.sendText("hazlo todo por favor")
        assertEquals(listOf("c1", "p1", "b1"), applied.map { it.id })
    }

    @Test fun whatWasDoneIsToldHonestly() {
        val chat = loaded()
        api.script += ChatResult.Reply("Hecho.", null, listOf(BrainCommand.Pause("p1", 90), BrainCommand.Block("b1", "com.tiktok", 30)))
        chat.sendText("pausa y bloquea tiktok")
        val notes = chat.ui().messages.filter { it.role == ChatRole.NOTE }.map { it.text }
        assertEquals(listOf("Pausé la capa durante 1 h 30 min.", "Bloqueé App(com.tiktok) durante 30 min."), notes)
    }

    @Test fun aRejectedCommandIsExplainedAndNeverShownAsDone() {
        val chat = loaded()
        outcome = CommandApplier.Outcome.REJECTED
        api.script += ChatResult.Reply("Hecho.", null, listOf(BrainCommand.Block("b1", "com.tiktok", 30), event, BrainCommand.Pause("p1", 15)))
        chat.sendText("bloquea tiktok y agenda algo")
        val notes = chat.ui().messages.filter { it.role == ChatRole.NOTE }.map { it.text }
        assertEquals(3, notes.size)
        assertTrue("Límites" in notes[0] && notes[0].startsWith("No bloqueé"))
        assertTrue(notes[1].startsWith("No pude proponer"))
        assertTrue(notes[2].startsWith("No pude pausar"))
        assertTrue(chat.ui().messages.none { it.proposal != null })
    }

    @Test fun aRepeatedCommandSaysNothing() {
        val chat = loaded()
        outcome = CommandApplier.Outcome.DUPLICATE
        api.script += ChatResult.Reply("Hecho.", null, listOf(event))
        chat.sendText("agenda la cena otra vez")
        assertEquals(listOf(ChatRole.USER, ChatRole.BRAIN), chat.ui().messages.map { it.role })
    }

    @Test fun aCommandThatThrowsIsJustRejected() {
        val chat = ChatController(
            api = api, brain = { config }, paused = { paused }, store = store, recorder = recorder,
            applyCommand = { throw IllegalStateException("disco") }, appLabel = { it }, onTurn = { emptyList() }, net = direct, io = direct,
        )
        chat.ensureLoaded()
        api.script += ChatResult.Reply("Hecho.", null, listOf(BrainCommand.Pause("p1", 15)))
        chat.sendText("pausa un rato por favor")
        assertEquals(ChatPhase.IDLE, chat.ui().phase)
        assertTrue(chat.ui().messages.last().text.startsWith("No pude pausar"))
    }

    // ---------- Nueva conversación ----------

    @Test fun aReplyThatArrivesAfterANewConversationIsDiscarded() {
        val manual = ManualExecutor()
        net = manual
        val chat = loaded()
        api.script += ChatResult.Reply("Respuesta tardía.", null, listOf(event))
        chat.sendText("pregunta de la conversación vieja")
        chat.newConversation()
        manual.runAll()
        val ui = chat.ui()
        assertTrue(ui.messages.isEmpty())
        assertEquals(ChatPhase.IDLE, ui.phase)
        assertTrue(applied.isEmpty()) // ni órdenes ni XP de una conversación que ya no existe
        assertTrue(turns.isEmpty())
        assertEquals("id1", api.calls.single().session)
        assertNull(store.stored) // la conversación vieja no quedó en el disco
        chat.sendText("mensaje de la conversación nueva")
        assertEquals("id3", store.stored?.session) // otra sesión en el servidor (id2 fue el mensaje viejo)
    }

    @Test fun aNewConversationStartsFreshAndKeepsTheNewSession() {
        val chat = loaded()
        api.script += reply("Uno.")
        chat.sendText("primera conversación aquí")
        chat.newConversation()
        api.script += reply("Dos.")
        chat.sendText("segunda conversación aquí")
        assertEquals(2, chat.ui().messages.size)
        assertEquals(2, api.calls.map { it.session }.distinct().size)
    }

    // ---------- Voz ----------

    @Test fun aVoiceMessageIsSentAsWavAndTheTranscriptFillsItIn() {
        val chat = loaded()
        assertEquals(ChatStart.STARTED, chat.startRecording())
        assertEquals(ChatPhase.RECORDING, chat.ui().phase)
        api.script += reply("Anotado.", "recuérdame comprar pan mañana")
        chat.finishRecording()
        val call = api.calls.single()
        assertNull(call.text)
        val wav = call.wav!!
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(44 + 32_000, wav.size) // 1 s a 16 kHz, 16 bits
        val ui = chat.ui()
        assertEquals(ChatPhase.IDLE, ui.phase)
        val mine = ui.messages.first()
        assertTrue(mine.voice)
        assertEquals("recuérdame comprar pan mañana", mine.text)
        assertEquals(listOf(true), turns) // dicho por voz
    }

    @Test fun aTooShortRecordingIsNotSent() {
        val chat = loaded()
        recorder.pcm = ShortArray(3_000)
        chat.startRecording()
        chat.finishRecording()
        assertTrue(api.calls.isEmpty())
        assertTrue(chat.ui().messages.isEmpty())
        assertNotNull(chat.ui().notice)
        assertEquals(ChatPhase.IDLE, chat.ui().phase)
    }

    @Test fun withoutTheMicrophonePermissionNothingStartsAndItIsSaid() {
        val chat = loaded()
        recorder.start = VoiceRecorder.Start.NO_PERMISSION
        assertEquals(ChatStart.NO_PERMISSION, chat.startRecording())
        assertEquals(ChatPhase.IDLE, chat.ui().phase)
        assertNotNull(chat.ui().notice)
    }

    @Test fun aBusyMicrophoneReturnsToIdle() {
        val chat = loaded()
        recorder.start = VoiceRecorder.Start.MIC_BUSY
        assertEquals(ChatStart.MIC_BUSY, chat.startRecording())
        assertEquals(ChatPhase.IDLE, chat.ui().phase)
    }

    @Test fun finishingTwiceSendsOnlyOnce() {
        val chat = loaded()
        chat.startRecording()
        api.script += reply("Ok.", "una frase de prueba")
        chat.finishRecording()
        chat.finishRecording() // el toque y el tope de duración a la vez
        assertEquals(1, api.calls.size)
    }

    @Test fun cancelingDiscardsTheAudioAndFreesTheMicrophone() {
        val chat = loaded()
        chat.startRecording()
        chat.cancelRecording()
        assertEquals(1, recorder.canceled)
        assertEquals(ChatPhase.IDLE, chat.ui().phase)
        chat.finishRecording() // ya no hay nada que enviar
        assertTrue(api.calls.isEmpty())
    }

    @Test fun thePauseCutsAVoiceMessageThatWasAlreadyRecording() {
        val chat = loaded()
        chat.startRecording()
        paused = true
        chat.finishRecording()
        assertTrue(api.calls.isEmpty())
        assertEquals(ChatPhase.IDLE, chat.ui().phase)
        assertNotNull(chat.ui().notice)
    }

    @Test fun reachingTheLimitSendsWhatWasRecorded() {
        val chat = loaded()
        chat.startRecording()
        api.script += reply("Ok.", "una frase de prueba")
        recorder.onLimit!!.invoke() // el grabador avisa; el io de la prueba es directo
        assertEquals(1, api.calls.size)
    }

    @Test fun aNewConversationWhileRecordingReleasesTheMicrophone() {
        val chat = loaded()
        chat.startRecording()
        chat.newConversation()
        assertEquals(1, recorder.canceled)
        assertEquals(ChatPhase.IDLE, chat.ui().phase)
    }

    // ---------- Guardado ----------

    @Test fun theSavedConversationComesBackAndAnUnansweredMessageIsMarkedInterrupted() {
        store.stored = ChatSnapshot(
            "saved-session-1",
            listOf(
                ChatMessage("a", ChatRole.USER, "hola", 1),
                ChatMessage("b", ChatRole.BRAIN, "buenas", 2),
                ChatMessage("c", ChatRole.USER, "¿sigues ahí?", 3),
            ),
        )
        val chat = loaded()
        val ui = chat.ui()
        assertEquals(3, ui.messages.size)
        assertEquals(ChatFailure.INTERRUPTED, ui.messages.last().failure)
        assertNull(ui.messages.first().failure)
        api.script += reply("Sí.")
        chat.sendText("sigo aquí contigo")
        assertEquals("saved-session-1", api.calls.single().session) // el servidor sigue la misma conversación
    }

    @Test fun anUnreadableHistoryKeepsTheChatClosedAndNothingIsOverwritten() {
        store.stored = ChatSnapshot("saved-session-1", listOf(ChatMessage("a", ChatRole.USER, "secreto", 1), ChatMessage("b", ChatRole.BRAIN, "ok", 2)))
        store.failLoad = true
        val chat = controller()
        chat.ensureLoaded()
        assertFalse(chat.ui().loaded)
        assertNotNull(chat.ui().notice)
        assertEquals(ChatSend.NOT_READY, chat.sendText("mensaje tras el fallo de lectura"))
        assertEquals(ChatStart.NOT_READY, chat.startRecording())
        assertTrue(store.saves.isEmpty()) // el archivo bueno no se pisa
        // El Keystore vuelve: al abrir de nuevo se recupera la conversación y se quita el aviso.
        store.failLoad = false
        chat.ensureLoaded()
        assertTrue(chat.ui().loaded)
        assertEquals(2, chat.ui().messages.size)
        assertNull(chat.ui().notice)
        assertEquals(ChatSend.SENT.also { api.script += reply("Hola.") }, chat.sendText("ahora sí puedo escribir"))
    }

    @Test fun startingOverIsTheExplicitWayOutOfAnUnreadableHistory() {
        store.failLoad = true
        val chat = controller()
        chat.ensureLoaded()
        chat.newConversation()
        assertTrue(chat.ui().loaded)
        assertEquals(1, store.cleared) // la persona decidió empezar de cero: lo viejo ya no está en el disco
        api.script += reply("Hola.")
        assertEquals(ChatSend.SENT, chat.sendText("empezamos de nuevo aquí"))
        assertEquals(2, store.stored?.messages?.size) // y lo nuevo sí se guarda
    }

    @Test fun aFailedSaveIsToldAndTheChatKeepsWorking() {
        val chat = loaded()
        store.failSave = true
        api.script += reply("Hola.")
        chat.sendText("mensaje con el disco lleno")
        assertEquals(2, chat.ui().messages.size)
        assertNotNull(chat.ui().notice)
    }

    @Test fun wipeErasesTheFileEvenIfTheChatWasNeverOpened() {
        store.stored = ChatSnapshot("old-session-1", listOf(ChatMessage("a", ChatRole.USER, "secreto", 1)))
        val chat = controller()
        chat.wipe()
        assertEquals(1, store.cleared)
        assertNull(store.stored)
        assertTrue(chat.ui().messages.isEmpty())
        assertTrue(chat.ui().loaded) // no se vuelve a leer lo borrado
    }

    @Test fun theHistoryIsCapped() {
        val chat = loaded()
        repeat(60) {
            api.script += reply("r$it")
            chat.sendText("mensaje número $it hoy")
        }
        assertEquals(ChatRules.MAX_HISTORY, chat.ui().messages.size)
        assertEquals("r59", chat.ui().messages.last().text)
    }

    @Test fun listenersHearChangesAndStopWhenAsked() {
        val chat = loaded()
        val heard = ArrayList<ChatPhase>()
        val stop = chat.listen { heard += it.phase }
        api.script += reply("Hola.")
        chat.sendText("mensaje para el oyente")
        assertTrue(heard.isNotEmpty())
        val count = heard.size
        stop()
        api.script += reply("Otra.")
        chat.sendText("otro mensaje para el oyente")
        assertEquals(count, heard.size)
    }

    @Test fun aListenerThatThrowsDoesNotBreakTheChat() {
        val chat = loaded()
        chat.listen { throw IllegalStateException("pantalla rota") }
        api.script += reply("Hola.")
        chat.sendText("mensaje con oyente roto")
        assertEquals(2, chat.ui().messages.size)
    }

    // ---------- Carreras (revisión independiente) ----------

    @Test fun whatIsSavedLastIsAlwaysTheLatestStateEvenIfTheDiskTasksRunOutOfOrder() {
        val reversed = ReverseExecutor()
        io = reversed
        val chat = loaded()
        api.script += reply("Respuesta.")
        chat.sendText("mensaje de la conversación vieja")
        chat.newConversation()
        reversed.runAllReversed() // si cada guardado llevara el estado de cuando se encoló, ganaría el más viejo
        assertNull(store.stored) // la conversación vieja no reaparece en el disco
        assertTrue(store.saves.isEmpty())
    }

    @Test fun anEmptyConversationIsErasedNotSaved() {
        val chat = loaded()
        api.script += reply("Hola.")
        chat.sendText("mensaje que se va a borrar")
        assertEquals(2, store.stored?.messages?.size)
        chat.newConversation()
        assertNull(store.stored)
    }

    @Test fun wipeSaysWhetherTheFileIsReallyGone() {
        store.stored = ChatSnapshot("old-session-1", listOf(ChatMessage("a", ChatRole.USER, "secreto", 1)))
        val chat = controller()
        store.failClear = true
        assertFalse(chat.wipe())
        assertTrue(chat.ui().messages.isEmpty()) // en pantalla ya no está, pero se dice que el disco falló
        store.failClear = false
        assertTrue(chat.wipe())
        assertNull(store.stored)
    }

    @Test fun aNewRecordingStartedWhileTheOldOneWasBeingFinishedIsNeitherJammedNorAdopted() {
        var inStop = true
        val c = withRecorder { me ->
            object : VoiceRecorder {
                override fun start(onLimit: () -> Unit) = recorder.start(onLimit)
                override fun stop(): ShortArray? {
                    if (inStop) {
                        inStop = false
                        me().cancelRecording() // cancelar y empezar otra, justo mientras se termina la primera
                        me().startRecording()
                    }
                    return recorder.stop()
                }

                override fun cancel() = recorder.cancel()
            }
        }
        c.startRecording()
        c.finishRecording()
        assertTrue(api.calls.isEmpty()) // la primera ya no existe: no se envía nada
        assertEquals(ChatPhase.RECORDING, c.ui().phase) // y la segunda sigue en marcha
        c.finishRecording() // la segunda se puede terminar (no quedó una marca pegada)
        assertEquals(ChatPhase.IDLE, c.ui().phase)
    }

    /** Un controlador cuyo grabador puede actuar sobre el propio controlador (para provocar la carrera exacta). */
    private fun withRecorder(make: (() -> ChatController) -> VoiceRecorder): ChatController {
        lateinit var self: ChatController
        self = ChatController(
            api = api, brain = { config }, paused = { paused }, store = store, recorder = make { self },
            applyCommand = { CommandApplier.Outcome.APPLIED }, appLabel = { it }, onTurn = { emptyList() }, net = direct, io = direct,
        )
        self.ensureLoaded()
        return self
    }

    @Test fun aRecordingThatOpenedAfterTheConversationWasResetIsReleased() {
        val c = withRecorder { me ->
            object : VoiceRecorder {
                override fun start(onLimit: () -> Unit): VoiceRecorder.Start {
                    me().newConversation() // otro hilo reinicia mientras se abre el micrófono
                    recorder.recording = true
                    return VoiceRecorder.Start.STARTED
                }

                override fun stop(): ShortArray? = recorder.stop()
                override fun cancel() = recorder.cancel()
            }
        }
        c.startRecording()
        assertFalse(recorder.recording) // no queda el micrófono abierto con el chat en reposo
        assertEquals(ChatPhase.IDLE, c.ui().phase)
    }

    @Test fun cancelingWhileTheRecordingIsBeingFinishedDoesNotJamTheNextOne() {
        var cancelDuringStop = true
        val c = withRecorder { me ->
            object : VoiceRecorder {
                override fun start(onLimit: () -> Unit) = recorder.start(onLimit)
                override fun stop(): ShortArray? {
                    if (cancelDuringStop) me().cancelRecording() // el usuario cancela justo mientras se termina
                    return recorder.stop()
                }

                override fun cancel() = recorder.cancel()
            }
        }
        c.startRecording()
        c.finishRecording()
        assertTrue(api.calls.isEmpty()) // se canceló: no se envía
        assertEquals(ChatPhase.IDLE, c.ui().phase)
        // La siguiente grabación funciona (la bandera de «terminando» no quedó pegada).
        cancelDuringStop = false
        recorder.pcm = ShortArray(16_000) { 7 }
        assertEquals(ChatStart.STARTED, c.startRecording())
        api.script += reply("Ok.", "una frase de prueba")
        c.finishRecording()
        assertEquals(1, api.calls.size)
    }

    @Test fun aConversationDiscardedInTheMiddleOfApplyingCommandsStopsApplyingAndEarnsNothing() {
        val chat = loaded()
        onApply = { chat.newConversation() } // la persona toca «Nueva» justo mientras se aplica la primera orden
        api.script += ChatResult.Reply("Hecho.", null, listOf(BrainCommand.Pause("p1", 5), BrainCommand.Pause("p2", 5)))
        chat.sendText("pausa dos veces por favor")
        assertEquals(listOf("p1"), applied.map { it.id })
        assertTrue(turns.isEmpty())
        assertTrue(chat.ui().messages.isEmpty())
    }

    @Test fun theChatKnowsWhetherAScreenIsWatchingIt() {
        val chat = loaded()
        assertFalse(chat.watched)
        val stop = chat.listen { }
        assertTrue(chat.watched)
        stop()
        assertFalse(chat.watched)
    }
}
