package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.ExerciseKind
import com.hermesmarcano.zen.data.ExerciseRef
import com.hermesmarcano.zen.data.MuscleGroup
import com.hermesmarcano.zen.data.RestState
import com.hermesmarcano.zen.data.Routine
import com.hermesmarcano.zen.data.RoutineExercise
import com.hermesmarcano.zen.data.SessionExercise
import com.hermesmarcano.zen.data.SetEntry
import com.hermesmarcano.zen.data.SetType
import com.hermesmarcano.zen.data.WorkoutSession
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L
private const val DAY = 24 * 60 * MIN
private val BENCH = ExerciseLibrary.preset("bench_press")!!
private val SQUAT = ExerciseLibrary.preset("squat")!!
private val PUSHUP = ExerciseLibrary.preset("pushup")!!
private val PLANK = ExerciseLibrary.preset("plank")!!

private fun done(w: Double?, r: Int?, at: Long = 1L, type: SetType = SetType.NORMAL) =
    SetEntry(weightKg = w, reps = r, type = type, done = true, completedAt = at)

private fun finished(id: String, endMillis: Long, vararg exercises: SessionExercise, routineId: String? = null, minutes: Int = 45) =
    WorkoutSession(id, routineId, routineId ?: "Libre", endMillis - minutes * MIN, endMillis, exercises.toList())

private fun ex(ref: ExerciseRef, vararg sets: SetEntry, rest: Int = 90, superset: Int? = null) = SessionExercise(ref, rest, superset, sets.toList())

class WorkoutNumbersTest {
    @Test fun weightAcceptsCommaAndDot() {
        assertEquals(62.5, WorkoutNumbers.parseWeight("62,5")!!, 0.0)
        assertEquals(62.5, WorkoutNumbers.parseWeight(" 62.5 ")!!, 0.0)
        assertEquals(0.0, WorkoutNumbers.parseWeight("0")!!, 0.0)
    }

    @Test fun weightRejectsGarbageNegativeAndAbsurd() {
        listOf("", "abc", "-5", "1501", "NaN", "Infinity", "1e9", "6,2,5").forEach { assertNull(it, WorkoutNumbers.parseWeight(it)) }
    }

    @Test fun weightRoundsToHundredths() = assertEquals(62.35, WorkoutNumbers.parseWeight("62.3456")!!, 0.0)

    @Test fun repsAndSecondsAreBounded() {
        assertEquals(12, WorkoutNumbers.parseReps("12"))
        assertNull(WorkoutNumbers.parseReps("-1"))
        assertNull(WorkoutNumbers.parseReps("1000"))
        assertNull(WorkoutNumbers.parseReps("8,5"))
        assertEquals(45, WorkoutNumbers.parseSeconds("45"))
        assertNull(WorkoutNumbers.parseSeconds("36001"))
    }

    @Test fun kgFormatUsesCommaAndNoTrailingZeros() {
        assertEquals("60", WorkoutNumbers.formatKg(60.0))
        assertEquals("62,5", WorkoutNumbers.formatKg(62.5))
        assertEquals("1,25", WorkoutNumbers.formatKg(1.25))
        assertEquals("62.5", WorkoutNumbers.formatKgPlain(62.5))
    }

    @Test fun clockAndDuration() {
        assertEquals("1:05", WorkoutNumbers.clock(65))
        assertEquals("0:00", WorkoutNumbers.clock(-3))
        assertEquals("1:01:01", WorkoutNumbers.clock(3661))
        assertEquals("1 h 05 min", WorkoutNumbers.durationText(65))
        assertEquals("45 min", WorkoutNumbers.durationText(45))
    }

    @Test fun rpeOnlyInHalfSteps() {
        assertTrue(WorkoutNumbers.isValidRpe(8.5))
        assertTrue(WorkoutNumbers.isValidRpe(10.0))
        assertFalse(WorkoutNumbers.isValidRpe(5.5))
        assertFalse(WorkoutNumbers.isValidRpe(8.3))
        assertFalse(WorkoutNumbers.isValidRpe(10.5))
        assertEquals("8,5", WorkoutNumbers.formatRpe(8.5))
        assertEquals("9", WorkoutNumbers.formatRpe(9.0))
    }

    @Test fun oneRepMaxEpley() {
        assertEquals(100.0, WorkoutNumbers.estimatedOneRepMax(100.0, 1)!!, 0.0)
        assertEquals(133.33, WorkoutNumbers.estimatedOneRepMax(100.0, 10)!!, 0.01)
        assertNull("más de 12 reps no es fiable", WorkoutNumbers.estimatedOneRepMax(40.0, 20))
        assertNull(WorkoutNumbers.estimatedOneRepMax(0.0, 5))
        assertNull(WorkoutNumbers.estimatedOneRepMax(50.0, 0))
    }

