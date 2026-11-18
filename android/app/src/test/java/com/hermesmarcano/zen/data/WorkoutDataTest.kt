package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.Achievement
import com.hermesmarcano.zen.core.ExerciseLibrary
import com.hermesmarcano.zen.core.GameMessages
import com.hermesmarcano.zen.core.Levels
import com.hermesmarcano.zen.core.WorkoutCsv
import com.hermesmarcano.zen.core.WorkoutOps
import com.hermesmarcano.zen.core.XpRules
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val MIN = 60_000L
private const val DAY = 24 * 60 * MIN
private val BENCH = ExerciseLibrary.preset("bench_press")!!
private val SQUAT = ExerciseLibrary.preset("squat")!!
private val PLANK = ExerciseLibrary.preset("plank")!!

private fun done(w: Double?, r: Int?, at: Long = 1L) = SetEntry(weightKg = w, reps = r, done = true, completedAt = at)

class WorkoutRepositoryTest {
    private val dir: File = Files.createTempDirectory("zen-workouts").toFile()
    private val main = File(dir, "workouts.json")
    private val active = File(dir, "active_workout.json")

    private fun repo() = WorkoutRepository(main, active)

    private val routine = Routine("r1", "Día 1 · Empuje", 0, listOf(RoutineExercise(BENCH, 4, 120, 1), RoutineExercise(SQUAT, 3, 90, 1), RoutineExercise(PLANK, 2, 30)))

    private fun sessionWith(id: String, end: Long) = WorkoutSession(
        id, "r1", "Día 1 · Empuje", end - 40 * MIN, end,
        listOf(
            SessionExercise(BENCH, 120, 1, listOf(SetEntry(62.5, 8, null, SetType.NORMAL, 8.5, true, end - 5 * MIN, 95), SetEntry(20.0, 12, null, SetType.WARMUP, null, true, end - 10 * MIN, null)), "agarre ancho"),
            SessionExercise(PLANK, 30, 1, listOf(SetEntry(null, null, 45, SetType.NORMAL, null, true, end - MIN, 30))),
        ),
        note = "buen día, \"sin dolor\"\ny con coma, vale", prs = 2,
    )

    @Test fun missingFilesStartEmpty() = assertEquals(WorkoutData(), repo().data.value)

    @Test fun theHistoryIsOnlyReadWhenSomeoneAsksForIt() {
        main.writeText("{no es json")
        val r = repo() // crear el repositorio (arranque de la app o de un receptor) no lee ni aparta nada
        assertTrue(main.exists())
        assertFalse(File(dir, "workouts.json.corrupt").exists())
        assertEquals(WorkoutData(), r.data.value)
        assertTrue(File(dir, "workouts.json.corrupt").exists())
    }

    @Test fun roundTripKeepsEverything() {
        val custom = ExerciseRef("custom:abc", "Remo \"pendlay\", pesado", MuscleGroup.BACK, ExerciseKind.WEIGHT_REPS)
        val r = repo()
        r.update { it.copy(routines = listOf(routine), customExercises = listOf(custom), history = listOf(sessionWith("s1", 10 * DAY)), weeklyGoal = 4, defaultRestSeconds = 75, barKg = 15.0) }
        val loaded = repo().data.value
        assertEquals(listOf(routine), loaded.routines)
        assertEquals(listOf(custom), loaded.customExercises)
        assertEquals(listOf(sessionWith("s1", 10 * DAY)), loaded.history)
        assertEquals(4, loaded.weeklyGoal); assertEquals(75, loaded.defaultRestSeconds); assertEquals(15.0, loaded.barKg, 0.0)
    }

    @Test fun activeSessionLivesInItsOwnSmallFile() {
        val r = repo()
        r.update { it.copy(history = listOf(sessionWith("s1", 10 * DAY))) }
        val mainBefore = main.lastModified()
        val mainText = main.readText()
        val live = WorkoutSession("live", null, "Libre", 1000L, null, listOf(SessionExercise(BENCH, 90, null, listOf(SetEntry(60.0, 8)))), rest = RestState(1000L, 91_000L, 90))
        Thread.sleep(15)
        r.update { it.copy(active = live) }
        assertEquals("marcar series no reescribe el historial", mainText, main.readText())
        assertEquals(mainBefore, main.lastModified())
        assertTrue(active.exists())
        assertEquals(live, repo().data.value.active)
        r.update { it.copy(active = null) }
        assertFalse(active.exists())
        assertNull(repo().data.value.active)
    }

