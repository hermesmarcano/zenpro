package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.ExerciseLibrary
import com.hermesmarcano.zen.core.WorkoutCsv
import com.hermesmarcano.zen.core.WorkoutNumbers
import com.hermesmarcano.zen.core.WorkoutOps
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persistencia de los entrenamientos en **dos** archivos con escritura atómica: `workouts.json` (rutinas,
 * ejercicios propios, ajustes e historial; crece con los años) y `active_workout.json` (solo la sesión en
 * curso, que se reescribe en cada serie). Así marcar una serie no reescribe años de historial.
 *
 * Si la app muere entre guardar el historial y borrar la sesión en curso, al arrancar se descarta la sesión
 * en curso que ya está en el historial. Un archivo ilegible se aparta como `.corrupt` y se arranca limpio.
 */
class WorkoutRepository(private val mainFile: File, private val activeFile: File) {
    // Perezoso: los receptores que levantan el proceso (alarmas, arranque) no leen el historial si no lo necesitan.
    private val flow by lazy { MutableStateFlow(load()) }
    val data: StateFlow<WorkoutData> get() = flow

    /** Aplica [transform] y guarda. Si la escritura falla, lanza y NO cambia el estado en memoria. */
    @Synchronized
    @Throws(IOException::class)
    fun update(transform: (WorkoutData) -> WorkoutData): WorkoutData {
        val current = flow.value
        val next = transform(current)
        if (next == current) return next
        val mainChanged = next.copy(active = null) != current.copy(active = null)
        // Primero el historial y luego la sesión en curso: si algo falla entre medias, no se pierde ningún entrenamiento.
        if (mainChanged) AtomicFiles.write(mainFile, WorkoutJson.encodeMain(next))
        if (next.active != current.active || (mainChanged && next.active == null)) {
            if (next.active == null) activeFile.delete() else AtomicFiles.write(activeFile, WorkoutJson.encodeSession(next.active))
        }
        flow.value = next
        return next
    }

    private fun load(): WorkoutData {
        var data = if (mainFile.exists()) AtomicFiles.readOrQuarantine(mainFile) { WorkoutJson.decodeMain(it) } ?: WorkoutData() else WorkoutData()
        val active = if (activeFile.exists()) AtomicFiles.readOrQuarantine(activeFile) { WorkoutJson.decodeSession(JSONObject(it)) } else null
        // Ya terminada y guardada en el historial: la copia en curso sobra.
        if (active != null && data.history.none { it.id == active.id }) data = data.copy(active = active.copy(finishedAt = null))
        else if (active != null) activeFile.delete()
        return data
    }
}

internal object WorkoutJson {
    private const val VERSION = 1

