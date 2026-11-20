package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.ExerciseLibrary
import com.hermesmarcano.zen.core.WorkoutCsv
import com.hermesmarcano.zen.core.WorkoutOps
import com.hermesmarcano.zen.core.WorkoutStats
import com.hermesmarcano.zen.core.WorkoutWeeks
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Lo que pasó al terminar un entrenamiento: para el resumen en pantalla. */
data class WorkoutFinish(
    val session: WorkoutSession,
    val records: List<WorkoutStats.NewRecord>,
    val events: GameEvents,
    val qualified: Boolean,
    val daysThisWeek: Int,
    val weeklyGoal: Int,
    /** El entrenamiento se guardó pero no se pudo guardar el XP (disco lleno): no se debe decir que "ya sumó". */
    val xpNotSaved: Boolean = false,
)

/** Acciones sobre el almacén de entrenamientos (rutinas, sesión en curso, historial). Todas guardan; pueden lanzar [IOException]. */
object WorkoutActions {
    // ---------- Rutinas y ejercicios propios ----------

    @Throws(IOException::class)
    fun addRoutine(repo: WorkoutRepository, name: String, items: List<RoutineExercise> = emptyList()): WorkoutOps.RoutineResult {
        var result: WorkoutOps.RoutineResult = WorkoutOps.RoutineResult.Blank
        repo.update { d ->
            result = WorkoutOps.addRoutine(d.routines, name, UUID.randomUUID().toString(), items)
            (result as? WorkoutOps.RoutineResult.Ok)?.let { d.copy(routines = it.routines) } ?: d
        }
        return result
    }

    @Throws(IOException::class)
    fun updateRoutine(repo: WorkoutRepository, id: String, name: String, items: List<RoutineExercise>): WorkoutOps.RoutineResult {
        var result: WorkoutOps.RoutineResult = WorkoutOps.RoutineResult.Blank
        repo.update { d ->
            result = WorkoutOps.updateRoutine(d.routines, id, name, items)
            (result as? WorkoutOps.RoutineResult.Ok)?.let { d.copy(routines = it.routines) } ?: d
        }
        return result
    }

    @Throws(IOException::class)
    fun deleteRoutine(repo: WorkoutRepository, id: String): Routine? {
        val removed = repo.data.value.routine(id)
        repo.update { it.copy(routines = WorkoutOps.deleteRoutine(it.routines, id)) }
        return removed
    }

    /** Deshace un borrado: vuelve a la posición que tenía, si cabe. */
    @Throws(IOException::class)
    fun restoreRoutine(repo: WorkoutRepository, routine: Routine): Boolean {
        var ok = false
        repo.update { d ->
            if (d.routines.size >= WorkoutOps.MAX_ROUTINES || d.routines.any { it.id == routine.id }) d
            else {
                ok = true
                val list = d.routines.sortedBy { it.position }.toMutableList()
                list.add(routine.position.coerceIn(0, list.size), routine)
                d.copy(routines = list.mapIndexed { i, r -> r.copy(position = i) })
            }
        }
        return ok
    }

    @Throws(IOException::class)
    fun moveRoutine(repo: WorkoutRepository, from: Int, to: Int) {
        repo.update { it.copy(routines = WorkoutOps.moveRoutine(it.routines, from, to)) }
    }

    /** Crea las tres rutinas de plantilla (empuje/tirón/piernas) si caben. Devuelve cuántas se crearon. */
    @Throws(IOException::class)
    fun addStarterRoutines(repo: WorkoutRepository): Int {
        var created = 0
        repo.update { d ->
            var routines = d.routines
            com.hermesmarcano.zen.core.StarterRoutines.pushPullLegs.forEach { t ->
                val r = WorkoutOps.addRoutine(routines, t.name, UUID.randomUUID().toString(), t.items)
                if (r is WorkoutOps.RoutineResult.Ok) { routines = r.routines; created++ }
            }
            d.copy(routines = routines)
        }
        return created
    }

    @Throws(IOException::class)
    fun addCustomExercise(repo: WorkoutRepository, name: String, muscle: MuscleGroup, kind: ExerciseKind): ExerciseLibrary.AddResult {
        var result: ExerciseLibrary.AddResult = ExerciseLibrary.AddResult.Blank
        repo.update { d ->
            result = ExerciseLibrary.addCustom(d.customExercises, name, muscle, kind, UUID.randomUUID().toString())
            (result as? ExerciseLibrary.AddResult.Added)?.let { d.copy(customExercises = it.list) } ?: d
        }
        return result
    }

    /** Borra un ejercicio propio de la biblioteca. Rutinas e historial conservan su copia. */
    @Throws(IOException::class)
    fun deleteCustomExercise(repo: WorkoutRepository, id: String) {
        repo.update { d -> d.copy(customExercises = d.customExercises.filterNot { it.id == id }) }
    }

    @Throws(IOException::class)
    fun setWeeklyGoal(repo: WorkoutRepository, days: Int) {
        repo.update { it.copy(weeklyGoal = days.coerceIn(WorkoutData.MIN_WEEKLY_GOAL, WorkoutData.MAX_WEEKLY_GOAL)) }
    }

    @Throws(IOException::class)
    fun setDefaultRest(repo: WorkoutRepository, seconds: Int) {
        repo.update { it.copy(defaultRestSeconds = seconds.coerceIn(WorkoutOps.MIN_REST_SECONDS, WorkoutOps.MAX_REST_SECONDS)) }
    }