    @Test fun restSurvivesARestart() {
        val live = WorkoutSession("live", null, "Libre", 1000L, null, listOf(SessionExercise(BENCH, 90, null, listOf(SetEntry(60.0, 8, done = true, completedAt = 5L)))), rest = RestState(5L, 90_005L, 90))
        repo().update { it.copy(active = live) }
        assertEquals(90_005L, repo().data.value.active!!.rest!!.endsAt)
    }

    @Test fun crashBetweenSavingHistoryAndClosingTheActiveFileDoesNotDuplicate() {
        val s = sessionWith("dup", 10 * DAY)
        val r = repo()
        r.update { it.copy(history = listOf(s)) }
        active.writeText(WorkoutJson.encodeSession(s.copy(finishedAt = null))) // la sesión en curso sobrevivió al cierre
        val loaded = repo().data.value
        assertNull(loaded.active)
        assertEquals(1, loaded.history.size)
        assertFalse("el archivo sobrante se limpia", active.exists())
    }

    @Test fun corruptFilesAreSetAside() {
        main.writeText("{no es json")
        active.writeText("tampoco")
        val loaded = repo().data.value
        assertEquals(WorkoutData(), loaded)
        assertTrue(File(dir, "workouts.json.corrupt").exists())
        assertTrue(File(dir, "active_workout.json.corrupt").exists())
    }

    @Test fun writeFailureDoesNotChangeMemory() {
        val r = repo()
        r.update { it.copy(weeklyGoal = 5) }
        // Un directorio donde debería ir el archivo temporal impide escribir.
        File(dir, "workouts.json.tmp").mkdirs()
        try {
            r.update { it.copy(weeklyGoal = 6) }
            fail("debía lanzar")
        } catch (_: IOException) {
        }
        assertEquals(5, r.data.value.weeklyGoal)
    }

    @Test fun hostileValuesAreClampedOnLoad() {
        main.writeText(
            """{"version":1,"weeklyGoal":99,"defaultRest":-4,"barKg":"x","custom":[{"id":"nope","name":"x"},{"id":"custom:1","name":"  Mío  ","muscle":"??","kind":"??"}],
            "routines":[{"id":"r","name":"R","position":5,"items":[{"ref":{"id":"squat","name":"Sentadilla","muscle":"legs","kind":"weight_reps"},"sets":500,"rest":99999,"superset":3}]},{"id":"","name":"x"}],
            "history":[{"id":"h","startedAt":5,"finishedAt":1,"exercises":[{"ref":{"id":"squat","name":"S","muscle":"legs","kind":"weight_reps"},"sets":[{"w":9999,"r":-1,"d":true},{"w":60,"r":5,"d":true,"at":3,"rpe":8.3}]}]},{"id":"open","startedAt":1}]}""",
        )
        val d = repo().data.value
        assertEquals(WorkoutData.MAX_WEEKLY_GOAL, d.weeklyGoal)
        assertEquals(0, d.defaultRestSeconds)
        assertEquals(WorkoutData.DEFAULT_BAR_KG, d.barKg, 0.0)
        assertEquals(listOf("Mío"), d.customExercises.map { it.name })
        assertEquals(1, d.routines.size)
        assertEquals(WorkoutOps.MAX_SETS_PER_EXERCISE, d.routines[0].items[0].sets)
        assertEquals(WorkoutOps.MAX_REST_SECONDS, d.routines[0].items[0].restSeconds)
        assertNull("un superset de un solo ejercicio se deshace", d.routines[0].items[0].superset)
        assertEquals(0, d.routines[0].position)
        assertEquals("solo la sesión terminada", 1, d.history.size)
        val h = d.history.single()
        assertEquals("fin nunca antes del inicio", 5L, h.finishedAt)
        val sets = h.exercises.single().sets
        assertFalse("una serie hecha sin datos válidos vuelve a pendiente", sets[0].done)
        assertNull(sets[0].weightKg)
        assertNull(sets[1].rpe)
        assertTrue(sets[1].done)
    }
}

class WorkoutGameTest {
    private val monday = LocalDate.of(2026, 11, 16) // lunes
    private fun outcome(qualifies: Boolean = true, volume: Double = 1000.0, records: Int = 0, days: Int = 1, goal: Int = 3) =
        WorkoutOutcome(qualifies, volume, records, days, goal)