    @Test fun canCompleteNeedsTheRightFieldsPerKind() {
        assertTrue(WorkoutNumbers.canComplete(ExerciseKind.WEIGHT_REPS, SetEntry(weightKg = 60.0, reps = 8)))
        assertTrue("peso 0 es válido (barra vacía)", WorkoutNumbers.canComplete(ExerciseKind.WEIGHT_REPS, SetEntry(weightKg = 0.0, reps = 8)))
        assertFalse(WorkoutNumbers.canComplete(ExerciseKind.WEIGHT_REPS, SetEntry(weightKg = null, reps = 8)))
        assertFalse(WorkoutNumbers.canComplete(ExerciseKind.WEIGHT_REPS, SetEntry(weightKg = 60.0, reps = 0)))
        assertFalse(WorkoutNumbers.canComplete(ExerciseKind.WEIGHT_REPS, SetEntry()))
        assertTrue(WorkoutNumbers.canComplete(ExerciseKind.BODYWEIGHT_REPS, SetEntry(reps = 10)))
        assertFalse(WorkoutNumbers.canComplete(ExerciseKind.BODYWEIGHT_REPS, SetEntry(reps = 0)))
        assertTrue(WorkoutNumbers.canComplete(ExerciseKind.DURATION, SetEntry(seconds = 30)))
        assertFalse(WorkoutNumbers.canComplete(ExerciseKind.DURATION, SetEntry(seconds = 0)))
    }

    @Test fun volumeIgnoresWarmupsAndUndoneSets() {
        assertEquals(480.0, WorkoutNumbers.setVolume(ExerciseKind.WEIGHT_REPS, done(60.0, 8)), 0.0)
        assertEquals(0.0, WorkoutNumbers.setVolume(ExerciseKind.WEIGHT_REPS, done(60.0, 8, type = SetType.WARMUP)), 0.0)
        assertEquals(0.0, WorkoutNumbers.setVolume(ExerciseKind.WEIGHT_REPS, SetEntry(weightKg = 60.0, reps = 8)), 0.0)
        assertEquals(0.0, WorkoutNumbers.setVolume(ExerciseKind.BODYWEIGHT_REPS, done(null, 10)), 0.0)
    }
}

class ExerciseLibraryTest {
    @Test fun idsAreUniqueAndCountIsUseful() {
        assertEquals(ExerciseLibrary.presets.size, ExerciseLibrary.presets.map { it.id }.toSet().size)
        assertTrue(ExerciseLibrary.presets.size >= 50)
        assertTrue(ExerciseLibrary.presets.all { it.name.length <= ExerciseLibrary.NAME_MAX })
        assertEquals(ExerciseLibrary.presets.size, ExerciseLibrary.presets.map { ExerciseLibrary.normalize(it.name) }.toSet().size)
    }

    @Test fun searchIgnoresAccentsAndCase() {
        val found = ExerciseLibrary.search(emptyList(), "extension").map { it.id }
        assertTrue("leg_ext" in found)
        assertTrue("tri_pushdown" in found)
        assertTrue(ExerciseLibrary.search(emptyList(), "SENTADILLA").isNotEmpty())
        assertTrue(ExerciseLibrary.search(emptyList(), "zzzz").isEmpty())
    }

    @Test fun searchFiltersByMuscle() {
        val chest = ExerciseLibrary.search(emptyList(), "", MuscleGroup.CHEST)
        assertTrue(chest.isNotEmpty() && chest.all { it.muscle == MuscleGroup.CHEST })
    }

    @Test fun customExercisesComeFirstAndRejectDuplicates() {
        val added = ExerciseLibrary.addCustom(emptyList(), "  Mi   remo ", MuscleGroup.BACK, ExerciseKind.WEIGHT_REPS, "u1") as ExerciseLibrary.AddResult.Added
        assertEquals("Mi remo", added.ref.name)
        assertTrue(ExerciseLibrary.isCustom(added.ref))
        assertEquals(added.ref.id, ExerciseLibrary.search(added.list, "").first().id)
        assertTrue(ExerciseLibrary.addCustom(added.list, "mi REMO", MuscleGroup.BACK, ExerciseKind.WEIGHT_REPS, "u2") is ExerciseLibrary.AddResult.Duplicate)
        assertTrue("también contra la biblioteca", ExerciseLibrary.addCustom(emptyList(), "press de banca", MuscleGroup.CHEST, ExerciseKind.WEIGHT_REPS, "u3") is ExerciseLibrary.AddResult.Duplicate)
        assertTrue(ExerciseLibrary.addCustom(emptyList(), "   ", MuscleGroup.BACK, ExerciseKind.WEIGHT_REPS, "u4") is ExerciseLibrary.AddResult.Blank)
        assertTrue(ExerciseLibrary.addCustom(emptyList(), "x".repeat(41), MuscleGroup.BACK, ExerciseKind.WEIGHT_REPS, "u5") is ExerciseLibrary.AddResult.TooLong)
    }

    @Test fun customLimit() {
        val full = (1..ExerciseLibrary.MAX_CUSTOM).map { ExerciseRef("custom:$it", "Ejercicio $it", MuscleGroup.CORE, ExerciseKind.BODYWEIGHT_REPS) }
        assertTrue(ExerciseLibrary.addCustom(full, "Otro más", MuscleGroup.CORE, ExerciseKind.BODYWEIGHT_REPS, "z") is ExerciseLibrary.AddResult.Full)
    }
}

class WorkoutRoutinesTest {
    private fun item(ref: ExerciseRef = BENCH, sets: Int = 3, rest: Int = 90, group: Int? = null) = RoutineExercise(ref, sets, rest, group)

