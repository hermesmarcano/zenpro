package com.hermesmarcano.zen.layer.android

import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.alarm.AlarmRuntime
import com.hermesmarcano.zen.data.ZenState
import com.hermesmarcano.zen.layer.capture.LayerLive
import com.hermesmarcano.zen.layer.capture.layer
import com.hermesmarcano.zen.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cómo se llega a la capa desde la app: por Ajustes, y directamente desde un aviso (la ruta `layer`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ZenApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LayerNavigationTest {
    init { useSoftwareSecrets() }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app: ZenApp = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        AlarmRuntime.end()
        LayerLive.audioRunning = false
        app.repository.update { ZenState() }
        rule.mainClock.autoAdvance = false
    }

    @After fun tearDown() = AlarmRuntime.end()

    private fun tap(label: String) {
        rule.onNode(hasText(label) and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(400)
    }

    @Test fun theLayerIsReachedFromSettingsAndReturnsToIt() {
        rule.mainClock.advanceTimeBy(400)
        tap("Alarmas")
        tap("Ajustes")
        rule.onNodeWithText("Capa inteligente").assertExists()
        tap("Abrir la capa")
        rule.onNodeWithText("Todo empieza apagado", substring = true).assertExists()
        tap("Listo")
        // "Listo" en la capa vuelve a la pestaña desde la que se abrieron los ajustes.
        rule.onNodeWithText("Nueva alarma").assertExists()
    }

    @Test fun openingTheAppDoesNotStartTheMicrophoneUnlessItShouldBeListening() {
        // Sin micrófono pedido, abrir ZEN no arranca ningún servicio.
        rule.activityRule.scenario.onActivity { }
        assertNull(shadowOf(app as android.app.Application).nextStartedService)
    }

    @Test fun openingTheAppResumesTheMicrophoneWhenItWasWantedAndStoppedByTheSystem() {
        app.layer.state.update {
            it.copy(brainUrl = "https://brain.example.com", brainToken = "tok", capture = it.capture.copy(ambientAudio = true))
        }
        shadowOf(app as android.app.Application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        LayerLive.audioRunning = false
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        val started = shadowOf(app as android.app.Application).nextStartedService
        assertEquals("com.hermesmarcano.zen.layer.capture.AmbientAudioService", started?.component?.className)
    }
}

/** Una notificación de la capa abre ZEN con la ruta `layer`: llega directa a la pantalla, sin pasar por Ajustes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ZenApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LayerRouteTest {
    init { useSoftwareSecrets() }

    @get:Rule val rule = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val app: ZenApp = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        AlarmRuntime.end()
        app.repository.update { ZenState() }
    }

    @After fun tearDown() = AlarmRuntime.end()

    @Test fun theLayerRouteOpensTheLayerScreenDirectly() {
        val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_LAYER)
        androidx.test.core.app.ActivityScenario.launch<MainActivity>(intent).use {
            rule.mainClock.autoAdvance = false
            rule.mainClock.advanceTimeBy(400)
            rule.onNodeWithText("Todo empieza apagado", substring = true).assertExists()
        }
    }

    @Test fun anUnknownRouteFallsBackToToday() {
        val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "no-existe")
        androidx.test.core.app.ActivityScenario.launch<MainActivity>(intent).use {
            rule.mainClock.autoAdvance = false
            rule.mainClock.advanceTimeBy(400)
            rule.onNodeWithText("¿Qué es lo más importante hoy?").assertExists()
        }
    }
}
