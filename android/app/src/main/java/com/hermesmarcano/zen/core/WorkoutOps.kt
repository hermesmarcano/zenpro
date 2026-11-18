package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.ExerciseKind
import com.hermesmarcano.zen.data.ExerciseRef
import com.hermesmarcano.zen.data.RestState
import com.hermesmarcano.zen.data.Routine
import com.hermesmarcano.zen.data.RoutineExercise
import com.hermesmarcano.zen.data.SessionExercise
import com.hermesmarcano.zen.data.SetEntry
import com.hermesmarcano.zen.data.SetType
import com.hermesmarcano.zen.data.WorkoutSession

/**
 * Operaciones puras sobre rutinas y sesiones (nada de Android, el reloj lo pone quien llama).
 * Cada una devuelve la lista/sesión nueva; si la petición no es válida devuelve lo mismo que recibió.
 */
object WorkoutOps {
    const val MAX_ROUTINES = 30
    const val MAX_EXERCISES_PER_ROUTINE = 20
    const val MAX_SETS_PER_EXERCISE = 20
    const val ROUTINE_NAME_MAX = 40
    const val NOTE_MAX = 500
    const val MIN_REST_SECONDS = 0
    const val MAX_REST_SECONDS = 600
    const val REST_STEP_SECONDS = 15
    private const val MIN_ADJUSTED_REST_MILLIS = 5_000L

    /** Si pasó más de esto entre dos series, no se considera "descanso" sino que se hizo otra cosa. */
    private const val MAX_PLAUSIBLE_REST_MILLIS = 30L * 60_000L

    // ---------- Rutinas ----------

    fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    sealed interface RoutineResult {
        data class Ok(val routines: List<Routine>, val routine: Routine) : RoutineResult
        data object Blank : RoutineResult
        data object TooLong : RoutineResult
        data object Full : RoutineResult
    }

    fun addRoutine(all: List<Routine>, rawName: String, id: String, items: List<RoutineExercise> = emptyList()): RoutineResult {
        val name = cleanName(rawName)
        if (name.isEmpty()) return RoutineResult.Blank
        if (name.length > ROUTINE_NAME_MAX) return RoutineResult.TooLong
        if (all.size >= MAX_ROUTINES) return RoutineResult.Full
        val routine = Routine(id, name, all.size, normalizeSupersets(items.take(MAX_EXERCISES_PER_ROUTINE).map(::clampItem)))
        return RoutineResult.Ok(all + routine, routine)
    }

    /** Sustituye nombre y ejercicios de una rutina existente (editor de rutina). */
    fun updateRoutine(all: List<Routine>, id: String, rawName: String, items: List<RoutineExercise>): RoutineResult {
        val name = cleanName(rawName)
        if (name.isEmpty()) return RoutineResult.Blank
        if (name.length > ROUTINE_NAME_MAX) return RoutineResult.TooLong
        val current = all.firstOrNull { it.id == id } ?: return RoutineResult.Blank
        val next = current.copy(name = name, items = normalizeSupersets(items.take(MAX_EXERCISES_PER_ROUTINE).map(::clampItem)))
        return RoutineResult.Ok(all.map { if (it.id == id) next else it }, next)
    }

    fun deleteRoutine(all: List<Routine>, id: String): List<Routine> =
        all.filterNot { it.id == id }.sortedBy { it.position }.mapIndexed { i, r -> r.copy(position = i) }

    fun moveRoutine(all: List<Routine>, from: Int, to: Int): List<Routine> {
        val list = all.sortedBy { it.position }.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return all
        list.add(to, list.removeAt(from))
        return list.mapIndexed { i, r -> r.copy(position = i) }
    }

    private fun clampItem(item: RoutineExercise) =
        item.copy(sets = item.sets.coerceIn(1, MAX_SETS_PER_EXERCISE), restSeconds = item.restSeconds.coerceIn(MIN_REST_SECONDS, MAX_REST_SECONDS))

