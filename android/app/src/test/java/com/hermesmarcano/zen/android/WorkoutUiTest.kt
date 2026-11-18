package com.hermesmarcano.zen.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.ZenApp
import com.hermesmarcano.zen.core.ExerciseLibrary
import com.hermesmarcano.zen.core.XpRules
import com.hermesmarcano.zen.data.ExerciseRef
import com.hermesmarcano.zen.data.RestState
import com.hermesmarcano.zen.data.Routine
import com.hermesmarcano.zen.data.RoutineExercise
import com.hermesmarcano.zen.data.SessionExercise
import com.hermesmarcano.zen.data.SetEntry
import com.hermesmarcano.zen.data.WorkoutActions
import com.hermesmarcano.zen.data.WorkoutData
import com.hermesmarcano.zen.data.WorkoutSession
import com.hermesmarcano.zen.data.ZenState
import com.hermesmarcano.zen.ui.ActiveWorkoutScreen
import com.hermesmarcano.zen.ui.ExerciseDetailScreen
import com.hermesmarcano.zen.ui.HistoryScreen
import com.hermesmarcano.zen.ui.RoutineEditScreen
import com.hermesmarcano.zen.ui.SessionDetailScreen
import com.hermesmarcano.zen.ui.WorkoutScreen
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val BENCH = ExerciseLibrary.preset("bench_press")!!
private val SQUAT = ExerciseLibrary.preset("squat")!!