    @Throws(IOException::class)
    fun setBar(repo: WorkoutRepository, kg: Double) {
        repo.update { d -> if (kg.isNaN() || kg < 0.0 || kg > 50.0) d else d.copy(barKg = kg) }
    }

    // ---------- Sesión en curso ----------

    /** Empieza una sesión (o devuelve la que ya estaba en curso: solo hay una a la vez). */
    @Throws(IOException::class)
    fun start(repo: WorkoutRepository, routineId: String?, now: Long): WorkoutSession {
        var started: WorkoutSession? = null
        val next = repo.update { d ->
            if (d.active != null) d
            else {
                val session = WorkoutOps.start(d.routine(routineId), d.history, UUID.randomUUID().toString(), now)
                started = session
                d.copy(active = session)
            }
        }
        return started ?: checkNotNull(next.active)
    }

    /** Cambia la sesión en curso; si no hay, no hace nada. */
    @Throws(IOException::class)
    fun edit(repo: WorkoutRepository, change: (WorkoutSession) -> WorkoutSession) {
        repo.update { d -> d.active?.let { d.copy(active = change(it)) } ?: d }
    }

    @Throws(IOException::class)
    fun toggleSet(repo: WorkoutRepository, exercise: Int, set: Int, now: Long): WorkoutOps.CompleteResult {
        var result: WorkoutOps.CompleteResult = WorkoutOps.CompleteResult.Invalid
        repo.update { d ->
            val active = d.active ?: return@update d
            result = WorkoutOps.toggleSet(active, exercise, set, now)
            (result as? WorkoutOps.CompleteResult.Done)?.let { d.copy(active = it.session) } ?: d
        }
        return result
    }

    @Throws(IOException::class)
    fun discard(repo: WorkoutRepository) {
        repo.update { it.copy(active = null) }
    }

    /**
     * Termina la sesión en curso: la guarda en el historial (solo series hechas), calcula récords y
     * da XP/logros/objetivo semanal. `null` si no hay sesión o no tiene ninguna serie hecha (no se
     * guarda nada; la sesión sigue en curso para que el usuario decida descartarla).
     *
     * La sesión se guarda **antes** de dar el XP: si algo falla entre medias se pierde como mucho el XP,
     * nunca el entrenamiento, y reintentar no puede dar XP dos veces (la sesión en curso ya no existe).
     */
    @Throws(IOException::class)
    fun finish(workouts: WorkoutRepository, state: StateRepository, now: Long, zone: ZoneId = ZoneId.systemDefault()): WorkoutFinish? =
        synchronized(workouts) {
            val data = workouts.data.value
            val active = data.active ?: return null
            val closed = WorkoutOps.finish(active, now) ?: return null
            val records = WorkoutStats.newRecords(data.history, closed)
            val saved = closed.copy(prs = records.size)
            val history = (data.history.filterNot { it.id == saved.id } + saved).sortedBy { it.finishedAt ?: it.startedAt }
            workouts.update { it.copy(history = history, active = null) }

            val day = LocalDate.ofEpochDay(WorkoutWeeks.dayOf(saved.finishedAt ?: now, zone))
            val week = WorkoutStats.weekSummary(history, WorkoutWeeks.weekIndex(day.toEpochDay()), zone)
            val qualified = WorkoutOps.qualifies(saved)
            val outcome = WorkoutOutcome(qualified, WorkoutOps.volumeKg(saved), records.size, week.daysTrained, data.weeklyGoal)
            var events = GameEvents()
            var xpNotSaved = false
            try {
                var computed = GameEvents()
                state.update { s -> GameEngine.recordWorkout(s, outcome, day).also { computed = it.events }.state }
                events = computed // solo si se guardó: no se anuncia un XP que no existe
            } catch (_: IOException) {
                // El entrenamiento ya está guardado; solo se pierde el XP de este.
                xpNotSaved = qualified
            }
            WorkoutFinish(saved, records, events, qualified, week.daysTrained, data.weeklyGoal, xpNotSaved)
        }

    // ---------- Historial ----------

    @Throws(IOException::class)
    fun deleteSession(repo: WorkoutRepository, id: String): WorkoutSession? {
        val removed = repo.data.value.history.firstOrNull { it.id == id }
        repo.update { d -> d.copy(history = d.history.filterNot { it.id == id }) }
        return removed
    }

    @Throws(IOException::class)
    fun restoreSession(repo: WorkoutRepository, session: WorkoutSession) {
        repo.update { d ->
            if (d.history.any { it.id == session.id }) d
            else d.copy(history = (d.history + session).sortedBy { it.finishedAt ?: it.startedAt })
        }
    }

    /** Importa sesiones desde CSV: añade las que no estaban. Devuelve (añadidas, filas descartadas, problema). */
    @Throws(IOException::class)
    fun importCsv(repo: WorkoutRepository, text: String): Triple<Int, Int, String?> {
        val parsed = WorkoutCsv.parse(text)
        if (parsed.problem != null) return Triple(0, parsed.skippedRows, parsed.problem)
        var added = 0
        repo.update { d ->
            val (history, n) = WorkoutCsv.merge(d.history, parsed.sessions)
            added = n
            d.copy(history = history, customExercises = WorkoutCsv.customFrom(parsed.sessions.filter { s -> history.any { it.id == s.id } }, d.customExercises))
        }
        return Triple(added, parsed.skippedRows, null)
    }
}