    /**
     * Un superset necesita al menos dos ejercicios **contiguos** con el mismo grupo: un grupo suelto
     * (tras borrar el compañero) o separado por otro ejercicio se deshace.
     */
    fun normalizeSupersets(items: List<RoutineExercise>): List<RoutineExercise> {
        val out = items.toMutableList()
        var i = 0
        while (i < out.size) {
            val group = out[i].superset
            var j = i
            while (j + 1 < out.size && group != null && out[j + 1].superset == group) j++
            if (group != null && j == i) out[i] = out[i].copy(superset = null)
            i = j + 1
        }
        // Grupos repetidos en tramos distintos: se renumeran para que cada tramo sea un grupo propio.
        var next = 1
        var k = 0
        while (k < out.size) {
            val group = out[k].superset
            if (group == null) { k++; continue }
            var end = k
            while (end + 1 < out.size && out[end + 1].superset == group) end++
            for (n in k..end) out[n] = out[n].copy(superset = next)
            next++
            k = end + 1
        }
        return out
    }

    /** Une el ejercicio [index] con el siguiente en un superset (o lo separa si ya lo estaba). */
    fun toggleSupersetWithNext(items: List<RoutineExercise>, index: Int): List<RoutineExercise> {
        if (index !in 0 until items.lastIndex) return items
        val a = items[index]
        val b = items[index + 1]
        val joined = a.superset != null && a.superset == b.superset
        val out = items.toMutableList()
        if (joined) {
            out[index + 1] = b.copy(superset = null)
        } else {
            val group = a.superset ?: (items.mapNotNull { it.superset }.maxOrNull() ?: 0) + 1
            out[index] = a.copy(superset = group)
            out[index + 1] = b.copy(superset = group)
        }
        return normalizeSupersets(out)
    }

    fun <T> moveItem(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val out = list.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }

    // ---------- Sesión ----------

    /**
     * Empieza una sesión desde [routine] (o vacía si es `null`). Cada serie sale rellenada con lo que se
     * hizo la última vez de ese ejercicio (ver [WorkoutStats.lastSets]); sin datos previos, vacía.
     */
    fun start(routine: Routine?, history: List<WorkoutSession>, id: String, now: Long): WorkoutSession {
        val exercises = routine?.items.orEmpty().map { item ->
            SessionExercise(item.ref, item.restSeconds, item.superset, blankSets(item.ref, item.sets, history))
        }
        return WorkoutSession(id, routine?.id, routine?.name ?: FREE_WORKOUT_NAME, now, null, exercises)
    }

    const val FREE_WORKOUT_NAME = "Entrenamiento libre"

    private fun blankSets(ref: ExerciseRef, count: Int, history: List<WorkoutSession>): List<SetEntry> {
        val last = WorkoutStats.lastSets(history, ref.id)
        return List(count.coerceIn(1, MAX_SETS_PER_EXERCISE)) { i ->
            // Si la última vez hubo menos series, las extra copian la última serie conocida.
            val previous = last.getOrNull(i) ?: last.lastOrNull()
            SetEntry(
                weightKg = previous?.weightKg?.takeIf { ref.kind == ExerciseKind.WEIGHT_REPS },
                reps = previous?.reps?.takeIf { ref.kind != ExerciseKind.DURATION },
                seconds = previous?.seconds?.takeIf { ref.kind == ExerciseKind.DURATION },
            )
        }
    }

    private fun SessionExercise.withSet(setIndex: Int, change: (SetEntry) -> SetEntry): SessionExercise =
        if (setIndex !in sets.indices) this else copy(sets = sets.mapIndexed { i, s -> if (i == setIndex) change(s) else s })

    private fun WorkoutSession.withExercise(index: Int, change: (SessionExercise) -> SessionExercise): WorkoutSession =
        if (index !in exercises.indices) this else copy(exercises = exercises.mapIndexed { i, e -> if (i == index) change(e) else e })