    @Test fun aQualifyingWorkoutGivesXpAndAchievement() {
        val r = GameEngine.recordWorkout(ZenState(), outcome(), monday)
        assertEquals(XpRules.WORKOUT, r.events.xpGained)
        assertTrue(r.events.workoutDone)
        assertEquals(XpRules.WORKOUT, r.state.progress.xp)
        assertEquals(1, r.state.progress.workouts)
        assertTrue(Achievement.FIRST_WORKOUT in r.events.unlocked)
        assertEquals(listOf("Entrenamiento completado · +40 XP", "Logro: Primer entrenamiento"), GameMessages.describe(r.events))
    }

    @Test fun nonQualifyingWorkoutChangesNothing() {
        val s = ZenState()
        val r = GameEngine.recordWorkout(s, outcome(qualifies = false), monday)
        assertEquals(s, r.state)
        assertTrue(r.events.isEmpty)
    }

    @Test fun onlyTheFirstWorkoutOfTheDayGivesXp() {
        val first = GameEngine.recordWorkout(ZenState(), outcome(), monday)
        val second = GameEngine.recordWorkout(first.state, outcome(days = 1), monday)
        assertEquals(0, second.events.xpGained)
        assertEquals(2, second.state.progress.workouts)
        assertEquals(first.state.progress.xp, second.state.progress.xp)
        val nextDay = GameEngine.recordWorkout(second.state, outcome(days = 2), monday.plusDays(1))
        assertEquals(XpRules.WORKOUT, nextDay.events.xpGained)
    }

    @Test fun recordBonusIsCapped() {
        val r = GameEngine.recordWorkout(ZenState(), outcome(records = 7), monday)
        assertEquals(XpRules.WORKOUT + XpRules.WORKOUT_RECORD_MAX * XpRules.WORKOUT_RECORD, r.events.xpGained)
        assertEquals(7, r.state.progress.personalRecords)
        assertTrue(Achievement.FIRST_RECORD in r.events.unlocked)
        assertTrue(GameMessages.describe(r.events).any { it.startsWith("Marcas personales en 7") })
    }

    @Test fun weeklyGoalBonusOncePerWeekAndStreakAcrossWeeks() {
        val a = GameEngine.recordWorkout(ZenState(), outcome(days = 3, goal = 3), monday.plusDays(2))
        assertTrue(a.events.weeklyGoalMet)
        assertEquals(XpRules.WORKOUT + XpRules.WEEKLY_GOAL, a.events.xpGained)
        assertEquals(1, a.state.progress.goalWeekStreak)
        assertTrue(Achievement.GOAL_WEEK in a.events.unlocked)
        // Otro entreno esa misma semana: sin segundo bonus.
        val again = GameEngine.recordWorkout(a.state, outcome(days = 4, goal = 3), monday.plusDays(4))
        assertFalse(again.events.weeklyGoalMet)
        assertEquals(1, again.state.progress.goalWeeks)
        // La semana siguiente: racha 2; saltarse una semana la reinicia.
        val w2 = GameEngine.recordWorkout(again.state, outcome(days = 3, goal = 3), monday.plusDays(9))
        assertEquals(2, w2.state.progress.goalWeekStreak)
        val w4 = GameEngine.recordWorkout(w2.state, outcome(days = 3, goal = 3), monday.plusDays(23))
        assertEquals(1, w4.state.progress.goalWeekStreak)
        assertEquals(2, w4.state.progress.bestGoalWeekStreak)
        assertEquals(3, w4.state.progress.goalWeeks)
    }

    @Test fun belowTheGoalNoBonus() {
        val r = GameEngine.recordWorkout(ZenState(), outcome(days = 2, goal = 3), monday)
        assertFalse(r.events.weeklyGoalMet)
        assertEquals(XpRules.WORKOUT, r.events.xpGained)
    }

    @Test fun fourWeeksStreakAndTonnageAchievements() {
        var s = ZenState()
        var day = monday
        repeat(4) { s = GameEngine.recordWorkout(s, outcome(days = 3, volume = 3000.0), day).state; day = day.plusWeeks(1) }
        assertTrue(Achievement.GOAL_WEEKS_4.id in s.progress.achievements)
        assertTrue(Achievement.TONNE_CLUB.id in s.progress.achievements)
    }