    @Test fun addRoutineValidatesNameAndLimit() {
        assertTrue(WorkoutOps.addRoutine(emptyList(), " ", "a") is WorkoutOps.RoutineResult.Blank)
        assertTrue(WorkoutOps.addRoutine(emptyList(), "x".repeat(41), "a") is WorkoutOps.RoutineResult.TooLong)
        val ok = WorkoutOps.addRoutine(emptyList(), "  Día   1 ", "a") as WorkoutOps.RoutineResult.Ok
        assertEquals("Día 1", ok.routine.name)
        val full = (0 until WorkoutOps.MAX_ROUTINES).map { Routine("r$it", "R$it", it, emptyList()) }
        assertTrue(WorkoutOps.addRoutine(full, "otra", "z") is WorkoutOps.RoutineResult.Full)
    }

    @Test fun itemsAreClamped() {
        val ok = WorkoutOps.addRoutine(emptyList(), "A", "a", listOf(item(sets = 99, rest = 9999), item(SQUAT, sets = 0, rest = -5))) as WorkoutOps.RoutineResult.Ok
        assertEquals(listOf(WorkoutOps.MAX_SETS_PER_EXERCISE, 1), ok.routine.items.map { it.sets })
        assertEquals(listOf(WorkoutOps.MAX_REST_SECONDS, 0), ok.routine.items.map { it.restSeconds })
    }

    @Test fun deleteAndMoveRenumber() {
        val all = listOf(Routine("a", "A", 0, emptyList()), Routine("b", "B", 1, emptyList()), Routine("c", "C", 2, emptyList()))
        assertEquals(listOf("a", "c"), WorkoutOps.deleteRoutine(all, "b").map { it.id })
        assertEquals(listOf(0, 1), WorkoutOps.deleteRoutine(all, "b").map { it.position })
        assertEquals(listOf("c", "a", "b"), WorkoutOps.moveRoutine(all, 2, 0).map { it.id })
        assertEquals(all, WorkoutOps.moveRoutine(all, 0, 9))
    }

    @Test fun supersetNeedsTwoContiguousMembers() {
        val lonely = WorkoutOps.normalizeSupersets(listOf(item(group = 1), item(SQUAT), item(PUSHUP)))
        assertTrue(lonely.all { it.superset == null })
        val apart = WorkoutOps.normalizeSupersets(listOf(item(group = 1), item(SQUAT), item(PUSHUP, group = 1)))
        assertTrue("el mismo grupo separado por otro ejercicio se deshace", apart.all { it.superset == null })
        val pair = WorkoutOps.normalizeSupersets(listOf(item(group = 7), item(SQUAT, group = 7), item(PUSHUP)))
        assertEquals(listOf(1, 1, null), pair.map { it.superset })
    }

    @Test fun toggleSupersetJoinsAndSplits() {
        val base = listOf(item(), item(SQUAT), item(PUSHUP))
        val joined = WorkoutOps.toggleSupersetWithNext(base, 0)
        assertEquals(joined[0].superset, joined[1].superset)
        assertNotNull(joined[0].superset)
        assertNull(joined[2].superset)
        val split = WorkoutOps.toggleSupersetWithNext(joined, 0)
        assertTrue(split.all { it.superset == null })
        assertEquals("el último no tiene siguiente", base, WorkoutOps.toggleSupersetWithNext(base, 2))
        val triple = WorkoutOps.toggleSupersetWithNext(WorkoutOps.toggleSupersetWithNext(base, 0), 1)
        assertEquals(1, triple.mapNotNull { it.superset }.toSet().size)
        assertEquals(3, triple.count { it.superset != null })
    }

    @Test fun updateRoutineKeepsPositionAndRejectsBlank() {
        val all = listOf(Routine("a", "A", 0, emptyList()), Routine("b", "B", 1, emptyList()))
        val ok = WorkoutOps.updateRoutine(all, "b", " Nueva ", listOf(item())) as WorkoutOps.RoutineResult.Ok
        assertEquals(listOf("A", "Nueva"), ok.routines.map { it.name })
        assertEquals(1, ok.routine.position)
        assertTrue(WorkoutOps.updateRoutine(all, "b", "", emptyList()) is WorkoutOps.RoutineResult.Blank)
    }
}

class WorkoutSessionTest {
    private val t0 = 1_000_000_000L

    private fun routine(vararg items: RoutineExercise) = Routine("r1", "Empuje", 0, items.toList())

    @Test fun startPrefillsFromLastTimeAndCopiesTheLastSetWhenThereAreMore() {
        val history = listOf(finished("h1", t0, ex(BENCH, done(60.0, 8), done(62.5, 6))))
        val s = WorkoutOps.start(routine(RoutineExercise(BENCH, 3, 90)), history, "s", t0 + DAY)
        val sets = s.exercises[0].sets
        assertEquals(listOf(60.0, 62.5, 62.5), sets.map { it.weightKg })
        assertEquals(listOf(8, 6, 6), sets.map { it.reps })
        assertTrue(sets.none { it.done })
        assertNull(s.finishedAt)
        assertEquals("r1", s.routineId)
    }

    @Test fun startWithoutHistoryLeavesBlankSetsAndFreeWorkoutIsEmpty() {
        val s = WorkoutOps.start(routine(RoutineExercise(BENCH, 2, 90)), emptyList(), "s", t0)
        assertTrue(s.exercises[0].sets.all { it.weightKg == null && it.reps == null })
        val free = WorkoutOps.start(null, emptyList(), "f", t0)
        assertTrue(free.exercises.isEmpty())
        assertNull(free.routineId)
    }