    /** Cambia los valores de una serie. Editar una serie ya hecha la mantiene hecha si sigue siendo válida; si no, la desmarca. */
    fun editSet(session: WorkoutSession, exercise: Int, set: Int, change: (SetEntry) -> SetEntry): WorkoutSession =
        session.withExercise(exercise) { ex ->
            ex.withSet(set) { old ->
                val edited = sanitize(change(old))
                if (edited.done && !WorkoutNumbers.canComplete(ex.ref.kind, edited)) edited.copy(done = false, completedAt = null, restSeconds = null) else edited
            }
        }

    private fun sanitize(set: SetEntry): SetEntry = set.copy(
        weightKg = set.weightKg?.takeIf { !it.isNaN() && it in 0.0..WorkoutNumbers.MAX_WEIGHT_KG },
        reps = set.reps?.takeIf { it in 0..WorkoutNumbers.MAX_REPS },
        seconds = set.seconds?.takeIf { it in 0..WorkoutNumbers.MAX_SECONDS },
        rpe = set.rpe?.takeIf { WorkoutNumbers.isValidRpe(it) },
    )

    sealed interface CompleteResult {
        data class Done(val session: WorkoutSession, val restStarted: RestState?) : CompleteResult
        /** Faltan datos (repeticiones, peso o tiempo) para darla por hecha. */
        data object Invalid : CompleteResult
    }

    /**
     * Marca la serie como hecha. Guarda el descanso real desde la serie anterior y, si procede, arranca el
     * cronómetro de descanso (ver [restFor]). Marcarla otra vez la desmarca y apaga un descanso en curso.
     */
    fun toggleSet(session: WorkoutSession, exercise: Int, set: Int, now: Long): CompleteResult {
        val ex = session.exercises.getOrNull(exercise) ?: return CompleteResult.Invalid
        val entry = ex.sets.getOrNull(set) ?: return CompleteResult.Invalid
        if (entry.done) {
            val undone = session.withExercise(exercise) { it.withSet(set) { s -> s.copy(done = false, completedAt = null, restSeconds = null) } }
            // Solo se apaga el descanso si lo arrancó esta misma serie; desmarcar una antigua no lo toca.
            val rest = session.rest?.takeIf { it.startedAt != entry.completedAt }
            return CompleteResult.Done(undone.copy(rest = rest), null)
        }
        if (!WorkoutNumbers.canComplete(ex.ref.kind, entry)) return CompleteResult.Invalid
        val previousAt = lastCompletedAt(session)
        val gap = previousAt?.let { now - it }
        val actualRest = gap?.takeIf { it in 1..MAX_PLAUSIBLE_REST_MILLIS }?.let { (it / 1000L).toInt() }
        val done = session.withExercise(exercise) { it.withSet(set) { s -> s.copy(done = true, completedAt = now, restSeconds = actualRest) } }
        val seconds = restFor(done, exercise)
        val rest = seconds?.let { RestState(now, now + it * 1000L, it) }
        return CompleteResult.Done(done.copy(rest = rest), rest)
    }

    private fun lastCompletedAt(session: WorkoutSession): Long? =
        session.exercises.flatMap { it.sets }.mapNotNull { if (it.done) it.completedAt else null }.maxOrNull()

    /**
     * Segundos de descanso tras completar una serie del ejercicio [exercise], o `null` si no toca descansar:
     * si el ejercicio no tiene descanso, si ya está todo hecho, o si es un superset y aún faltan ejercicios
     * del grupo por hacer en esta ronda (el descanso llega al terminar la ronda, con el mayor del grupo).
     */
    fun restFor(session: WorkoutSession, exercise: Int): Int? {
        val ex = session.exercises.getOrNull(exercise) ?: return null
        if (session.exercises.all { e -> e.sets.all { it.done } }) return null
        val group = if (ex.superset == null) listOf(exercise) else session.exercises.indices.filter { session.exercises[it].superset == ex.superset }
        if (group.size > 1) {
            val round = session.exercises[exercise].sets.count { it.done }
            val behind = group.any { g -> g != exercise && session.exercises[g].sets.size >= round && session.exercises[g].sets.count { it.done } < round }
            if (behind) return null
        }
        val seconds = group.maxOf { session.exercises[it].restSeconds }.coerceIn(MIN_REST_SECONDS, MAX_REST_SECONDS)
        return seconds.takeIf { it > 0 }
    }