    @Test fun xpNeverOverflowsWithManyWorkouts() {
        val s = ZenState(progress = Progress(xp = Levels.MAX_XP, workoutVolumeKg = 999_999_999))
        val r = GameEngine.recordWorkout(s, outcome(volume = 1e12), monday)
        assertEquals(Levels.MAX_XP, r.state.progress.xp)
        assertTrue(r.state.progress.workoutVolumeKg <= 1_000_000_000L)
    }

    @Test fun progressJsonRoundTripsWorkoutFields() {
        val p = Progress(workouts = 4, workoutVolumeKg = 12345, personalRecords = 3, workoutXpDay = 99, goalBonusWeek = 12, goalWeeks = 2, goalWeekStreak = 2, bestGoalWeekStreak = 5, lastGoalWeek = 12)
        val back = StateJson.decode(StateJson.encode(ZenState(progress = p))).progress
        assertEquals(p, back)
        assertEquals(Progress(), StateJson.decode(StateJson.encode(ZenState())).progress)
    }
}

class WorkoutActionsTest {
    private val dir = Files.createTempDirectory("zen-wa").toFile()
    private val workouts = WorkoutRepository(File(dir, "workouts.json"), File(dir, "active_workout.json"))
    private val state = StateRepository(File(dir, "state.json"))
    private val zone = ZoneOffset.UTC
    private val t0 = LocalDate.of(2026, 11, 16).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun withRoutine(): Routine {
        val items = listOf(RoutineExercise(BENCH, 3, 90), RoutineExercise(SQUAT, 3, 90))
        assertTrue(WorkoutActions.addRoutine(workouts, "Día 1", items) is WorkoutOps.RoutineResult.Ok)
        return workouts.data.value.routines.single()
    }

    private fun doAllSets(startAt: Long, w: Double = 60.0, r: Int = 8) {
        var at = startAt
        val session = workouts.data.value.active!!
        session.exercises.forEachIndexed { e, ex ->
            ex.sets.indices.forEach { s ->
                WorkoutActions.edit(workouts) { WorkoutOps.editSet(it, e, s) { set -> set.copy(weightKg = w, reps = r) } }
                assertTrue(WorkoutActions.toggleSet(workouts, e, s, at) is WorkoutOps.CompleteResult.Done)
                at += 2 * MIN
            }
        }
    }

    @Test fun fullFlowStartLogFinishGivesXpAndSavesHistory() {
        val routine = withRoutine()
        val started = WorkoutActions.start(workouts, routine.id, t0)
        assertEquals(2, started.exercises.size)
        assertEquals("empezar otra vez devuelve la misma sesión", started.id, WorkoutActions.start(workouts, null, t0 + 1).id)
        doAllSets(t0 + MIN)
        val result = WorkoutActions.finish(workouts, state, t0 + 20 * MIN, zone)!!
        assertTrue(result.qualified)
        assertEquals(XpRules.WORKOUT, result.events.xpGained)
        assertNull(workouts.data.value.active)
        assertEquals(1, workouts.data.value.history.size)
        assertEquals(XpRules.WORKOUT, state.state.value.progress.xp)
        assertEquals(1, state.state.value.progress.workouts)
        assertEquals(60.0 * 8 * 6, state.state.value.progress.workoutVolumeKg.toDouble(), 1.0)
        assertNull("terminar dos veces no repite", WorkoutActions.finish(workouts, state, t0 + 21 * MIN, zone))
        assertEquals(XpRules.WORKOUT, state.state.value.progress.xp)
    }

    @Test fun secondWorkoutPrefillsFromTheFirstAndBeatingItIsARecord() {
        val routine = withRoutine()
        WorkoutActions.start(workouts, routine.id, t0); doAllSets(t0 + MIN)
        WorkoutActions.finish(workouts, state, t0 + 20 * MIN, zone)
        val second = WorkoutActions.start(workouts, routine.id, t0 + DAY)
        assertEquals(60.0, second.exercises[0].sets[0].weightKg!!, 0.0)
        assertEquals(8, second.exercises[0].sets[0].reps)
        doAllSets(t0 + DAY + MIN, w = 65.0, r = 8)
        val result = WorkoutActions.finish(workouts, state, t0 + DAY + 20 * MIN, zone)!!
        assertEquals("press de banca y sentadilla", 2, result.records.size)
        assertEquals(2, result.session.prs)
        assertEquals(XpRules.WORKOUT + 2 * XpRules.WORKOUT_RECORD, result.events.xpGained)
        assertTrue(Achievement.FIRST_RECORD in result.events.unlocked)
    }