    @Test fun prefillIgnoresWarmupsAndKindSpecificFields() {
        val history = listOf(finished("h", t0, ex(BENCH, done(20.0, 10, type = SetType.WARMUP), done(80.0, 5)), ex(PLANK, SetEntry(seconds = 45, done = true, completedAt = 1))))
        val s = WorkoutOps.start(routine(RoutineExercise(BENCH, 1, 90), RoutineExercise(PLANK, 1, 30)), history, "s", t0 + DAY)
        assertEquals(80.0, s.exercises[0].sets[0].weightKg!!, 0.0)
        assertEquals(45, s.exercises[1].sets[0].seconds)
        assertNull(s.exercises[1].sets[0].weightKg)
    }

    private fun session(vararg exercises: SessionExercise) = WorkoutSession("s", null, "T", t0, null, exercises.toList())

    private fun set(w: Double? = 60.0, r: Int? = 8) = SetEntry(weightKg = w, reps = r)

    @Test fun completingAStartsRestAndStoresRealRestBetweenSets() {
        val s0 = session(ex(BENCH, set(), set(), rest = 90))
        val a = WorkoutOps.toggleSet(s0, 0, 0, t0 + 1_000) as WorkoutOps.CompleteResult.Done
        assertEquals(90, a.restStarted!!.totalSeconds)
        assertEquals(t0 + 91_000, a.restStarted!!.endsAt)
        assertNull("la primera serie no tiene descanso previo", a.session.exercises[0].sets[0].restSeconds)
        val b = WorkoutOps.toggleSet(a.session, 0, 1, t0 + 100_000) as WorkoutOps.CompleteResult.Done
        assertEquals(99, b.session.exercises[0].sets[1].restSeconds)
        assertNull("terminar todo no abre otro descanso", b.restStarted)
        assertNull(b.session.rest)
    }

    @Test fun completingWithMissingDataIsRejected() {
        val s0 = session(ex(BENCH, set(null, 8), set(60.0, 0), SetEntry()))
        listOf(0, 1, 2).forEach { assertTrue(WorkoutOps.toggleSet(s0, 0, it, t0) is WorkoutOps.CompleteResult.Invalid) }
        assertTrue(WorkoutOps.toggleSet(s0, 5, 0, t0) is WorkoutOps.CompleteResult.Invalid)
        assertTrue(WorkoutOps.toggleSet(s0, 0, 9, t0) is WorkoutOps.CompleteResult.Invalid)
    }

    @Test fun unmarkingClearsTheSetAndOnlyStopsRestIfItStartedIt() {
        val s0 = session(ex(BENCH, set(), set(), set(), rest = 90))
        val a = (WorkoutOps.toggleSet(s0, 0, 0, t0 + 1_000) as WorkoutOps.CompleteResult.Done).session
        val b = (WorkoutOps.toggleSet(a, 0, 1, t0 + 100_000) as WorkoutOps.CompleteResult.Done).session
        val unmarkOld = (WorkoutOps.toggleSet(b, 0, 0, t0 + 101_000) as WorkoutOps.CompleteResult.Done).session
        assertNotNull("desmarcar una serie antigua no apaga el descanso actual", unmarkOld.rest)
        assertFalse(unmarkOld.exercises[0].sets[0].done)
        val unmarkCurrent = (WorkoutOps.toggleSet(unmarkOld, 0, 1, t0 + 102_000) as WorkoutOps.CompleteResult.Done).session
        assertNull(unmarkCurrent.rest)
        assertNull(unmarkCurrent.exercises[0].sets[1].completedAt)
    }

    @Test fun anImplausiblyLongGapIsNotRest() {
        val s0 = session(ex(BENCH, set(), set(), rest = 90))
        val a = (WorkoutOps.toggleSet(s0, 0, 0, t0 + 1_000) as WorkoutOps.CompleteResult.Done).session
        val b = (WorkoutOps.toggleSet(a, 0, 1, t0 + 1_000 + 31 * MIN) as WorkoutOps.CompleteResult.Done).session
        assertNull(b.exercises[0].sets[1].restSeconds)
    }

    @Test fun clockGoingBackwardsNeverGivesNegativeRest() {
        val s0 = session(ex(BENCH, set(), set()))
        val a = (WorkoutOps.toggleSet(s0, 0, 0, t0 + 50_000) as WorkoutOps.CompleteResult.Done).session
        val b = (WorkoutOps.toggleSet(a, 0, 1, t0 + 10_000) as WorkoutOps.CompleteResult.Done).session
        assertNull(b.exercises[0].sets[1].restSeconds)
    }

    @Test fun exerciseWithoutRestNeverStartsTheTimer() {
        val s0 = session(ex(BENCH, set(), set(), rest = 0))
        val a = WorkoutOps.toggleSet(s0, 0, 0, t0) as WorkoutOps.CompleteResult.Done
        assertNull(a.restStarted)
    }

