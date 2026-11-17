package com.hermesmarcano.zen.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.core.TaskBoard
import com.hermesmarcano.zen.data.TaskActions
import com.hermesmarcano.zen.data.ZenState
import com.hermesmarcano.zen.ui.TodayScreen
import com.hermesmarcano.zen.ui.ZenTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** La pantalla "Hoy": añadir, prioridades por posición, completar, eliminar con deshacer, reordenar y planificar mañana. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ZenApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TasksUiTest {
    @get:Rule val compose = createComposeRule()
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val repo get() = app.repository
    private val today = LocalDate.now()
    private val day = today.toEpochDay()
    private val announced = mutableListOf<Triple<String, String?, (() -> Unit)?>>()

    @Before fun reset() {
        repo.update { ZenState() }
        announced.clear()
    }

    private fun seed(vararg titles: String, forDay: Long = day) = titles.forEach { TaskActions.add(repo, forDay, it) }
    private fun titles(forDay: Long = day) = TaskBoard.forDay(repo.state.value.tasks, forDay).map { it.title }

    /** Los relojes en bucle de la pantalla piden avanzar a mano; desplazarse sí necesita fotogramas. */
    private fun show() {
        compose.setContent {
            ZenTheme { TodayScreen(androidx.compose.ui.Modifier, onOpenSettings = {}, announce = { t, a, f -> announced += Triple(t, a, f) }) }
        }
    }

    @Test fun theTodayTabWarnsAboutMissingPermissionsAndLeadsToAlarms() {
        var opened = false
        compose.setContent {
            ZenTheme { TodayScreen(androidx.compose.ui.Modifier, onOpenSettings = {}, announce = { _, _, _ -> }, onOpenAlarms = { opened = true }) }
        }
        compose.onNodeWithText("Faltan permisos para que la alarma funcione bien.").assertIsDisplayed()
        compose.onNodeWithText("Revisar").performClick()
        assertTrue(opened)
    }

    @Test fun withoutAlarmsTheSummaryOffersToCreateOne() {
        var opened = false
        compose.setContent {
            ZenTheme { TodayScreen(androidx.compose.ui.Modifier, onOpenSettings = {}, announce = { _, _, _ -> }, onOpenAlarms = { opened = true }) }
        }
        compose.onNodeWithText("Crear mi primera alarma").performClick()
        assertTrue(opened)
    }

    @Test fun emptyDayInvitesToChooseTheMostImportantThing() {
        show()
        compose.onNodeWithText("Tu día empieza aquí", substring = true).assertIsDisplayed()
        compose.onNodeWithText("¿Qué es lo más importante hoy?").assertIsDisplayed()
        compose.onNodeWithText("0/10 · +30 XP al completarla").assertIsDisplayed()
    }

    @Test fun addingTasksFillsPriorityFromTheTop() {
        show()
        compose.onNodeWithText("¿Qué es lo más importante hoy?").performTextInput("Escribir el informe")
        compose.onNodeWithContentDescription("Añadir tarea").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Escribir el informe"), titles())
        compose.onNodeWithText("Prioritaria · +30 XP").assertIsDisplayed()
        // La siguiente entra como media y el campo lo avisa.
        compose.onNodeWithText("Nueva tarea · entra como media").performTextInput("Llamar")
        compose.onNodeWithContentDescription("Añadir tarea").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Escribir el informe", "Llamar"), titles())
    }

    @Test fun eachSlotShowsItsPriorityAndXp() {
        seed("a", "b", "c", "d", "e")
        show()
        assertEquals(1, compose.onAllNodesWithText("Prioritaria · +30 XP").fetchSemanticsNodes().size)
        assertEquals(3, compose.onAllNodesWithText("Media · +15 XP").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("Baja · +5 XP").fetchSemanticsNodes().size)
    }

    @Test fun completingATaskPaysXpAndAnnouncesIt() {
        seed("Importante", "Otra")
        show()
        compose.onNodeWithContentDescription("Marcar como hecha: Importante").performClick()
        compose.waitForIdle()
        assertTrue(repo.state.value.tasks.first { it.title == "Importante" }.done)
        assertEquals(30, repo.state.value.progress.xp)
        assertTrue(announced.any { it.first.contains("Prioritaria cumplida") && it.first.contains("+30 XP") })
        compose.onNodeWithText("Hecha").assertIsDisplayed()
    }

    @Test fun deletingOffersUndoAndUndoBringsItBack() {
        seed("A", "B", "C")
        show()
        compose.onNodeWithContentDescription("Más acciones de B").performClick()
        compose.onNodeWithText("Eliminar").performClick()
        compose.waitForIdle()
        assertEquals(listOf("A", "C"), titles())
        val undo = announced.last()
        assertEquals("Deshacer", undo.second)
        undo.third!!.invoke()
        compose.waitForIdle()
        assertEquals(listOf("A", "B", "C"), titles())
    }

    @Test fun menuMovesATaskDownAndUp() {
        seed("A", "B", "C")
        show()
        compose.onNodeWithContentDescription("Más acciones de A").performClick()
        compose.onNodeWithText("Bajar").performClick()
        compose.waitForIdle()
        assertEquals(listOf("B", "A", "C"), titles())
        compose.onNodeWithContentDescription("Más acciones de A").performClick()
        compose.onNodeWithText("Subir").performClick()
        compose.waitForIdle()
        assertEquals(listOf("A", "B", "C"), titles())
    }

    @Test fun aFullListBlocksMoreTasksAndSaysSo() {
        seed(*Array(10) { "t$it" })
        show()
        compose.onNodeWithText("Lista llena").assertIsDisplayed()
        compose.onNodeWithText("10/10").assertIsDisplayed()
    }

    @Test fun tomorrowIsForPlanningNotForCompleting() {
        seed("Mañana1", "Mañana2", forDay = day + 1)
        show()
        compose.onNodeWithText("Mañana").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Mañana1").assertIsDisplayed()
        compose.onAllNodesWithText("Prioritaria · se completa mañana")[0].assertIsDisplayed()
        compose.onNodeWithContentDescription("Marcar como hecha: Mañana1").assertIsNotEnabled()
        // Se puede planificar: añadir a mañana.
        compose.onNodeWithText("Nueva tarea · entra como media").performTextInput("Preparar maleta")
        compose.onNodeWithContentDescription("Añadir tarea").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Mañana1", "Mañana2", "Preparar maleta"), titles(day + 1))
        assertTrue(titles(day).isEmpty())
    }

    @Test fun pendingTasksFromYesterdayCanBeBroughtToToday() {
        seed("Ayer 1", "Ayer 2", forDay = day - 1)
        show()
        compose.onNodeWithText("Te quedaron 2 tareas pendientes de días anteriores.").assertIsDisplayed()
        compose.onNodeWithText("Traer a hoy").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Ayer 1", "Ayer 2"), titles())
        assertTrue(announced.last().first.contains("Traje 2"))
    }

    @Test fun theDragHintAppearsOnceAndCanBeDismissed() {
        seed("A", "B")
        show()
        compose.onNodeWithText("Mantén pulsado", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Entendido").performClick()
        compose.waitForIdle()
        assertTrue(repo.state.value.settings.taskHintSeen)
        compose.onNodeWithText("Mantén pulsado", substring = true).assertDoesNotExist()
    }

    @Test fun summaryShowsProgressAndLevel() {
        seed("A", "B")
        TaskActions.setDone(repo, repo.state.value.tasks.first().id, true, today)
        show()
        compose.onNodeWithText("1 de 2 hechas").assertIsDisplayed()
        compose.onNodeWithText("1/2").assertIsDisplayed()
        compose.onNodeWithText("Nivel 1 · Despertando").assertIsDisplayed()
        compose.onNodeWithText("Prioritaria cumplida. Lo importante ya está hecho.").assertIsDisplayed()
        compose.onNodeWithText("Racha de prioritaria: 1 día").assertIsDisplayed()
    }

    /** Arrastre real con el gesto (mantener pulsado el asa y mover): comprueba que se reordena y se guarda al soltar. */
    @Test fun draggingTheHandleReordersAndSaves() {
        seed("A", "B", "C")
        show()
        val handles = compose.onAllNodesWithContentDescription("Arrastrar para reordenar")
        handles[0].performTouchInput {
            down(center)
            advanceEventTime(700) // pulsación larga
            moveBy(Offset(0f, 40f)); advanceEventTime(50)
            moveBy(Offset(0f, 120f)); advanceEventTime(50)
            moveBy(Offset(0f, 120f)); advanceEventTime(50)
            up()
        }
        compose.waitForIdle()
        // Arrastrar la primera hacia abajo la deja en medio, y el orden queda guardado (sobrevive a recargar).
        assertEquals(listOf("B", "A", "C"), titles())
        val reloaded = com.hermesmarcano.zen.data.StateRepository(java.io.File(app.filesDir, "state.json")).state.value
        assertEquals(listOf("B", "A", "C"), TaskBoard.forDay(reloaded.tasks, day).map { it.title })
    }
}
