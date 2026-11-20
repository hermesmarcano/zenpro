package com.hermesmarcano.zen.layer.android

import android.Manifest
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.layer.core.BrainCommand
import com.hermesmarcano.zen.layer.core.ChatFailure
import com.hermesmarcano.zen.layer.core.ChatSnapshot
import com.hermesmarcano.zen.layer.core.ChatStore
import com.hermesmarcano.zen.layer.core.CommandApplier
import com.hermesmarcano.zen.layer.core.OutboxItem
import com.hermesmarcano.zen.layer.link.ApiResult
import com.hermesmarcano.zen.layer.link.BrainApi
import com.hermesmarcano.zen.layer.link.BrainConfig
import com.hermesmarcano.zen.layer.link.ChatController
import com.hermesmarcano.zen.layer.link.ChatResult
import com.hermesmarcano.zen.layer.link.CommandsResult
import com.hermesmarcano.zen.layer.link.VoiceRecorder
import com.hermesmarcano.zen.layer.ui.ChatScreen
import com.hermesmarcano.zen.ui.ZenTheme
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class ScreenApi : BrainApi {
    val script = ArrayDeque<ChatResult>()
    val sent = ArrayList<Pair<String?, Int?>>()
    override fun postEvents(cfg: BrainConfig, events: List<OutboxItem>): ApiResult = ApiResult.Ok
    override fun postAudio(cfg: BrainConfig, item: OutboxItem, wav: ByteArray): ApiResult = ApiResult.Ok
    override fun fetchCommands(cfg: BrainConfig, cursor: String) = CommandsResult(ApiResult.Ok)
    override fun chat(cfg: BrainConfig, session: String, text: String?, wav: ByteArray?): ChatResult {
        sent += text to wav?.size
        return script.removeFirstOrNull() ?: ChatResult.Failed(ChatFailure.UNAVAILABLE)
    }
}

private class ScreenStore : ChatStore {
    var stored: ChatSnapshot? = null
    var failLoad = false
    override fun load(): ChatSnapshot? {
        if (failLoad) throw java.io.IOException("Keystore caído")
        return stored
    }
    override fun save(snapshot: ChatSnapshot) { stored = snapshot }
    override fun clear() { stored = null }
}

private class ScreenRecorder : VoiceRecorder {
    var recording = false
    var canceled = 0
    override fun start(onLimit: () -> Unit): VoiceRecorder.Start { recording = true; return VoiceRecorder.Start.STARTED }
    override fun stop(): ShortArray? = if (recording) { recording = false; ShortArray(32_000) { 5 } } else null
    override fun cancel() { recording = false; canceled++ }
}