    fun encodeMain(d: WorkoutData): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("weeklyGoal", d.weeklyGoal)
        root.put("defaultRest", d.defaultRestSeconds)
        root.put("barKg", d.barKg)
        root.put("custom", JSONArray().also { a -> d.customExercises.forEach { a.put(encodeRef(it)) } })
        root.put(
            "routines",
            JSONArray().also { a ->
                d.routines.forEach { r ->
                    a.put(
                        JSONObject().put("id", r.id).put("name", r.name).put("position", r.position).put(
                            "items",
                            JSONArray().also { items ->
                                r.items.forEach { i ->
                                    items.put(
                                        JSONObject().put("ref", encodeRef(i.ref)).put("sets", i.sets).put("rest", i.restSeconds).put("superset", i.superset ?: JSONObject.NULL),
                                    )
                                }
                            },
                        ),
                    )
                }
            },
        )
        root.put("history", JSONArray().also { a -> d.history.forEach { a.put(encodeSessionJson(it)) } })
        return root.toString()
    }

    fun encodeSession(s: WorkoutSession): String = encodeSessionJson(s).toString()

    private fun encodeRef(r: ExerciseRef) =
        JSONObject().put("id", r.id).put("name", r.name).put("muscle", r.muscle.id).put("kind", r.kind.id)

    private fun encodeSessionJson(s: WorkoutSession): JSONObject = JSONObject()
        .put("id", s.id)
        .put("routineId", s.routineId ?: JSONObject.NULL)
        .put("routineName", s.routineName)
        .put("startedAt", s.startedAt)
        .put("finishedAt", s.finishedAt ?: JSONObject.NULL)
        .put("note", s.note)
        .put("prs", s.prs)
        .put("rest", s.rest?.let { JSONObject().put("startedAt", it.startedAt).put("endsAt", it.endsAt).put("total", it.totalSeconds) } ?: JSONObject.NULL)
        .put(
            "exercises",
            JSONArray().also { a ->
                s.exercises.forEach { e ->
                    a.put(
                        JSONObject().put("ref", encodeRef(e.ref)).put("rest", e.restSeconds).put("superset", e.superset ?: JSONObject.NULL).put("note", e.note).put(
                            "sets",
                            JSONArray().also { sets ->
                                e.sets.forEach { st ->
                                    sets.put(
                                        JSONObject()
                                            .put("w", st.weightKg ?: JSONObject.NULL)
                                            .put("r", st.reps ?: JSONObject.NULL)
                                            .put("s", st.seconds ?: JSONObject.NULL)
                                            .put("t", st.type.id)
                                            .put("rpe", st.rpe ?: JSONObject.NULL)
                                            .put("d", st.done)
                                            .put("at", st.completedAt ?: JSONObject.NULL)
                                            .put("rs", st.restSeconds ?: JSONObject.NULL),
                                    )
                                }
                            },
                        ),
                    )
                }
            },
        )

    @Throws(JSONException::class)
    fun decodeMain(text: String): WorkoutData {
        val root = JSONObject(text)
        val custom = root.optJSONArray("custom").objects().mapNotNull { decodeRef(it) }
            .filter { ExerciseLibrary.isCustom(it) }.distinctBy { it.id }.take(ExerciseLibrary.MAX_CUSTOM)
        val routines = root.optJSONArray("routines").objects().mapNotNull { r ->
            val id = r.optString("id"); val name = ExerciseLibrary.cleanName(r.optString("name"))
            if (id.isBlank() || name.isEmpty()) return@mapNotNull null
            val items = r.optJSONArray("items").objects().mapNotNull { i ->
                val ref = decodeRef(i.optJSONObject("ref")) ?: return@mapNotNull null
                RoutineExercise(ref, i.optInt("sets", 3).coerceIn(1, WorkoutOps.MAX_SETS_PER_EXERCISE),
                    i.optInt("rest", 90).coerceIn(WorkoutOps.MIN_REST_SECONDS, WorkoutOps.MAX_REST_SECONDS), intOrNull(i, "superset"))
            }.take(WorkoutOps.MAX_EXERCISES_PER_ROUTINE)
            Routine(id, name.take(WorkoutOps.ROUTINE_NAME_MAX), r.optInt("position"), WorkoutOps.normalizeSupersets(items))
        }.distinctBy { it.id }.sortedBy { it.position }.take(WorkoutOps.MAX_ROUTINES).mapIndexed { i, r -> r.copy(position = i) }
        val history = root.optJSONArray("history").objects().mapNotNull { decodeSession(it) }
            .filter { it.isFinished }.distinctBy { it.id }.sortedBy { it.finishedAt }.takeLast(WorkoutCsv.MAX_SESSIONS)
        return WorkoutData(
            routines = routines,
            customExercises = custom,
            history = history,
            active = null,
            weeklyGoal = root.optInt("weeklyGoal", WorkoutData.DEFAULT_WEEKLY_GOAL).coerceIn(WorkoutData.MIN_WEEKLY_GOAL, WorkoutData.MAX_WEEKLY_GOAL),
            defaultRestSeconds = root.optInt("defaultRest", WorkoutData.DEFAULT_REST_SECONDS).coerceIn(WorkoutOps.MIN_REST_SECONDS, WorkoutOps.MAX_REST_SECONDS),
            barKg = root.optDouble("barKg", WorkoutData.DEFAULT_BAR_KG).takeIf { !it.isNaN() && it in 0.0..50.0 } ?: WorkoutData.DEFAULT_BAR_KG,
        )
    }

    private fun decodeRef(o: JSONObject?): ExerciseRef? {
        if (o == null) return null
        val id = o.optString("id"); val name = ExerciseLibrary.cleanName(o.optString("name"))
        if (id.isBlank() || name.isEmpty()) return null
        return ExerciseRef(id, name.take(ExerciseLibrary.NAME_MAX), MuscleGroup.fromId(o.optString("muscle")), ExerciseKind.fromId(o.optString("kind")))
    }

    /** Sesión válida o `null`. Valores fuera de rango se descartan, no tumban la carga. */
    @Throws(JSONException::class)
    fun decodeSession(o: JSONObject): WorkoutSession? {
        val id = o.optString("id")
        if (id.isBlank()) return null
        val started = o.optLong("startedAt")
        val finished = if (o.isNull("finishedAt") || !o.has("finishedAt")) null else o.optLong("finishedAt")
        val exercises = o.optJSONArray("exercises").objects().mapNotNull { e ->
            val ref = decodeRef(e.optJSONObject("ref")) ?: return@mapNotNull null
            val sets = e.optJSONArray("sets").objects().map { s ->
                val entry = SetEntry(
                    weightKg = doubleOrNull(s, "w")?.takeIf { it in 0.0..WorkoutNumbers.MAX_WEIGHT_KG },
                    reps = intOrNull(s, "r")?.takeIf { it in 0..WorkoutNumbers.MAX_REPS },
                    seconds = intOrNull(s, "s")?.takeIf { it in 0..WorkoutNumbers.MAX_SECONDS },
                    type = SetType.fromId(s.optString("t")),
                    rpe = doubleOrNull(s, "rpe")?.takeIf { WorkoutNumbers.isValidRpe(it) },
                    done = s.optBoolean("d"),
                    completedAt = if (s.isNull("at") || !s.has("at")) null else s.optLong("at"),
                    restSeconds = intOrNull(s, "rs")?.takeIf { it >= 0 },
                )
                // Una serie "hecha" sin los datos mínimos (archivo editado a mano) vuelve a pendiente.
                if (entry.done && !WorkoutNumbers.canComplete(ref.kind, entry)) entry.copy(done = false, completedAt = null) else entry
            }.take(WorkoutOps.MAX_SETS_PER_EXERCISE)
            if (sets.isEmpty()) null
            else SessionExercise(ref, e.optInt("rest", 90).coerceIn(WorkoutOps.MIN_REST_SECONDS, WorkoutOps.MAX_REST_SECONDS), intOrNull(e, "superset"), sets, e.optString("note").take(WorkoutOps.NOTE_MAX))
        }.take(WorkoutOps.MAX_EXERCISES_PER_ROUTINE)
        val rest = o.optJSONObject("rest")?.let { r ->
            val total = r.optInt("total")
            if (total in 1..WorkoutOps.MAX_REST_SECONDS * 2) RestState(r.optLong("startedAt"), r.optLong("endsAt"), total) else null
        }
        return WorkoutSession(
            id = id,
            routineId = if (o.isNull("routineId")) null else o.optString("routineId").ifBlank { null },
            routineName = o.optString("routineName").ifBlank { WorkoutOps.FREE_WORKOUT_NAME }.take(WorkoutOps.ROUTINE_NAME_MAX),
            startedAt = started,
            finishedAt = finished?.let { maxOf(it, started) },
            exercises = WorkoutOps.normalizeSessionSupersets(exercises),
            note = o.optString("note").take(WorkoutOps.NOTE_MAX),
            rest = rest,
            prs = o.optInt("prs").coerceAtLeast(0),
        )
    }

    private fun intOrNull(o: JSONObject, key: String): Int? = if (!o.has(key) || o.isNull(key)) null else o.optInt(key)
    private fun doubleOrNull(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() && !it.isInfinite() }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