    @Test fun tooShortWorkoutIsSavedButGivesNothing() {
        WorkoutActions.start(workouts, null, t0)
        WorkoutActions.edit(workouts) { WorkoutOps.addExercise(it, BENCH, emptyList(), 60, sets = 1) }
        WorkoutActions.edit(workouts) { WorkoutOps.editSet(it, 0, 0) { s -> s.copy(weightKg = 50.0, reps = 5) } }
        WorkoutActions.toggleSet(workouts, 0, 0, t0 + MIN)
        val r = WorkoutActions.finish(workouts, state, t0 + 2 * MIN, zone)!!
        assertFalse(r.qualified)
        assertEquals(0, r.events.xpGained)
        assertEquals(1, workouts.data.value.history.size)
        assertEquals(0, state.state.value.progress.xp)
    }

    @Test fun finishingWithNothingDoneKeepsTheSessionForTheUserToDiscard() {
        withRoutine()
        WorkoutActions.start(workouts, workouts.data.value.routines.single().id, t0)
        assertNull(WorkoutActions.finish(workouts, state, t0 + MIN, zone))
        assertNotNull(workouts.data.value.active)
        WorkoutActions.discard(workouts)
        assertNull(workouts.data.value.active)
        assertTrue(workouts.data.value.history.isEmpty())
    }

    @Test fun stateWriteFailureStillKeepsTheWorkout() {
        val routine = withRoutine()
        WorkoutActions.start(workouts, routine.id, t0); doAllSets(t0 + MIN)
        File(dir, "state.json.tmp").mkdirs() // impide guardar el XP
        val r = WorkoutActions.finish(workouts, state, t0 + 20 * MIN, zone)!!
        assertEquals(1, workouts.data.value.history.size)
        assertEquals(0, r.events.xpGained)
        assertTrue("la pantalla debe poder avisar de que no se guardó", r.xpNotSaved)
        assertEquals(0, state.state.value.progress.xp)
    }

    @Test fun deleteAndRestoreRoutineKeepsPosition() {
        listOf("A", "B", "C").forEach { WorkoutActions.addRoutine(workouts, it) }
        val b = workouts.data.value.routines[1]
        val removed = WorkoutActions.deleteRoutine(workouts, b.id)!!
        assertEquals(listOf("A", "C"), workouts.data.value.routines.map { it.name })
        assertTrue(WorkoutActions.restoreRoutine(workouts, removed))
        assertEquals(listOf("A", "B", "C"), workouts.data.value.routines.map { it.name })
        assertFalse("no se duplica", WorkoutActions.restoreRoutine(workouts, removed))
    }

    @Test fun starterRoutinesAreCreatedAndRespectTheLimit() {
        assertEquals(3, WorkoutActions.addStarterRoutines(workouts))
        assertEquals(3, workouts.data.value.routines.size)
        assertTrue(workouts.data.value.routines.all { it.items.isNotEmpty() })
        (1..WorkoutOps.MAX_ROUTINES - 3).forEach { WorkoutActions.addRoutine(workouts, "R$it") }
        assertEquals(0, WorkoutActions.addStarterRoutines(workouts))
    }

    @Test fun customExerciseLifecycle() {
        val r = WorkoutActions.addCustomExercise(workouts, "Mi press", MuscleGroup.CHEST, ExerciseKind.WEIGHT_REPS) as ExerciseLibrary.AddResult.Added
        assertEquals(1, workouts.data.value.customExercises.size)
        assertTrue(WorkoutActions.addCustomExercise(workouts, "mi PRESS", MuscleGroup.CHEST, ExerciseKind.WEIGHT_REPS) is ExerciseLibrary.AddResult.Duplicate)
        WorkoutActions.deleteCustomExercise(workouts, r.ref.id)
        assertTrue(workouts.data.value.customExercises.isEmpty())
    }

    @Test fun settingsAreClamped() {
        WorkoutActions.setWeeklyGoal(workouts, 50); assertEquals(7, workouts.data.value.weeklyGoal)
        WorkoutActions.setWeeklyGoal(workouts, 0); assertEquals(1, workouts.data.value.weeklyGoal)
        WorkoutActions.setDefaultRest(workouts, 9999); assertEquals(WorkoutOps.MAX_REST_SECONDS, workouts.data.value.defaultRestSeconds)
        WorkoutActions.setBar(workouts, -3.0); assertEquals(20.0, workouts.data.value.barKg, 0.0)
        WorkoutActions.setBar(workouts, 15.0); assertEquals(15.0, workouts.data.value.barKg, 0.0)
    }