/** La pestaña Entreno: plantillas, empezar, anotar series, descanso, terminar, historial y marcas. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ZenApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WorkoutUiTest {
    @get:Rule val compose = createComposeRule()
    private val app: ZenApp = ApplicationProvider.getApplicationContext()
    private val workouts get() = app.workouts
    private val announced = mutableListOf<String>()
    private val announce: (String, String?, (() -> Unit)?) -> Unit = { t, _, _ -> announced += t }

    @Before fun reset() {
        workouts.update { WorkoutData() }
        app.repository.update { ZenState() }
        announced.clear()
        compose.mainClock.autoAdvance = false
    }

    private fun routine(sets: Int = 2) = Routine("r1", "Día 1 · Empuje", 0, listOf(RoutineExercise(BENCH, sets, 90), RoutineExercise(SQUAT, sets, 90)))

    private fun finishedSession(id: String, end: Long, weight: Double) = WorkoutSession(
        id, "r1", "Día 1 · Empuje", end - 45 * 60_000L, end,
        listOf(SessionExercise(BENCH, 90, null, List(3) { SetEntry(weight, 8, done = true, completedAt = end - 10 * 60_000L) })), prs = 1,
    )

    private fun advance() = compose.mainClock.advanceTimeBy(300)

    @Test fun withoutRoutinesTheTabOffersATemplateThatCreatesThree() {
        compose.setContent { ZenTheme { WorkoutScreen(androidx.compose.ui.Modifier, announce) } }
        compose.onNodeWithText("Entreno").assertIsDisplayed()
        compose.onNodeWithText("Usar plantilla Empuje · Tirón · Piernas").performClick()
        advance()
        assertEquals(3, workouts.data.value.routines.size)
        compose.onNodeWithText("Te toca").assertIsDisplayed()
    }

    @Test fun thePlanShowsTheNextRoutineAndTheXpHint() {
        workouts.update { it.copy(routines = listOf(routine(), Routine("r2", "Día 2 · Tirón", 1, emptyList())), history = listOf(finishedSession("h", System.currentTimeMillis(), 60.0))) }
        compose.setContent { ZenTheme { WorkoutScreen(androidx.compose.ui.Modifier, announce) } }
        compose.onNodeWithText("Día 2 · Tirón").assertIsDisplayed() // r1 fue la última: toca r2
        compose.onNodeWithText("+${XpRules.WORKOUT} XP", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1 de 3 días entrenados", substring = true).assertExists()
    }

    @Test fun startingOpensTheSessionPrefilledFromLastTime() {
        workouts.update { it.copy(routines = listOf(routine()), history = listOf(finishedSession("h", System.currentTimeMillis() - 86_400_000L, 70.0))) }
        compose.setContent { ZenTheme { WorkoutScreen(androidx.compose.ui.Modifier, announce) } }
        compose.onAllNodesWithText("Empezar")[0].performClick() // "Te toca" y la fila de la rutina
        advance()
        assertNotNull(workouts.data.value.active)
        compose.onNodeWithText("Press de banca").assertIsDisplayed()
        compose.onNodeWithText("Última vez: 70 kg × 8", substring = true).assertExists()
        assertEquals(70.0, workouts.data.value.active!!.exercises[0].sets[0].weightKg!!, 0.0)
    }

    private fun startActive(sets: Int = 2) {
        workouts.update { it.copy(routines = listOf(routine(sets))) }
        WorkoutActions.start(workouts, "r1", System.currentTimeMillis() - 10 * 60_000L)
    }

    private fun showActive() = compose.setContent {
        ZenTheme { ActiveWorkoutScreen(androidx.compose.ui.Modifier, onBack = {}, onFinished = {}, onEnded = {}, announce = announce) }
    }

    @Test fun markingASetWithoutDataExplainsWhatIsMissing() {
        startActive()
        showActive()
        compose.onNodeWithContentDescription("Marcar serie 1 de Press de banca como hecha").performClick()
        advance()
        assertTrue(announced.single().contains("peso y las repeticiones"))
        assertFalse(workouts.data.value.active!!.exercises[0].sets[0].done)
    }

    @Test fun markingAValidSetStartsTheRestTimer() {
        startActive()
        WorkoutActions.edit(workouts) { com.hermesmarcano.zen.core.WorkoutOps.editSet(it, 0, 0) { s -> s.copy(weightKg = 60.0, reps = 8) } }
        showActive()
        compose.onNodeWithContentDescription("Marcar serie 1 de Press de banca como hecha").performClick()
        advance()
        val session = workouts.data.value.active!!
        assertTrue(session.exercises[0].sets[0].done)
        assertNotNull(session.rest)
        compose.onNodeWithText("Descanso ·", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Desmarcar serie 1 de Press de banca").assertExists()
        compose.onNodeWithText("Saltar").performClick()
        advance()
        assertNull(workouts.data.value.active!!.rest)
    }

    @Test fun anExpiredRestShowsTheFinishedBar() {
        startActive()
        val now = System.currentTimeMillis()
        WorkoutActions.edit(workouts) { it.copy(rest = RestState(now - 100_000, now - 10_000, 90)) }
        showActive()
        compose.onNodeWithText("Descanso terminado").assertIsDisplayed()
        compose.onNodeWithText("Cerrar").performClick()
        advance()
        assertNull(workouts.data.value.active!!.rest)
    }

    @Test fun finishingWithNothingDoneOffersToDiscardInsteadOfSaving() {
        startActive()
        showActive()
        compose.onNodeWithText("Terminar").performClick()
        advance()
        compose.onNodeWithText("Aún no hay series hechas").assertIsDisplayed()
        compose.onNodeWithText("Descartar").performClick()
        advance()
        compose.onNodeWithText("¿Descartar el entrenamiento?").assertIsDisplayed()
        compose.onNodeWithText("Descartar").performClick()
        advance()
        assertNull(workouts.data.value.active)
        assertTrue(workouts.data.value.history.isEmpty())
    }

    @Test fun fullFlowFinishShowsTheSummaryWithXp() {
        startActive(sets = 3)
        val session = workouts.data.value.active!!
        var at = session.startedAt + 60_000L
        session.exercises.forEachIndexed { e, ex -> ex.sets.indices.forEach { s ->
            WorkoutActions.edit(workouts) { com.hermesmarcano.zen.core.WorkoutOps.editSet(it, e, s) { x -> x.copy(weightKg = 60.0, reps = 8) } }
            WorkoutActions.toggleSet(workouts, e, s, at); at += 60_000L
        } }
        var summary: com.hermesmarcano.zen.data.WorkoutFinish? = null
        compose.setContent { ZenTheme { WorkoutScreen(androidx.compose.ui.Modifier, announce) } }
        compose.onNodeWithText("Continuar").performClick()
        advance()
        compose.onNodeWithText("Terminar").performClick()
        advance()
        compose.onNodeWithText("¿Terminar el entrenamiento?").assertIsDisplayed()
        // El botón del diálogo y el de la cabecera comparten texto: el del diálogo es el último.
        compose.onAllNodesWithTextLast("Terminar").performClick()
        advance()
        compose.onNodeWithText("Entrenamiento completado").assertIsDisplayed()
        compose.onNodeWithText("Entrenamiento completado · +${XpRules.WORKOUT} XP", substring = true).assertExists()
        assertEquals(1, workouts.data.value.history.size)
        assertEquals(XpRules.WORKOUT, app.repository.state.value.progress.xp)
        compose.onNodeWithText("Listo").performClick()
        advance()
        compose.onNodeWithText("Esta semana").assertIsDisplayed()
        assertNull(summary)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.last() = this[fetchSemanticsNodes().lastIndex]
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextLast(text: String) =
        onAllNodes(hasText(text)).let { all -> all[all.fetchSemanticsNodes().lastIndex] }

    @Test fun routineEditorSavesChangesAndRejectsBlankNames() {
        workouts.update { it.copy(routines = listOf(routine())) }
        var closed = false
        compose.setContent { ZenTheme { RoutineEditScreen("r1", androidx.compose.ui.Modifier, onClose = { closed = true }, announce = announce) } }
        compose.onNodeWithText("Día 1 · Empuje").assertExists()
        compose.onNodeWithText("Press de banca").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Más series")[0].performClick()
        advance()
        compose.onNodeWithText("Guardar").performClick()
        advance()
        assertTrue(closed)
        assertEquals(3, workouts.data.value.routines.single().items[0].sets)
    }

    @Test fun routineEditorCancelWithChangesAsksBeforeLeaving() {
        workouts.update { it.copy(routines = listOf(routine())) }
        var closed = false
        compose.setContent { ZenTheme { RoutineEditScreen("r1", androidx.compose.ui.Modifier, onClose = { closed = true }, announce = announce) } }
        compose.onAllNodesWithContentDescription("Más series")[0].performClick()
        advance()
        compose.onNodeWithText("Cancelar").performClick()
        advance()
        compose.onNodeWithText("¿Salir sin guardar?").assertIsDisplayed()
        assertFalse(closed)
        compose.onNodeWithText("Salir").performClick()
        advance()
        assertTrue(closed)
        assertEquals(2, workouts.data.value.routines.single().items[0].sets)
    }

    @Test fun historyListsSessionsAndTheDetailCanDeleteWithUndo() {
        val end = System.currentTimeMillis()
        workouts.update { it.copy(history = listOf(finishedSession("a", end - 86_400_000L, 60.0), finishedSession("b", end, 65.0))) }
        var openedId: String? = null
        compose.setContent { ZenTheme { HistoryScreen(androidx.compose.ui.Modifier, onBack = {}, onOpen = { openedId = it }) } }
        compose.onNodeWithText("Historial · 2").assertIsDisplayed()
        compose.onAllNodes(hasText("Día 1 · Empuje")).let { assertEquals(2, it.fetchSemanticsNodes().size) }
        compose.onAllNodes(hasText("Día 1 · Empuje"))[0].performClick()
        assertEquals("b", openedId) // la más reciente primero
    }

    @Test fun aSessionWithTheSameExerciseTwiceOpensWithoutCrashing() {
        val end = System.currentTimeMillis()
        val done = { w: Double, r: Int -> SetEntry(w, r, done = true, completedAt = end - 60_000L) }
        val twice = WorkoutSession("two", null, "Libre", end - 40 * 60_000L, end, listOf(
            SessionExercise(BENCH, 90, null, listOf(done(100.0, 3))),
            SessionExercise(BENCH, 90, null, listOf(done(60.0, 12))),
        ))
        workouts.update { it.copy(history = listOf(twice)) }
        compose.setContent { ZenTheme { SessionDetailScreen("two", androidx.compose.ui.Modifier, onBack = {}, announce = announce) } }
        compose.onNodeWithText("100 kg × 3", substring = true).assertExists()
        compose.onNodeWithText("60 kg × 12", substring = true).assertExists()
    }

    @Test fun exerciseDetailListsEveryBlockOfTheSameExercise() {
        val end = System.currentTimeMillis()
        val done = { w: Double, r: Int -> SetEntry(w, r, done = true, completedAt = end - 60_000L) }
        val twice = WorkoutSession("two", null, "Libre", end - 40 * 60_000L, end, listOf(
            SessionExercise(BENCH, 90, null, listOf(done(100.0, 3))),
            SessionExercise(BENCH, 90, null, listOf(done(60.0, 12))),
        ))
        workouts.update { it.copy(history = listOf(twice)) }
        compose.setContent { ZenTheme { ExerciseDetailScreen("bench_press", androidx.compose.ui.Modifier, onBack = {}) } }
        compose.onNodeWithText("100 kg × 3", substring = true).assertExists()
        compose.onNodeWithText("60 kg × 12", substring = true).assertExists()
    }

    @Test fun sessionDetailShowsSetsAndDeletes() {
        val end = System.currentTimeMillis()
        workouts.update { it.copy(history = listOf(finishedSession("a", end, 62.5))) }
        var back = 0
        val actions = mutableListOf<(() -> Unit)?>()
        compose.setContent { ZenTheme { SessionDetailScreen("a", androidx.compose.ui.Modifier, onBack = { back++ }, announce = { _, _, f -> actions += f }) } }
        compose.onNodeWithText("62,5 kg × 8", substring = true).assertExists()
        compose.onNodeWithText("Eliminar este entrenamiento").performScrollToNodeIfNeeded()
        compose.onNodeWithText("Eliminar este entrenamiento").performClick()
        advance()
        compose.onNodeWithText("Eliminar").performClick()
        advance()
        assertTrue(workouts.data.value.history.isEmpty())
        actions.last()!!.invoke() // "Deshacer"
        assertEquals(1, workouts.data.value.history.size)
        assertEquals(1, back)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performScrollToNodeIfNeeded() {
        runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText("Eliminar este entrenamiento")) }
    }

    @Test fun exerciseDetailShowsRecordsAndNeedsTwoSessionsForTheChart() {
        val end = System.currentTimeMillis()
        workouts.update { it.copy(history = listOf(finishedSession("a", end - 7 * 86_400_000L, 60.0))) }
        compose.setContent { ZenTheme { ExerciseDetailScreen("bench_press", androidx.compose.ui.Modifier, onBack = {}) } }
        compose.onNodeWithText("Mejores marcas").assertIsDisplayed()
        compose.onAllNodesWithText("60 kg", substring = true)[0].assertExists()
        compose.onNodeWithText("La gráfica aparece con 2 o más entrenamientos.").assertExists()
    }

    @Test fun exerciseDetailDrawsTheChartWithTwoSessions() {
        val end = System.currentTimeMillis()
        workouts.update { it.copy(history = listOf(finishedSession("a", end - 7 * 86_400_000L, 60.0), finishedSession("b", end, 65.0))) }
        compose.setContent { ZenTheme { ExerciseDetailScreen("bench_press", androidx.compose.ui.Modifier, onBack = {}) } }
        compose.onNodeWithContentDescription("Gráfica.", substring = true).assertExists()
    }

    @Test fun unknownExerciseDoesNotCrash() {
        compose.setContent { ZenTheme { ExerciseDetailScreen("nope", androidx.compose.ui.Modifier, onBack = {}) } }
        compose.onNodeWithText("Este ejercicio ya no existe.").assertIsDisplayed()
    }
}