/** La pantalla del chat con un controlador de verdad y un cerebro de mentira (hilos directos: todo es síncrono). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ZenApp::class, sdk = [34], qualifiers = "w411dp-h4000dp-xxhdpi")
class ChatScreenTest {
    init { useSoftwareSecrets() }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val api = ScreenApi()
    private val store = ScreenStore()
    private val recorder = ScreenRecorder()
    private var config: BrainConfig? = BrainConfig("https://brain.example.com", "tok", "dev-1")
    private var paused = false
    private var closed = false
    private val applied = ArrayList<BrainCommand>()
    private val direct = Executor { it.run() }
    private var visible by mutableStateOf(true)

    private val chat by lazy {
        ChatController(
            api = api, brain = { config }, paused = { paused }, store = store, recorder = recorder,
            applyCommand = { applied += it; CommandApplier.Outcome.APPLIED }, appLabel = { it },
            onTurn = { listOf("Conversación del día · +5 XP") }, net = direct, io = direct,
        ).also { it.ensureLoaded() }
    }

    @Before fun reset() {
        closed = false
        try {
            val policies = Class.forName("androidx.test.espresso.IdlingPolicies")
            for (name in listOf("setMasterPolicyTimeout", "setIdlingResourceTimeout")) {
                policies.getMethod(name, Long::class.javaPrimitiveType, java.util.concurrent.TimeUnit::class.java).invoke(null, 10L, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
        }
    }

    private fun frames(ms: Long = 300) = compose.mainClock.advanceTimeBy(ms)

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { ZenTheme { if (visible) ChatScreen(onClose = { closed = true }, chat = chat) } }
        frames()
    }

    private fun node(text: String, substring: Boolean = false) = compose.onNodeWithText(text, substring = substring)

    private fun SemanticsNodeInteraction.tap(): SemanticsNodeInteraction {
        performClick()
        frames()
        return this
    }

    private fun type(text: String) {
        node("Mensaje").performTextInput(text)
        frames()
    }

    /** Lo que llega desde otro hilo (la carga del historial) necesita fotogramas: se avanza el reloj hasta que se cumpla. */
    private fun pumpUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(10)
        }
        assertTrue("no llegó: $what", condition())
    }

    private fun exists(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test fun withoutABrainTheChatExplainsWhatIsMissingAndSendsNothing() {
        config = null
        show()
        assertTrue(exists("Primero configura el cerebro", substring = true))
        node("Mensaje").assertIsNotEnabled()
        node("Hablar").assertIsNotEnabled()
        assertTrue(api.sent.isEmpty())
    }

    @Test fun duringAPauseTheChatSaysSoAndSendsNothing() {
        paused = true
        show()
        assertTrue(exists("La capa está en pausa", substring = true))
        node("Hablar").assertIsNotEnabled()
    }

    @Test fun writingAMessageShowsItAndTheReplyAndClearsTheField() {
        api.script += ChatResult.Reply("Claro, te ayudo con eso.", null, emptyList())
        show()
        node("Enviar").assertIsNotEnabled() // sin texto no hay nada que enviar
        type("ayúdame a organizar mañana")
        node("Enviar").assertIsEnabled().tap()
        assertTrue(exists("ayúdame a organizar mañana"))
        assertTrue(exists("Claro, te ayudo con eso."))
        assertTrue(exists("Tú") && exists("Cerebro")) // quién habla, también para los lectores de pantalla
        assertTrue(exists("Conversación del día · +5 XP"))
        assertEquals(listOf<Pair<String?, Int?>>("ayúdame a organizar mañana" to null), api.sent)
        node("Enviar").assertIsNotEnabled() // el campo quedó vacío
    }

    @Test fun aCalendarProposalIsACardAndAgendarOpensTheCalendarFilledIn() {
        val event = BrainCommand.CalendarEvent("c1", "Cena con Marta", 1_800_000_000_000L, 1_800_003_600_000L, "chat")
        api.script += ChatResult.Reply("Te propongo la cena.", null, listOf(event))
        show()
        type("agéndame la cena con Marta mañana")
        node("Enviar").tap()
        assertTrue(exists("Propuesta para tu calendario"))
        assertTrue(exists("Cena con Marta"))
        node("Agendar").tap()
        val started: Intent = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_INSERT, started.action)
        assertEquals("Cena con Marta", started.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(1_800_000_000_000L, started.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0L))
        assertEquals(1_800_003_600_000L, started.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0L))
    }

    @Test fun aFailureShowsItsReasonAndRetryResendsTheSameText() {
        api.script += ChatResult.Failed(ChatFailure.TIMEOUT)
        api.script += ChatResult.Reply("Ya está.", null, emptyList())
        show()
        type("explícame esto despacio")
        node("Enviar").tap()
        assertTrue(exists(ChatFailure.TIMEOUT.message))
        node("Reintentar").tap()
        assertTrue(exists("Ya está."))
        assertFalse(exists(ChatFailure.TIMEOUT.message))
        assertEquals(listOf("explícame esto despacio", "explícame esto despacio"), api.sent.map { it.first })
    }

    @Test fun speakingRecordsShowsTheTimerAndSendsTheVoiceMessage() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        api.script += ChatResult.Reply("Anotado.", "recuérdame comprar pan mañana", emptyList())
        show()
        node("Hablar").tap()
        assertTrue(recorder.recording)
        assertTrue(exists("Grabando…", substring = true))
        node("Cancelar").assertIsEnabled()
        node("Enviar").tap()
        assertFalse(recorder.recording)
        assertTrue(exists("recuérdame comprar pan mañana"))
        assertTrue(exists("Anotado."))
        assertEquals(listOf<Pair<String?, Int?>>(null to 44 + 64_000), api.sent)
    }

    @Test fun cancelingARecordingSendsNothing() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        show()
        node("Hablar").tap()
        node("Cancelar").tap()
        assertEquals(1, recorder.canceled)
        assertTrue(api.sent.isEmpty())
        assertTrue(exists("Hablar"))
    }

    @Test fun leavingTheScreenWhileRecordingReleasesTheMicrophone() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        show()
        node("Hablar").tap()
        assertTrue(recorder.recording)
        visible = false
        frames()
        assertFalse(recorder.recording)
        assertTrue(api.sent.isEmpty())
    }

    @Test fun startingOverAsksFirstAndThenClears() {
        api.script += ChatResult.Reply("Hola.", null, emptyList())
        show()
        type("hola buenos días amigo")
        node("Enviar").tap()
        node("Nueva").tap()
        assertTrue(exists("¿Empezar una conversación nueva?"))
        node("Cancelar").tap()
        assertTrue(exists("hola buenos días amigo"))
        node("Nueva").tap()
        node("Empezar").tap()
        assertFalse(exists("hola buenos días amigo"))
        assertTrue(exists("Escríbele o háblale", substring = true))
    }

    @Test fun theModelTextIsShownAsPlainTextWithoutActiveLinks() {
        api.script += ChatResult.Reply("Entra en https://evil.example/pago ya", null, emptyList())
        show()
        type("¿qué hago ahora mismo?")
        node("Enviar").tap()
        assertTrue(exists("Entra en https://evil.example/pago ya"))
        assertEquals(null, shadowOf(app).nextStartedActivity) // nada se abre solo
    }

    @Test fun theChatOnlyListensWhileTheScreenIsStartedSoABackgroundReplyCanNotify() {
        show()
        assertTrue(chat.watched)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED) // la app pasa a segundo plano (ON_STOP)
        frames()
        assertFalse(chat.watched) // una orden que llegue ahora se anuncia con una notificación
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        frames()
        assertTrue(chat.watched)
    }

    @Test fun anUnreadableHistoryShowsAWayOutInsteadOfABlankScreen() {
        store.failLoad = true
        store.stored = ChatSnapshot("saved-session-1", listOf(com.hermesmarcano.zen.layer.core.ChatMessage("a", com.hermesmarcano.zen.layer.core.ChatRole.USER, "mensaje guardado", 1)))
        show()
        pumpUntil("el aviso de historial ilegible") { exists("No se pudo abrir la conversación guardada.") }
        node("Mensaje").assertIsNotEnabled()
        // El Keystore vuelve: «Reintentar» recupera la conversación.
        store.failLoad = false
        node("Reintentar").tap()
        pumpUntil("la conversación recuperada") { exists("mensaje guardado") }
    }

    @Test fun doneClosesTheScreen() {
        show()
        node("Listo").tap()
        assertTrue(closed)
    }
}