    @Test fun deleteAndRestoreSession() {
        val routine = withRoutine()
        WorkoutActions.start(workouts, routine.id, t0); doAllSets(t0 + MIN)
        val id = WorkoutActions.finish(workouts, state, t0 + 20 * MIN, zone)!!.session.id
        val removed = WorkoutActions.deleteSession(workouts, id)!!
        assertTrue(workouts.data.value.history.isEmpty())
        WorkoutActions.restoreSession(workouts, removed)
        WorkoutActions.restoreSession(workouts, removed)
        assertEquals(1, workouts.data.value.history.size)
    }

    @Test fun csvExportThenImportIntoAFreshInstallRestoresTheHistory() {
        val routine = withRoutine()
        WorkoutActions.start(workouts, routine.id, t0); doAllSets(t0 + MIN)
        WorkoutActions.edit(workouts) { WorkoutOps.setSessionNote(it, "con coma, \"comillas\"\ny salto") }
        WorkoutActions.finish(workouts, state, t0 + 20 * MIN, zone)
        val csv = WorkoutCsv.export(workouts.data.value.history, zone)

        val other = WorkoutRepository(File(Files.createTempDirectory("zen-wb").toFile(), "w.json"), File(dir, "a2.json"))
        val (added, skipped, problem) = WorkoutActions.importCsv(other, csv)
        assertNull(problem)
        assertEquals(1, added); assertEquals(0, skipped)
        val a = workouts.data.value.history.single(); val b = other.data.value.history.single()
        assertEquals(a.id, b.id)
        assertEquals(a.note, b.note)
        assertEquals(a.startedAt, b.startedAt)
        assertEquals(a.exercises.map { it.ref }, b.exercises.map { it.ref })
        assertEquals(a.exercises.map { e -> e.sets.map { it.weightKg to it.reps } }, b.exercises.map { e -> e.sets.map { it.weightKg to it.reps } })
        // Importar otra vez no duplica.
        assertEquals(0, WorkoutActions.importCsv(other, csv).first)
        assertEquals(1, other.data.value.history.size)
    }
}