    fun addSet(session: WorkoutSession, exercise: Int, type: SetType = SetType.NORMAL): WorkoutSession =
        session.withExercise(exercise) { ex ->
            if (ex.sets.size >= MAX_SETS_PER_EXERCISE) ex
            else {
                val last = ex.sets.lastOrNull { it.type == SetType.NORMAL } ?: ex.sets.lastOrNull()
                ex.copy(sets = ex.sets + SetEntry(weightKg = last?.weightKg, reps = last?.reps, seconds = last?.seconds, type = type))
            }
        }

    fun removeSet(session: WorkoutSession, exercise: Int, set: Int): WorkoutSession =
        session.withExercise(exercise) { ex -> if (set !in ex.sets.indices) ex else ex.copy(sets = ex.sets.filterIndexed { i, _ -> i != set }) }
            .let { s -> if (s.exercises.getOrNull(exercise)?.sets?.isEmpty() == true) removeExercise(s, exercise) else s }

    fun setSetType(session: WorkoutSession, exercise: Int, set: Int, type: SetType): WorkoutSession =
        session.withExercise(exercise) { it.withSet(set) { s -> s.copy(type = type) } }

    fun addExercise(session: WorkoutSession, ref: ExerciseRef, history: List<WorkoutSession>, restSeconds: Int, sets: Int = 3): WorkoutSession {
        if (session.exercises.size >= MAX_EXERCISES_PER_ROUTINE) return session
        val ex = SessionExercise(ref, restSeconds.coerceIn(MIN_REST_SECONDS, MAX_REST_SECONDS), null, blankSets(ref, sets, history))
        return session.copy(exercises = session.exercises + ex)
    }

    fun removeExercise(session: WorkoutSession, exercise: Int): WorkoutSession {
        if (exercise !in session.exercises.indices) return session
        val exercises = session.exercises.filterIndexed { i, _ -> i != exercise }
        val normalized = normalizeSessionSupersets(exercises)
        return session.copy(exercises = normalized, rest = if (normalized.all { e -> e.sets.all { it.done } }) null else session.rest)
    }

    /** Igual que [normalizeSupersets] pero sobre los ejercicios de una sesión. */
    fun normalizeSessionSupersets(list: List<SessionExercise>): List<SessionExercise> {
        val items = list.map { RoutineExercise(it.ref, 1, 0, it.superset) }
        val fixed = normalizeSupersets(items)
        return list.mapIndexed { i, e -> e.copy(superset = fixed[i].superset) }
    }

    fun setExerciseNote(session: WorkoutSession, exercise: Int, note: String): WorkoutSession =
        session.withExercise(exercise) { it.copy(note = note.take(NOTE_MAX)) }

    fun setSessionNote(session: WorkoutSession, note: String): WorkoutSession = session.copy(note = note.take(NOTE_MAX))

    fun setExerciseRest(session: WorkoutSession, exercise: Int, seconds: Int): WorkoutSession =
        session.withExercise(exercise) { it.copy(restSeconds = seconds.coerceIn(MIN_REST_SECONDS, MAX_REST_SECONDS)) }

    // ---------- Descanso ----------

