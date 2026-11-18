package com.hermesmarcano.zen.android

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.alarm.AlarmRuntime
import com.hermesmarcano.zen.data.ZenState
import com.hermesmarcano.zen.ui.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La app completa: pestañas inferiores, ajustes, viaje y vuelta con "atrás". Con una actividad real de
 * Robolectric la ventana mide la pantalla por defecto del simulador, así que se comprueba que los
 * elementos existen (la visibilidad se prueba en las pruebas de cada pantalla, que sí fijan el tamaño).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ZenApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AppNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val app: ZenApp = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        AlarmRuntime.end()
        app.repository.update { ZenState() }
        // Hay relojes en bucle en varias pantallas: el tiempo de la prueba se avanza a mano.
        rule.mainClock.autoAdvance = false
    }

    @After fun tearDown() = AlarmRuntime.end()

    private fun back() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        // El cambio de estado se hizo fuera del control de la prueba: se deja correr el bucle principal
        // para que Compose se entere, y luego se avanza el reloj de la prueba.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        rule.mainClock.advanceTimeBy(400)
    }

    private fun tap(label: String) {
        rule.onNode(hasText(label) and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(400)
    }

    @Test fun opensOnTodayAndMovesBetweenTabs() {
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("¿Qué es lo más importante hoy?").assertExists()

        tap("Alarmas")
        rule.onNodeWithText("Nueva alarma").assertExists()
        rule.onNodeWithText("Modo viaje").assertExists()

        tap("Progreso")
        rule.onNodeWithText("Nivel 1").assertExists()

        back() // atrás desde una pestaña vuelve a Hoy
        rule.onNodeWithText("¿Qué es lo más importante hoy?").assertExists()
    }

    @Test fun theWorkoutTabIsReachableAndTodayLinksToTheNextRoutine() {
        app.workouts.update { com.hermesmarcano.zen.data.WorkoutData() }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("Te toca entrenar").assertDoesNotExist() // sin rutinas, Hoy no insiste
        tap("Entreno")
        rule.onNodeWithText("Usar plantilla Empuje · Tirón · Piernas").assertExists()
        rule.onNodeWithText("Esta semana").assertExists()
        back()
        rule.onNodeWithText("¿Qué es lo más importante hoy?").assertExists()

        app.workouts.update { it.copy(routines = listOf(com.hermesmarcano.zen.data.Routine("r1", "Día 1 · Empuje", 0, emptyList()))) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("Te toca entrenar").assertExists()
        tap("Ver")
        rule.onNodeWithText("Te toca").assertExists()
    }

    @Test fun settingsReturnToTheTabTheyWereOpenedFrom() {
        tap("Alarmas")
        tap("Ajustes")
        rule.onNodeWithText("Sonido de la alarma").assertExists()
        tap("Listo")
        rule.onNodeWithText("Nueva alarma").assertExists()
    }

    @Test fun tripScreenOpensFromAlarmsAndBackReturnsThere() {
        tap("Alarmas")
        rule.onNodeWithText("Modo viaje").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("Pausar mis alarmas").assertExists()
        back()
        rule.onNodeWithText("Nueva alarma").assertExists()
    }

    @Test fun settingsOpenFromTheGearAndCloseBackToToday() {
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithContentDescription("Ajustes").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("Sonido de la alarma").assertExists()
        tap("Listo")
        rule.onNodeWithText("¿Qué es lo más importante hoy?").assertExists()
    }

    @Test fun theBottomBarDisappearsOnSubScreens() {
        tap("Alarmas")
        rule.onNodeWithText("Nueva alarma").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("Guardar y activar").assertExists()
        rule.onAllNodesWithTextCount("Progreso", 0)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String, expected: Int) {
        val found = onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().size
        check(found == expected) { "esperaba $expected nodos '$text' pulsables y hay $found" }
    }
}