    @Test fun supersetRestComesAfterTheRoundNotAfterEachExercise() {
        val s0 = session(
            ex(BENCH, set(), set(), rest = 60, superset = 1),
            ex(SQUAT, set(), set(), rest = 120, superset = 1),
        )
        val a = WorkoutOps.toggleSet(s0, 0, 0, t0) as WorkoutOps.CompleteResult.Done
        assertNull("falta el segundo ejercicio de la ronda", a.restStarted)
        val b = WorkoutOps.toggleSet(a.session, 1, 0, t0 + 20_000) as WorkoutOps.CompleteResult.Done
        assertEquals("descansa el mayor del grupo", 120, b.restStarted!!.totalSeconds)
        val c = WorkoutOps.toggleSet(b.session, 1, 1, t0 + 150_000) as WorkoutOps.CompleteResult.Done
        assertNull("el compañero va por detrás en la ronda 2", c.restStarted)
        val d = WorkoutOps.toggleSet(c.session, 0, 1, t0 + 160_000) as WorkoutOps.CompleteResult.Done
        assertNull("era la última serie: no hay descanso", d.restStarted)
    }

    @Test fun supersetMemberWithFewerSetsDoesNotBlockTheRest() {
        val s0 = session(ex(BENCH, set(), set(), set(), rest = 60, superset = 1), ex(SQUAT, set(), rest = 60, superset = 1))
        var s = (WorkoutOps.toggleSet(s0, 0, 0, t0) as WorkoutOps.CompleteResult.Done).session
        s = (WorkoutOps.toggleSet(s, 1, 0, t0 + 1_000) as WorkoutOps.CompleteResult.Done).session
        val r = WorkoutOps.toggleSet(s, 0, 1, t0 + 100_000) as WorkoutOps.CompleteResult.Done
        assertNotNull("el compañero solo tenía una serie: no espera", r.restStarted)
    }

    @Test fun adjustRestStaysWithinBounds() {
        val s = session(ex(BENCH)).copy(rest = RestState(t0, t0 + 90_000, 90))
        val more = WorkoutOps.adjustRest(s, 15, t0 + 10_000)
        assertEquals(t0 + 105_000, more.rest!!.endsAt)
        assertEquals(105, more.rest!!.totalSeconds)
        val less = WorkoutOps.adjustRest(s, -15, t0 + 10_000)
        assertEquals(t0 + 75_000, less.rest!!.endsAt)
        val floor = WorkoutOps.adjustRest(s, -15, t0 + 85_000)
        assertEquals("nunca menos de 5 s", t0 + 90_000, floor.rest!!.endsAt)
        val huge = WorkoutOps.adjustRest(s, 100_000, t0)
        assertTrue(huge.rest!!.remainingMillis(t0) <= WorkoutOps.MAX_REST_SECONDS * 1000L)
        assertEquals(s.copy(rest = null), WorkoutOps.adjustRest(s.copy(rest = null), 15, t0))
    }

    @Test fun shorteningNeverLengthensARestThatIsAlmostOver() {
        val s = session(ex(BENCH)).copy(rest = RestState(t0, t0 + 90_000, 90))
        val almost = WorkoutOps.adjustRest(s, -15, t0 + 88_000) // quedan 2 s
        assertEquals("sigue terminando a la misma hora", t0 + 90_000, almost.rest!!.endsAt)
        val extend = WorkoutOps.adjustRest(s, 15, t0 + 88_000)
        assertEquals(t0 + 88_000 + 17_000, extend.rest!!.endsAt)
    }

    @Test fun startAndSkipRest() {
        val s = WorkoutOps.startRest(session(ex(BENCH)), 60, t0)
        assertEquals(t0 + 60_000, s.rest!!.endsAt)
        assertNull(WorkoutOps.skipRest(s).rest)
        assertEquals(5, WorkoutOps.startRest(session(ex(BENCH)), 1, t0).rest!!.totalSeconds)
    }

    @Test fun editingADoneSetToInvalidUnmarksIt() {
        val s = session(ex(BENCH, done(60.0, 8, at = 5)))
        val ok = WorkoutOps.editSet(s, 0, 0) { it.copy(weightKg = 70.0) }
        assertTrue(ok.exercises[0].sets[0].done)
        val bad = WorkoutOps.editSet(s, 0, 0) { it.copy(reps = 0) }
        assertFalse(bad.exercises[0].sets[0].done)
        assertNull(bad.exercises[0].sets[0].completedAt)
    }

    @Test fun editSanitizesOutOfRangeValues() {
        val s = session(ex(BENCH, set()))
        val e = WorkoutOps.editSet(s, 0, 0) { it.copy(weightKg = 9999.0, reps = -4, rpe = 8.3) }.exercises[0].sets[0]
        assertNull(e.weightKg); assertNull(e.reps); assertNull(e.rpe)
        assertEquals(s, WorkoutOps.editSet(s, 3, 0) { it.copy(reps = 1) })
    }

    @Test fun addAndRemoveSetsRespectLimits() {
        var s = session(ex(BENCH, done(60.0, 8)))
        s = WorkoutOps.addSet(s, 0)
        assertEquals(2, s.exercises[0].sets.size)
        assertEquals("la serie nueva copia la última", 60.0, s.exercises[0].sets[1].weightKg!!, 0.0)
        assertFalse(s.exercises[0].sets[1].done)
        repeat(30) { s = WorkoutOps.addSet(s, 0) }
        assertEquals(WorkoutOps.MAX_SETS_PER_EXERCISE, s.exercises[0].sets.size)
        val one = session(ex(BENCH, set()))
        assertTrue("quitar la única serie quita el ejercicio", WorkoutOps.removeSet(one, 0, 0).exercises.isEmpty())
    }