    /** Suma o resta tiempo al descanso en curso (mínimo 5 s restantes); sin descanso no hace nada. */
    fun adjustRest(session: WorkoutSession, deltaSeconds: Int, now: Long): WorkoutSession {
        val rest = session.rest ?: return session
        val remaining = rest.remainingMillis(now)
        // Nunca menos de 5 s al acortar, pero acortar jamás alarga (si ya quedan menos de 5 s, se queda como está).
        val floor = if (deltaSeconds < 0) minOf(remaining, MIN_ADJUSTED_REST_MILLIS) else MIN_ADJUSTED_REST_MILLIS
        val newRemaining = (remaining + deltaSeconds * 1000L).coerceIn(floor, MAX_REST_SECONDS * 1000L)
        val total = (rest.totalSeconds + ((newRemaining - remaining) / 1000L).toInt()).coerceIn(5, MAX_REST_SECONDS)
        return session.copy(rest = RestState(rest.startedAt, now + newRemaining, total))
    }

    fun skipRest(session: WorkoutSession): WorkoutSession = session.copy(rest = null)

    /** Arranque manual del cronómetro (botón "Descansar") por [seconds]. */
    fun startRest(session: WorkoutSession, seconds: Int, now: Long): WorkoutSession {
        val s = seconds.coerceIn(5, MAX_REST_SECONDS)
        return session.copy(rest = RestState(now, now + s * 1000L, s))
    }

    // ---------- Terminar ----------

    /** Series hechas que cuentan como trabajo (sin calentamiento). */
    fun workingSets(session: WorkoutSession): Int =
        session.exercises.sumOf { ex -> ex.sets.count { it.done && it.type == SetType.NORMAL } }

    fun doneSets(session: WorkoutSession): Int = session.exercises.sumOf { ex -> ex.sets.count { it.done } }

    /**
     * La sesión tal como queda en el historial: solo series hechas, sin ejercicios vacíos, con hora de fin
     * y sin descanso en curso. `null` si no hay ninguna serie hecha (no hay nada que guardar).
     */
    fun finish(session: WorkoutSession, now: Long): WorkoutSession? {
        val exercises = session.exercises
            .map { ex -> ex.copy(sets = ex.sets.filter { it.done }) }
            .filter { it.sets.isNotEmpty() }
        if (exercises.isEmpty()) return null
        val last = exercises.flatMap { it.sets }.mapNotNull { it.completedAt }.maxOrNull()
        // La sesión termina "ahora", pero nunca antes de su última serie ni de su inicio (reloj que retrocede), y
        // si pasaron horas desde la última serie (se olvidó cerrarla) solo cuenta un margen para estirar y salir.
        val capped = if (last != null && now - last > IDLE_AFTER_LAST_SET_MILLIS) last + IDLE_AFTER_LAST_SET_MILLIS else now
        val end = maxOf(capped, session.startedAt, last ?: 0L)
        return session.copy(exercises = normalizeSessionSupersets(exercises), finishedAt = end, rest = null)
    }

    /** Tiempo que se cuenta tras la última serie hecha si se cierra la sesión mucho más tarde. */
    const val IDLE_AFTER_LAST_SET_MILLIS = 15L * 60_000L

    const val MIN_QUALIFYING_SETS = 3
    const val MIN_QUALIFYING_MINUTES = 5

    /** Un entrenamiento "cuenta" para XP y objetivo semanal con al menos 3 series de trabajo y 5 minutos. */
    fun qualifies(session: WorkoutSession): Boolean {
        val end = session.finishedAt ?: return false
        val minutes = (end - session.startedAt) / 60_000L
        return workingSets(session) >= MIN_QUALIFYING_SETS && minutes >= MIN_QUALIFYING_MINUTES
    }

    /** Minutos de la sesión; en curso (sin hora de fin) cuenta hasta [now]. */
    fun durationMinutes(session: WorkoutSession, now: Long): Int =
        (((session.finishedAt ?: now) - session.startedAt).coerceAtLeast(0L) / 60_000L).toInt()

    fun volumeKg(session: WorkoutSession): Double =
        session.exercises.sumOf { ex -> ex.sets.sumOf { WorkoutNumbers.setVolume(ex.ref.kind, it) } }
}
