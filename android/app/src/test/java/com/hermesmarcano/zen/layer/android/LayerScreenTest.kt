package com.hermesmarcano.zen.layer.android

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.layer.capture.layer
import com.hermesmarcano.zen.layer.core.BlockEntry
import com.hermesmarcano.zen.layer.core.BlockReason
import com.hermesmarcano.zen.layer.core.DraftStatus
import com.hermesmarcano.zen.layer.core.LayerEvent
import com.hermesmarcano.zen.layer.core.LimitRule
import com.hermesmarcano.zen.layer.ui.AppEntry
import com.hermesmarcano.zen.layer.ui.LayerScreen
import com.hermesmarcano.zen.ui.SettingsScreen
import com.hermesmarcano.zen.ui.ZenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val TIKTOK = "com.zhiliaoapp.musically"

/** La pantalla "Capa": que muestre lo esencial, que cada control guarde lo que dice y que las reglas de fricción se cumplan. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ZenApp::class, sdk = [34], qualifiers = "w411dp-h4000dp-xxhdpi")
class LayerScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val runtime get() = app.layer
    private var closed = false
    private var now = System.currentTimeMillis()
    private val apps = listOf(AppEntry(TIKTOK, "TikTok"), AppEntry("com.whatsapp", "WhatsApp"), AppEntry("com.bbva.mx", "Banco"))

    @Before fun reset() {
        closed = false
        // Un cuelgue de Compose debe costar segundos, no un minuto.
        shortenIdlingTimeout()
    }

    /** Espresso solo está en el classpath de ejecución (lo usa Compose por dentro): se llama por reflexión y sin que falle nada. */
    private fun shortenIdlingTimeout() {
        try {
            val policies = Class.forName("androidx.test.espresso.IdlingPolicies")
            for (name in listOf("setMasterPolicyTimeout", "setIdlingResourceTimeout")) {
                policies.getMethod(name, Long::class.javaPrimitiveType, java.util.concurrent.TimeUnit::class.java).invoke(null, 10L, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
        }
    }

    /** Con el reloj manual hay que avanzar un fotograma tras cada acción para ver el cambio en pantalla. */
    private fun frames(ms: Long = 300) = compose.mainClock.advanceTimeBy(ms)

    private fun SemanticsNodeInteraction.tap(): SemanticsNodeInteraction {
        performClick()
        frames()
        return this
    }

    private fun SemanticsNodeInteraction.typeText(text: String): SemanticsNodeInteraction {
        performTextInput(text)
        frames()
        return this
    }

    private fun SemanticsNodeInteraction.clearText(): SemanticsNodeInteraction {
        performTextClearance()
        frames()
        return this
    }

    /**
     * Reloj manual y ventana muy alta (no hace falta desplazarse). Dentro de un diálogo el campo de texto tiene la ventana
     * enfocada y su cursor parpadea sin fin: con el reloj automático Compose no llegaría nunca a reposo.
     */
    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { ZenTheme { LayerScreen(onClose = { closed = true }, appLoader = { apps }, clock = { now }) } }
    }

    private fun toggles() = compose.onAllNodes(isToggleable())

    private fun node(text: String, substring: Boolean = false) = compose.onNodeWithText(text, substring = substring)

    /** Avanza el reloj de Compose a trozos hasta que se cumpla [condition] (lo que llega desde otro hilo necesita fotogramas). */
    private fun pumpUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(10)
        }
        assertTrue("no llegó: $what", condition())
    }

    private fun waitForText(text: String, substring: Boolean = false) =
        pumpUntil(text) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun startedActivity(): Intent? = shadowOf(app as Application).nextStartedActivity

    // --- Lo esencial ---

    @Test fun showsEveryAlwaysVisibleSectionAndTheHonestIntro() {
        show()
        compose.onNodeWithText("Capa").assertIsDisplayed()
        compose.onNodeWithText("Todo empieza apagado", substring = true).assertIsDisplayed()
        for (section in listOf("Estado", "Pausa", "Qué observa", "Apps que no se miran nunca", "Límites y bloqueos", "Memoria", "Cerebro")) {
            compose.onNodeWithText(section).assertExists()
        }
    }

    @Test fun listoClosesTheScreen() {
        show()
        compose.onNodeWithText("Listo").tap()
        assertTrue(closed)
    }

    @Test fun everySourceStartsOff() {
        show()
        toggles().assertCountEquals(3)
        for (i in 0 until 3) toggles()[i].assertIsOff()
    }

    @Test fun withNothingOnTheStatusOnlyMentionsTheBrain() {
        show()
        compose.onNodeWithText("Sin cerebro configurado", substring = true).assertExists()
        compose.onNodeWithText("Abrir accesibilidad").assertDoesNotExist()
    }

    // --- Fuentes ---

    @Test fun turningOnTheScreenSourceSavesItAndOpensAccessibilitySettings() {
        show()
        toggles()[0].tap()
        assertTrue(runtime.state.current.capture.screenText)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, startedActivity()!!.action)
        // Y el estado ahora dice qué falta.
        compose.onNodeWithText("Servicio de accesibilidad apagado", substring = true).assertExists()
    }

    @Test fun turningOnNotificationsOpensNotificationAccess() {
        show()
        toggles()[1].tap()
        assertTrue(runtime.state.current.capture.notifications)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, startedActivity()!!.action)
    }

    @Test fun theSourcesCanBeTurnedBackOff() {
        runtime.state.update { it.copy(capture = it.capture.copy(screenText = true, notifications = true)) }
        show()
        toggles()[0].assertIsOn()
        toggles()[0].tap()
        toggles()[1].tap()
        assertFalse(runtime.state.current.capture.screenText)
        assertFalse(runtime.state.current.capture.notifications)
    }

    @Test fun theMicrophoneRefusesToTurnOnWithoutABrain() {
        show()
        toggles()[2].tap()
        assertFalse(runtime.state.current.capture.ambientAudio)
        compose.onNodeWithText("Primero configura el cerebro", substring = true).assertExists()
        assertNull(shadowOf(app as Application).nextStartedService)
    }

    @Test fun theMicrophoneStartsTheServiceOnceEverythingIsInPlace() {
        runtime.state.update { it.copy(brainUrl = "https://brain.example.com", brainToken = "tok") }
        shadowOf(app as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        show()
        toggles()[2].tap()
        assertTrue(runtime.state.current.capture.ambientAudio)
        assertNotNull(shadowOf(app as Application).nextStartedService)
    }

    @Test fun turningTheMicrophoneOffStopsIt() {
        runtime.state.update { it.copy(brainUrl = "https://brain.example.com", brainToken = "tok", capture = it.capture.copy(ambientAudio = true)) }
        show()
        toggles()[2].assertIsOn()
        toggles()[2].tap()
        assertFalse(runtime.state.current.capture.ambientAudio)
    }

    // --- Estado ---

    @Test fun theStatusOffersTheFixForWhatIsMissing() {
        runtime.state.update { it.copy(capture = it.capture.copy(screenText = true)) }
        show()
        node("Abrir accesibilidad").tap()
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, startedActivity()!!.action)
    }

    @Test fun theAdbCommandIsShownWithoutTheSecureSettingsPermission() {
        runtime.state.update { it.copy(capture = it.capture.copy(screenText = true)) }
        show()
        compose.onNodeWithText("adb shell pm grant com.hermesmarcano.zen android.permission.WRITE_SECURE_SETTINGS").assertExists()
    }

    // --- Pausa ---

    @Test fun pausingAndResuming() {
        show()
        node("1 hora").tap()
        assertEquals(now + 3_600_000L, runtime.state.current.capture.pausedUntilMs)
        compose.onNodeWithText("En pausa hasta las", substring = true).assertExists()
        node("Reanudar").tap()
        assertEquals(0L, runtime.state.current.capture.pausedUntilMs)
    }

    @Test fun pausingUntilTomorrowEndsAtMidnight() {
        show()
        node("Hasta mañana").tap()
        assertEquals(runtime.dayClock.nextDayStart(now), runtime.state.current.capture.pausedUntilMs)
    }

    // --- Apps vetadas ---

    @Test fun vetoingAnAppSavesItAndForgetsWhatWasStored() {
        runtime.timeline.insert(LayerEvent("e1", LayerEvent.Kind.SCREEN, "com.whatsapp", "", "algo que ya estaba guardado", 1L))
        show()
        node("Añadir app").tap()
        waitForText("WhatsApp")
        compose.onNodeWithText("Banco").assertDoesNotExist() // las apps sensibles ya están cubiertas: no se ofrecen
        compose.onNodeWithText("WhatsApp").tap()
        assertEquals(setOf("com.whatsapp"), runtime.state.current.capture.denyPackages)
        runtime.io.submit {}.get()
        assertEquals(0, runtime.timeline.count())
        assertTrue(compose.onAllNodesWithText("Quitar").fetchSemanticsNodes().isNotEmpty())
    }

    // --- Límites ---

    @Test fun addingALimitNeedsAnAppAndAReason() {
        show()
        node("Añadir límite").tap()
        compose.onNodeWithText("Nuevo límite").assertIsDisplayed()
        compose.onNodeWithText("Guardar límite").tap()
        compose.onNodeWithText("Elige una app.").assertIsDisplayed()
        compose.onNodeWithText("Elegir app").tap()
        waitForText("TikTok")
        compose.onNodeWithText("TikTok").tap()
        compose.onNodeWithText("Guardar límite").tap()
        compose.onNodeWithText("Pon un límite de minutos o una franja prohibida.").assertIsDisplayed()
    }

    @Test fun aNewLimitAppliesImmediately() {
        show()
        node("Añadir límite").tap()
        compose.onNodeWithText("Elegir app").tap()
        waitForText("TikTok")
        compose.onNodeWithText("TikTok").tap()
        compose.onNodeWithText("App: TikTok").assertIsDisplayed()
        compose.onNodeWithText("Minutos al día (vacío = sin límite)").typeText("45")
        compose.onNodeWithText("Guardar límite").tap()
        val rule = runtime.state.current.limits.single()
        assertEquals(TIKTOK, rule.pkg)
        assertEquals("TikTok", rule.label)
        assertEquals(45 * 60_000L, rule.dailyLimitMs)
        assertFalse(rule.blockAtLimit)
        compose.onNodeWithText("45 min al día (solo avisa)").assertExists()
    }

    @Test fun anInvalidWindowIsRejected() {
        runtime.state.update { it.copy(limits = listOf(LimitRule(TIKTOK, "TikTok", 30 * 60_000L))) }
        show()
        node("Editar").tap()
        // Interruptores de la pantalla: 3 fuentes + «bloquear al llegar» + «franja prohibida» (el último).
        toggles().assertCountEquals(5)
        toggles()[4].tap()
        // Franja con la misma hora de inicio y de fin.
        compose.onNodeWithText("Hasta (hora)").clearText()
        compose.onNodeWithText("Hasta (hora)").typeText("22")
        compose.onNodeWithText("Guardar límite").tap()
        compose.onNodeWithText("La franja necesita dos horas distintas", substring = true).assertIsDisplayed()
    }

    @Test fun looseningALimitIsDeferredByTwentyFourHours() {
        runtime.state.update { it.copy(limits = listOf(LimitRule(TIKTOK, "TikTok", 30 * 60_000L))) }
        show()
        node("Editar").tap()
        compose.onNodeWithText("Minutos al día (vacío = sin límite)").clearText()
        compose.onNodeWithText("Minutos al día (vacío = sin límite)").typeText("120")
        compose.onNodeWithText("Guardar límite").tap()
        assertEquals("el límite estricto sigue vigente", 30 * 60_000L, runtime.state.current.limits.single().dailyLimitMs)
        assertEquals(1, runtime.state.current.pendingRules.size)
        compose.onNodeWithText("Aflojar o quitar un límite tarda 24 horas", substring = true).assertExists()
        node("Cancelar el cambio").tap()
        assertTrue(runtime.state.current.pendingRules.isEmpty())
    }

    @Test fun tighteningALimitIsImmediate() {
        runtime.state.update { it.copy(limits = listOf(LimitRule(TIKTOK, "TikTok", 60 * 60_000L))) }
        show()
        node("Editar").tap()
        compose.onNodeWithText("Minutos al día (vacío = sin límite)").clearText()
        compose.onNodeWithText("Minutos al día (vacío = sin límite)").typeText("20")
        compose.onNodeWithText("Guardar límite").tap()
        assertEquals(20 * 60_000L, runtime.state.current.limits.single().dailyLimitMs)
        assertTrue(runtime.state.current.pendingRules.isEmpty())
    }

    @Test fun removingALimitIsAlsoDeferred() {
        runtime.state.update { it.copy(limits = listOf(LimitRule(TIKTOK, "TikTok", 30 * 60_000L))) }
        show()
        node("Quitar").tap()
        assertEquals(1, runtime.state.current.limits.size)
        assertNull(runtime.state.current.pendingRules.single().rule)
    }

    // --- Bloqueos ---

    @Test fun anActiveBlockCanOnlyBeReleasedAfterTheWaitAndAReason() {
        runtime.state.update {
            it.copy(
                limits = listOf(LimitRule(TIKTOK, "TikTok", 30 * 60_000L, blockAtLimit = true)),
                blocks = listOf(BlockEntry(TIKTOK, now + 3_600_000L, BlockReason.LIMIT)),
            )
        }
        show()
        node("Soltar…").tap()
        compose.onNodeWithText("Soltar TikTok").assertIsDisplayed()
        compose.onNodeWithText("Empezar la espera").tap()
        compose.onNodeWithText("Espera 60 s…").assertIsDisplayed()
        compose.onNodeWithText("Soltar").assertIsNotEnabled()

        now += 61_000
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("¿Por qué lo sueltas?").assertIsDisplayed()
        compose.onNodeWithText("Soltar").assertIsEnabled()

        // Sin motivo, no.
        compose.onNodeWithText("Soltar").tap()
        compose.onNodeWithText("Escribe un motivo", substring = true).assertExists()
        assertTrue(runtime.state.current.passes.isEmpty())

        compose.onNodeWithText("¿Por qué lo sueltas?").typeText("necesito ver el video de mi hermana")
        compose.onNodeWithText("Soltar").tap()
        val pass = runtime.state.current.passes[TIKTOK]
        assertNotNull(pass)
        assertEquals("solo un pase corto, el bloqueo sigue puesto", now + 15 * 60_000L, pass)
        assertEquals(1, runtime.state.current.blocks.size)
    }

    @Test fun keepingTheBlockCancelsTheRequest() {
        runtime.state.update { it.copy(blocks = listOf(BlockEntry(TIKTOK, now + 3_600_000L, BlockReason.MANUAL))) }
        show()
        node("Soltar…").tap()
        compose.onNodeWithText("Empezar la espera").tap()
        assertTrue(runtime.state.current.relaxRequests.containsKey(TIKTOK))
        compose.onNodeWithText("Mantener el bloqueo").tap()
        assertTrue(runtime.state.current.relaxRequests.isEmpty())
        assertTrue(runtime.state.current.passes.isEmpty())
    }

    // --- Respuestas ---

    @Test fun theDraftsSectionIsHiddenWhenThereAreNone() {
        show()
        compose.onNodeWithText("Respuestas por aprobar").assertDoesNotExist()
    }

    private fun repliableDraft(): String {
        val pi = android.app.PendingIntent.getBroadcast(app, 0, Intent("zen.test.REPLY").setPackage(app.packageName), android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Marta").setContentText("hola")
            .addAction(NotificationCompat.Action.Builder(0, "Responder", pi).addRemoteInput(RemoteInput.Builder("k").setAllowFreeFormInput(true).build()).build()).build()
        runtime.replies.put("com.whatsapp|1", "com.whatsapp", "Marta", n)
        return runtime.drafts.propose("com.whatsapp|1", "claro, a las 9")!!.id
    }

    @Test fun approvingADraftSendsItOnlyWhenTheUserTapsSend() {
        repliableDraft()
        show()
        compose.onNodeWithText("Respuestas por aprobar").assertExists()
        compose.onNodeWithText("Para Marta", substring = true).assertExists()
        assertTrue("mostrar el borrador no envía nada", shadowOf(app as Application).broadcastIntents.none { it.action == "zen.test.REPLY" })
        node("Enviar").tap()
        // El borrador se marca como enviado ANTES de enviar (como mucho una vez): hay que esperar al envío real.
        pumpUntil("el envío") { shadowOf(app as Application).broadcastIntents.any { it.action == "zen.test.REPLY" } }
        assertEquals(DraftStatus.SENT, runtime.state.current.drafts.single().status)
        assertEquals(1, shadowOf(app as Application).broadcastIntents.count { it.action == "zen.test.REPLY" })
    }

    @Test fun discardingADraftNeverSends() {
        repliableDraft()
        show()
        node("Descartar").tap()
        assertEquals(DraftStatus.DISCARDED, runtime.state.current.drafts.single().status)
        assertTrue(shadowOf(app as Application).broadcastIntents.none { it.action == "zen.test.REPLY" })
    }

    // --- Memoria ---

    @Test fun theMemoryListsAndSearchesWhatWasStored() {
        runtime.timeline.insert(LayerEvent("e1", LayerEvent.Kind.NOTIFICATION, "com.whatsapp", "Marta", "¿cenamos hoy?", 2_000L))
        runtime.timeline.insert(LayerEvent("e2", LayerEvent.Kind.SCREEN, "com.whatsapp", "", "compra leche y pan", 1_000L))
        show()
        waitForText("¿cenamos hoy?")
        compose.onNodeWithText("compra leche y pan").assertExists()
        node("Buscar en lo recordado").typeText("marta")
        compose.mainClock.advanceTimeBy(400)
        pumpUntil("la búsqueda") { compose.onAllNodesWithText("compra leche y pan").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("¿cenamos hoy?").assertExists()
    }

    @Test fun wipingTheMemoryAsksForConfirmationFirst() {
        runtime.timeline.insert(LayerEvent("e1", LayerEvent.Kind.SCREEN, "com.whatsapp", "", "algo guardado", 1L))
        show()
        waitForText("algo guardado")
        node("Borrar todo…").tap()
        compose.onNodeWithText("¿Borrar toda la memoria?").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").tap()
        assertEquals("cancelar no borra", 1, runtime.timeline.count())
        node("Borrar todo…").tap()
        compose.onNodeWithText("Borrar").tap()
        pumpUntil("el borrado") { runtime.timeline.count() == 0 }
    }

    // --- Cerebro ---

    @Test fun theBrainAddressIsValidatedWithAClearReason() {
        show()
        node("Dirección del cerebro").typeText("http://example.com")
        node("Token").typeText("secreto")
        node("Guardar").tap()
        compose.onNodeWithText("http:// solo se acepta hacia tu propia red", substring = true).assertExists()
        assertEquals("", runtime.state.current.brainUrl)
    }

    @Test fun theBrainNeedsAToken() {
        show()
        node("Dirección del cerebro").typeText("https://brain.example.com")
        node("Guardar").tap()
        compose.onNodeWithText("Falta el token.").assertExists()
        assertEquals("", runtime.state.current.brainUrl)
    }

    @Test fun aValidBrainIsSavedNormalizedAndGetsADeviceId() {
        show()
        node("Dirección del cerebro").typeText("https://Brain.Example.com/")
        node("Token").typeText("  secreto  ")
        node("Guardar").tap()
        assertEquals("https://brain.example.com", runtime.state.current.brainUrl)
        assertEquals("secreto", runtime.state.current.brainToken)
        assertTrue(runtime.state.current.deviceId.isNotBlank())
        assertTrue(runtime.state.current.brainConfigured)
    }

    @Test fun removingTheBrainAlsoTurnsTheMicrophoneOff() {
        runtime.state.update { it.copy(brainUrl = "https://brain.example.com", brainToken = "tok", capture = it.capture.copy(ambientAudio = true), commandCursor = "c") }
        show()
        node("Quitar cerebro").tap()
        val s = runtime.state.current
        assertEquals("", s.brainUrl)
        assertEquals("", s.brainToken)
        assertEquals("", s.commandCursor)
        assertFalse(s.capture.ambientAudio)
    }

    @Test fun syncNowIsOnlyOfferedWithAConfiguredBrain() {
        show()
        node("Sincronizar ahora").assertIsNotEnabled()
        runtime.state.update { it.copy(brainUrl = "https://brain.example.com", brainToken = "tok") }
        compose.mainClock.advanceTimeByFrame()
        node("Sincronizar ahora").assertIsEnabled()
    }

    // --- Ajustes ---

    @Test fun theSettingsScreenLeadsToTheLayer() {
        var opened = false
        compose.setContent { ZenTheme { SettingsScreen(onClose = {}, onOpenLayer = { opened = true }) } }
        compose.onNodeWithText("Capa inteligente").assertIsDisplayed()
        compose.onNodeWithText("Abrir la capa").tap()
        assertTrue(opened)
    }
}