    @Test fun removingExerciseDissolvesLonelySuperset() {
        val s = session(ex(BENCH, set(), superset = 1), ex(SQUAT, set(), superset = 1), ex(PUSHUP, set()))
        val r = WorkoutOps.removeExercise(s, 1)
        assertEquals(2, r.exercises.size)
        assertTrue(r.exercises.all { it.superset == null })
    }

    @Test fun addExerciseIsCappedAndUsesHistory() {
        val history = listOf(finished("h", t0, ex(SQUAT, done(100.0, 5))))
        val s = WorkoutOps.addExercise(session(), SQUAT, history, 120)
        assertEquals(3, s.exercises[0].sets.size)
        assertEquals(100.0, s.exercises[0].sets[0].weightKg!!, 0.0)
        var many = session()
        repeat(30) { many = WorkoutOps.addExercise(many, BENCH, emptyList(), 60) }
        assertEquals(WorkoutOps.MAX_EXERCISES_PER_ROUTINE, many.exercises.size)
    }

    @Test fun finishKeepsOnlyDoneSetsAndDropsEmptyExercises() {
        val s = session(ex(BENCH, done(60.0, 8, at = t0 + 5 * MIN), set()), ex(SQUAT, set(), set()))
            .copy(rest = RestState(t0, t0 + 1, 1))
        val f = WorkoutOps.finish(s, t0 + 10 * MIN)!!
        assertEquals(1, f.exercises.size)
        assertEquals(1, f.exercises[0].sets.size)
        assertEquals(t0 + 10 * MIN, f.finishedAt)
        assertNull(f.rest)
    }

    @Test fun finishWithNothingDoneReturnsNull() {
        assertNull(WorkoutOps.finish(session(ex(BENCH, set(), set())), t0 + MIN))
        assertNull(WorkoutOps.finish(session(), t0 + MIN))
    }

    @Test fun finishNeverEndsBeforeItStartedOrBeforeTheLastSet() {
        val s = session(ex(BENCH, done(60.0, 8, at = t0 + 20 * MIN)))
        assertEquals(t0 + 20 * MIN, WorkoutOps.finish(s, t0 - 5 * MIN)!!.finishedAt)
    }

    @Test fun forgottenSessionOnlyCountsAMarginAfterTheLastSet() {
        val s = session(ex(BENCH, done(60.0, 8, at = t0 + 30 * MIN)))
        val f = WorkoutOps.finish(s, t0 + 10 * 60 * MIN)!!
        assertEquals(t0 + 45 * MIN, f.finishedAt)
        assertEquals(45, WorkoutOps.durationMinutes(f, 0))
        val soon = WorkoutOps.finish(s, t0 + 40 * MIN)!!
        assertEquals("cerrar enseguida usa la hora real", t0 + 40 * MIN, soon.finishedAt)
    }

    @Test fun qualifyingNeedsThreeWorkingSetsAndFiveMinutes() {
        fun sess(minutes: Int, vararg sets: SetEntry) = finished("q", t0 + minutes * MIN, ex(BENCH, *sets), minutes = minutes)
        val three = arrayOf(done(60.0, 8), done(60.0, 8), done(60.0, 8))
        assertTrue(WorkoutOps.qualifies(sess(30, *three)))
        assertFalse("solo 2 series", WorkoutOps.qualifies(sess(30, done(60.0, 8), done(60.0, 8))))
        assertFalse("calentamientos no cuentan", WorkoutOps.qualifies(sess(30, done(20.0, 8, type = SetType.WARMUP), done(20.0, 8, type = SetType.WARMUP), done(60.0, 8))))
        assertFalse("muy corto", WorkoutOps.qualifies(sess(4, *three)))
        assertFalse("sin terminar", WorkoutOps.qualifies(sess(30, *three).copy(finishedAt = null)))
    }

    @Test fun sessionVolumeAndDuration() {
        val f = finished("v", t0 + 60 * MIN, ex(BENCH, done(60.0, 8), done(20.0, 10, type = SetType.WARMUP)), ex(PUSHUP, done(null, 12)), minutes = 60)
        assertEquals(480.0, WorkoutOps.volumeKg(f), 0.0)
        assertEquals(60, WorkoutOps.durationMinutes(f, 0))
        assertEquals(2, WorkoutOps.workingSets(f))
        assertEquals(3, WorkoutOps.doneSets(f))
        val active = session(ex(BENCH)).copy(startedAt = t0)
        assertEquals(10, WorkoutOps.durationMinutes(active, t0 + 10 * MIN))
        assertEquals(0, WorkoutOps.durationMinutes(active, t0 - 5 * MIN))
    }
}

class WorkoutStatsTest {
    private val zone = ZoneOffset.UTC
    private val monday = 4L * DAY // 5-ene-1970, lunes

    private fun at(dayOffset: Int, hour: Int = 10) = monday + dayOffset * DAY + hour * 60 * MIN

    @Test fun weekIndexIsMondayAligned() {
        assertEquals(1L, WorkoutWeeks.weekIndex(4))
        assertEquals(1L, WorkoutWeeks.weekIndex(10))
        assertEquals("el domingo cierra la semana anterior", 0L, WorkoutWeeks.weekIndex(3))
        assertEquals(2L, WorkoutWeeks.weekIndex(11))
        assertEquals(4L, WorkoutWeeks.startDay(1))
        assertEquals(0L, WorkoutWeeks.weekIndex(-3))
        assertEquals(-1L, WorkoutWeeks.weekIndex(-4))
    }