class WorkoutCsvTest {
    @Test fun parserHandlesQuotesAndEmbeddedNewlines() {
        val rows = WorkoutCsv.parseRows("a,b,c\r\n\"x,1\",\"he said \"\"hi\"\"\",\"l1\nl2\"\n\nlast,,")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("x,1", "he said \"hi\"", "l1\nl2"), listOf("last", "", "")), rows)
    }

    @Test fun parserIgnoresBomAndTrailingNewline() {
        assertEquals(listOf(listOf("a", "b")), WorkoutCsv.parseRows(0xFEFF.toChar() + "a,b\n"))
        assertTrue(WorkoutCsv.parseRows("").isEmpty())
    }

    @Test fun importRejectsFilesThatAreNotOurs() {
        assertEquals("El archivo está vacío.", WorkoutCsv.parse("").problem)
        val r = WorkoutCsv.parse("nombre,edad\nana,3")
        assertNotNull(r.problem)
        assertTrue(r.sessions.isEmpty())
    }

    private val header = "session_id,finished_at,exercise_id,exercise,kind,set,weight_kg,reps,seconds,type,rpe"

    @Test fun importSkipsBadRowsAndKeepsGoodOnes() {
        val csv = """
            $header
            s1,1700000000000,bench_press,Press de banca,weight_reps,1,60,8,,normal,8
            s1,1700000000000,bench_press,Press de banca,weight_reps,2,abc,8,,normal,
            s1,1700000000000,bench_press,Press de banca,weight_reps,3,60,0,,normal,
            ,1700000000000,bench_press,Press de banca,weight_reps,4,60,8,,normal,
            s2,notanumber,bench_press,Press de banca,weight_reps,1,60,8,,normal,
            s3,1700000100000,plank,Plancha,duration,1,,,45,normal,
        """.trimIndent()
        val r = WorkoutCsv.parse(csv)
        assertEquals(4, r.skippedRows)
        assertEquals(listOf("s1", "s3"), r.sessions.map { it.id })
        assertEquals(1, r.sessions[0].exercises.single().sets.size)
        assertEquals(8.0, r.sessions[0].exercises.single().sets.single().rpe!!, 0.0)
        assertTrue(r.sessions.all { s -> s.exercises.flatMap { it.sets }.all { it.done } })
    }

    @Test fun columnOrderDoesNotMatterAndExtraColumnsAreIgnored() {
        val csv = "extra,reps,kind,exercise,exercise_id,finished_at,session_id,weight_kg,set\nzz,5,weight_reps,Sentadilla,squat,1700000000000,k,100,1"
        val r = WorkoutCsv.parse(csv)
        assertEquals(100.0, r.sessions.single().exercises.single().sets.single().weightKg!!, 0.0)
        assertEquals(WorkoutOps.FREE_WORKOUT_NAME, r.sessions.single().routineName)
    }

    @Test fun mergeDoesNotOverwriteAndKeepsOrder() {
        fun s(id: String, end: Long) = WorkoutSession(id, null, "x", end - 1, end, listOf(SessionExercise(BENCH, 0, null, listOf(done(1.0, 1)))))
        val (merged, n) = WorkoutCsv.merge(listOf(s("a", 30), s("b", 10)), listOf(s("a", 99), s("c", 20), s("c", 21)))
        assertEquals(1, n)
        assertEquals(listOf("b", "c", "a"), merged.map { it.id })
        assertEquals(30L, merged.first { it.id == "a" }.finishedAt)
    }

    @Test fun theSameExerciseTwiceInOneSessionSurvivesExportAndImport() {
        val heavy = SessionExercise(BENCH, 0, null, listOf(done(100.0, 3), done(100.0, 3)))
        val light = SessionExercise(BENCH, 0, null, listOf(done(60.0, 12), done(60.0, 12), done(60.0, 12)))
        val s = WorkoutSession("dup", null, "Libre", 1_000L, 2_000L, listOf(heavy, SessionExercise(SQUAT, 0, null, listOf(done(80.0, 5))), light))
        val back = WorkoutCsv.parse(WorkoutCsv.export(listOf(s), ZoneOffset.UTC)).sessions.single()
        assertEquals(listOf("bench_press", "squat", "bench_press"), back.exercises.map { it.ref.id })
        assertEquals(listOf(2, 1, 3), back.exercises.map { it.sets.size })
        assertEquals(100.0, back.exercises[0].sets[0].weightKg!!, 0.0)
        assertEquals(60.0, back.exercises[2].sets[0].weightKg!!, 0.0)
    }

    @Test fun textCellsThatLookLikeFormulasAreNeutralisedAndRestored() {
        val evil = ExerciseRef("custom:e", "=HYPERLINK(\"http://x\")", MuscleGroup.CORE, ExerciseKind.BODYWEIGHT_REPS)
        val s = WorkoutSession("f", null, "+cmd", 1_000L, 2_000L, listOf(SessionExercise(evil, 0, null, listOf(done(null, 5)), note = "@SUM(A1)")), note = "-2+3")
        val csv = WorkoutCsv.export(listOf(s), ZoneOffset.UTC)
        val cells = WorkoutCsv.parseRows(csv).drop(1).single()
        assertTrue("ninguna celda de texto empieza por un carácter de fórmula", cells.filter { it.isNotEmpty() }.none { it[0] in "=+-@" && !it[0].isDigit() })
        val back = WorkoutCsv.parse(csv).sessions.single()
        assertEquals("+cmd", back.routineName)
        assertEquals("-2+3", back.note)
        assertEquals("=HYPERLINK(\"http://x\")", back.exercises.single().ref.name)
        assertEquals("@SUM(A1)", back.exercises.single().note)
    }

    @Test fun customExercisesFromImportAreRegisteredOnce() {
        val custom = ExerciseRef("custom:z", "Mío", MuscleGroup.CORE, ExerciseKind.BODYWEIGHT_REPS)
        val s = WorkoutSession("i", null, "x", 1, 2, listOf(SessionExercise(custom, 0, null, listOf(done(null, 5))), SessionExercise(BENCH, 0, null, listOf(done(1.0, 1)))))
        assertEquals(listOf(custom), WorkoutCsv.customFrom(listOf(s, s), emptyList()))
        assertEquals(listOf(custom), WorkoutCsv.customFrom(listOf(s), listOf(custom)))
    }
}