    @Test fun lastSetsUsesMostRecentSessionWithTheExercise() {
        val history = listOf(
            finished("a", at(0), ex(BENCH, done(50.0, 10))),
            finished("b", at(3), ex(BENCH, done(60.0, 8))),
            finished("c", at(5), ex(SQUAT, done(100.0, 5))),
        )
        assertEquals(60.0, WorkoutStats.lastSets(history, "bench_press").single().weightKg!!, 0.0)
        assertTrue(WorkoutStats.lastSets(history, "plank").isEmpty())
    }

    @Test fun lastSetsIgnoresUnfinishedAndWarmupOnly() {
        val unfinished = finished("u", at(2), ex(BENCH, done(90.0, 5))).copy(finishedAt = null)
        val warm = finished("w", at(1), ex(BENCH, done(20.0, 10, type = SetType.WARMUP)))
        assertTrue(WorkoutStats.lastSets(listOf(unfinished, warm), "bench_press").isEmpty())
    }

    @Test fun sessionValuesPerMetric() {
        val s = finished("s", at(0), ex(BENCH, done(100.0, 5), done(80.0, 10), done(20.0, 15, type = SetType.WARMUP)))
        assertEquals(100.0, WorkoutStats.sessionValue(s, "bench_press", Metric.MAX_WEIGHT)!!, 0.0)
        assertEquals(116.67, WorkoutStats.sessionValue(s, "bench_press", Metric.E1RM)!!, 0.01)
        assertEquals(800.0, WorkoutStats.sessionValue(s, "bench_press", Metric.SET_VOLUME)!!, 0.0)
        assertEquals(1300.0, WorkoutStats.sessionValue(s, "bench_press", Metric.SESSION_VOLUME)!!, 0.0)
        assertNull(WorkoutStats.sessionValue(s, "squat", Metric.MAX_WEIGHT))
    }

    @Test fun bodyweightAndDurationMetrics() {
        val s = finished("s", at(0), ex(PUSHUP, done(null, 12), done(null, 15)), ex(PLANK, SetEntry(seconds = 60, done = true, completedAt = 1)))
        assertEquals(15.0, WorkoutStats.sessionValue(s, "pushup", Metric.MAX_REPS)!!, 0.0)
        assertEquals(60.0, WorkoutStats.sessionValue(s, "plank", Metric.LONGEST)!!, 0.0)
    }

    @Test fun firstTimeDoesNotCountAsRecord() {
        val s = finished("s", at(0), ex(BENCH, done(60.0, 8)))
        assertTrue(WorkoutStats.newRecords(emptyList(), s).isEmpty())
    }

    @Test fun beatingThePastIsARecordAndTyingIsNot() {
        val past = listOf(finished("p", at(0), ex(BENCH, done(60.0, 8))))
        val better = finished("s", at(3), ex(BENCH, done(62.5, 8)))
        val records = WorkoutStats.newRecords(past, better)
        assertEquals(1, records.size)
        assertTrue(Metric.MAX_WEIGHT in records[0].metrics)
        assertTrue(Metric.E1RM in records[0].metrics)
        val same = finished("t", at(4), ex(BENCH, done(60.0, 8)))
        assertTrue(WorkoutStats.newRecords(past, same).isEmpty())
        val lighter = finished("u", at(5), ex(BENCH, done(50.0, 8)))
        assertTrue(WorkoutStats.newRecords(past, lighter).isEmpty())
    }

    @Test fun recordsCountPerExerciseNotPerMetricAndWarmupsNeverCount() {
        val past = listOf(finished("p", at(0), ex(BENCH, done(60.0, 8)), ex(SQUAT, done(100.0, 5))))
        val s = finished("s", at(3), ex(BENCH, done(70.0, 8), done(20.0, 5, type = SetType.WARMUP)), ex(SQUAT, done(100.0, 5)))
        assertEquals(listOf("bench_press"), WorkoutStats.newRecords(past, s).map { it.ref.id })
        val warmOnly = finished("w", at(4), ex(SQUAT, done(200.0, 5, type = SetType.WARMUP)))
        assertTrue(WorkoutStats.newRecords(past, warmOnly).isEmpty())
    }

    @Test fun theSessionBeingEvaluatedIsNotItsOwnPast() {
        val s = finished("s", at(3), ex(BENCH, done(70.0, 8)))
        assertTrue(WorkoutStats.newRecords(listOf(s), s).isEmpty())
    }

    @Test fun seriesIsChronologicalAndLocalDay() {
        val history = listOf(finished("b", at(3), ex(BENCH, done(60.0, 8))), finished("a", at(0), ex(BENCH, done(50.0, 8))))
        val points = WorkoutStats.series(history, "bench_press", Metric.MAX_WEIGHT, zone)
        assertEquals(listOf(50.0, 60.0), points.map { it.value })
        assertEquals(listOf(4L, 7L), points.map { it.day })
    }

    @Test fun weekSummaryCountsDistinctQualifyingDays() {
        fun q(id: String, day: Int, hour: Int) = finished(id, at(day, hour), ex(BENCH, done(60.0, 8), done(60.0, 8), done(60.0, 8)))
        val history = listOf(q("a", 0, 8), q("b", 0, 18), q("c", 2, 10), q("old", -7, 10),
            finished("tiny", at(3), ex(BENCH, done(60.0, 8)), minutes = 20))
        val w = WorkoutStats.weekSummary(history, 1, zone)
        assertEquals("dos entrenos el mismo día cuentan un día", 2, w.daysTrained)
        assertEquals(4, w.sessions)
        assertEquals(10, w.sets)
        assertEquals(4800.0, w.volumeKg, 0.0)
        assertEquals(155, w.minutes)
        assertEquals("la semana anterior solo tiene el entreno viejo", 1, WorkoutStats.weekSummary(history, 0, zone).sessions)
    }

    @Test fun weeklyVolumesCoverEmptyWeeks() {
        val history = listOf(finished("a", at(0), ex(BENCH, done(100.0, 10))))
        val v = WorkoutStats.weeklyVolumes(history, 3, 4, zone)
        assertEquals(listOf(0L, 1L, 2L, 3L), v.map { it.first })
        assertEquals(1000.0, v.first { it.first == 1L }.second, 0.0)
        assertEquals(0.0, v.first { it.first == 3L }.second, 0.0)
    }
}

class RotationAndPlatesTest {
    private fun r(id: String, pos: Int) = Routine(id, id, pos, emptyList())

    @Test fun firstWhenNothingDoneAndNullWhenNoRoutines() {
        assertNull(Rotation.next(emptyList(), emptyList()))
        assertEquals("a", Rotation.next(listOf(r("b", 1), r("a", 0)), emptyList())!!.id)
    }

    @Test fun nextAfterLastDoneAndWrapsAround() {
        val routines = listOf(r("a", 0), r("b", 1), r("c", 2))
        val h1 = listOf(finished("1", 10 * DAY, ex(BENCH, done(1.0, 1)), routineId = "a"))
        assertEquals("b", Rotation.next(routines, h1)!!.id)
        val h2 = h1 + finished("2", 12 * DAY, ex(BENCH, done(1.0, 1)), routineId = "c")
        assertEquals("a", Rotation.next(routines, h2)!!.id)
    }

    @Test fun skippingTheDayOfTheWeekDoesNotSkipTheRoutine() {
        val routines = listOf(r("a", 0), r("b", 1))
        val history = listOf(finished("1", 10 * DAY, ex(BENCH, done(1.0, 1)), routineId = "a"))
        // Pasan semanas sin entrenar: sigue tocando "b".
        assertEquals("b", Rotation.next(routines, history)!!.id)
    }

    @Test fun deletedRoutineInHistoryFallsBackToFirstAndFreeWorkoutsAreIgnored() {
        val routines = listOf(r("a", 0), r("b", 1))
        val ghost = listOf(finished("1", 10 * DAY, ex(BENCH, done(1.0, 1)), routineId = "gone"))
        assertEquals("a", Rotation.next(routines, ghost)!!.id)
        val free = ghost + finished("2", 11 * DAY, ex(BENCH, done(1.0, 1)))
        assertEquals("a", Rotation.next(routines, free)!!.id)
    }

    @Test fun platesPerSideGreedy() {
        val r = PlateCalculator.perSide(100.0, 20.0)!!
        assertEquals(listOf(25.0 to 1, 15.0 to 1), r.perSide)
        assertEquals(100.0, r.loadedKg, 0.0)
        assertEquals(0.0, r.remainderKg, 0.0)
        assertEquals("25 + 15", PlateCalculator.describe(r))
    }

    @Test fun platesRepeatedAndSmall() {
        val r = PlateCalculator.perSide(142.5, 20.0)!!
        assertEquals(listOf(25.0 to 2, 10.0 to 1, 1.25 to 1), r.perSide)
        assertEquals(0.0, r.remainderKg, 0.0)
        assertEquals("2 × 25 + 10 + 1,25", PlateCalculator.describe(r))
    }

    @Test fun platesRemainderWhenNotExact() {
        val r = PlateCalculator.perSide(61.0, 20.0)!!
        assertEquals(60.0, r.loadedKg, 0.0)
        assertEquals(1.0, r.remainderKg, 0.001)
    }

    @Test fun platesBarOnlyAndInvalid() {
        assertEquals("Solo la barra", PlateCalculator.describe(PlateCalculator.perSide(20.0, 20.0)!!))
        assertNull("menos que la barra", PlateCalculator.perSide(15.0, 20.0))
        assertNull(PlateCalculator.perSide(Double.NaN, 20.0))
        assertNull(PlateCalculator.perSide(5000.0, 20.0))
    }

    @Test fun displayGoalStreakExpiresAfterAMissedWeek() {
        val p = com.hermesmarcano.zen.data.Progress(goalWeekStreak = 3, lastGoalWeek = 10)
        assertEquals(3, WorkoutWeeks.displayGoalStreak(p, 10))
        assertEquals(3, WorkoutWeeks.displayGoalStreak(p, 11))
        assertEquals(0, WorkoutWeeks.displayGoalStreak(p, 12))
        assertEquals("un reloj que retrocede", 0, WorkoutWeeks.displayGoalStreak(p, 9))
        assertEquals(0, WorkoutWeeks.displayGoalStreak(com.hermesmarcano.zen.data.Progress(), 5))
    }
}
