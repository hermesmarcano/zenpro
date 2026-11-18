package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.ExerciseKind
import com.hermesmarcano.zen.data.ExerciseRef
import com.hermesmarcano.zen.data.MuscleGroup
import com.hermesmarcano.zen.data.SessionExercise
import com.hermesmarcano.zen.data.SetEntry
import com.hermesmarcano.zen.data.SetType
import com.hermesmarcano.zen.data.WorkoutSession
import java.time.Instant
import java.time.ZoneId

/**
 * Exportar/importar el historial como CSV (una fila por serie): la copia de seguridad del usuario, ya que la
 * app no usa la copia de Android. Compatible con una hoja de cálculo; el importador solo necesita las columnas
 * por nombre, en cualquier orden.
 */
object WorkoutCsv {
    const val MAX_SESSIONS = 5000
    const val MAX_ROWS = 400_000
    private const val BOM_CODE = 0xFEFF

    private val COLUMNS = listOf(
        "session_id", "date", "started_at", "finished_at", "routine_id", "routine", "session_note", "session_prs",
        "exercise_id", "exercise", "muscle", "kind", "exercise_note", "set", "type", "weight_kg", "reps", "seconds", "rpe", "rest_seconds",
    )

    fun export(history: List<WorkoutSession>, zone: ZoneId): String {
        val out = StringBuilder()
        out.append(COLUMNS.joinToString(",")).append("\r\n")
        for (s in history.filter { it.isFinished }.sortedBy { it.finishedAt }) {
            val date = Instant.ofEpochMilli(s.startedAt).atZone(zone).toLocalDate().toString()
            for (ex in s.exercises) {
                ex.sets.forEachIndexed { i, set ->
                    val row = listOf(
                        s.id, date, s.startedAt.toString(), (s.finishedAt ?: s.startedAt).toString(), s.routineId.orEmpty(), protect(s.routineName), protect(s.note), s.prs.toString(),
                        ex.ref.id, protect(ex.ref.name), ex.ref.muscle.id, ex.ref.kind.id, protect(ex.note), (i + 1).toString(), set.type.id,
                        set.weightKg?.let { WorkoutNumbers.formatKgPlain(it) }.orEmpty(), set.reps?.toString().orEmpty(), set.seconds?.toString().orEmpty(),
                        set.rpe?.let { WorkoutNumbers.formatKgPlain(it) }.orEmpty(), set.restSeconds?.toString().orEmpty(),
                    )
                    out.append(row.joinToString(",") { quote(it) }).append("\r\n")
                }
            }
        }
        return out.toString()
    }

    /**
     * Una celda de texto que empieza por = + - @ se evalúa como fórmula en Excel/Sheets: se le antepone una
     * comilla simple (y al importar se quita). Los números no pasan por aquí.
     */
    private fun protect(value: String): String =
        if (value.isNotEmpty() && value[0] in FORMULA_STARTS) "'$value" else value

    private fun unprotect(value: String): String =
        if (value.length > 1 && value[0] == '\'' && value[1] in FORMULA_STARTS) value.substring(1) else value

    private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')

    private fun quote(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** Divide el texto en filas y celdas (comillas, comillas dobles escapadas y saltos de línea dentro de una celda). */
    fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endCell() { row.add(cell.toString()); cell.setLength(0) }
        fun endRow() {
            endCell()
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(row) // línea en blanco
            row = mutableListOf()
        }
        // Las hojas de cálculo suelen añadir una marca de orden de bytes (BOM) al principio del archivo.
        val t = if (text.firstOrNull()?.code == BOM_CODE) text.substring(1) else text
        while (i < t.length) {
            val c = t[i]
            when {
                inQuotes && c == '"' && i + 1 < t.length && t[i + 1] == '"' -> { cell.append('"'); i++ }
                inQuotes && c == '"' -> inQuotes = false
                inQuotes -> cell.append(c)
                c == '"' && cell.isEmpty() -> inQuotes = true
                c == ',' -> endCell()
                c == '\r' -> {
                    if (i + 1 < t.length && t[i + 1] == '\n') i++
                    endRow()
                }
                c == '\n' -> endRow()
                else -> cell.append(c)
            }
            i++
            if (rows.size > MAX_ROWS) break
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    data class ImportResult(val sessions: List<WorkoutSession>, val skippedRows: Int, val problem: String? = null)

    private val REQUIRED = listOf("session_id", "finished_at", "exercise_id", "exercise", "kind", "set")

    fun parse(text: String): ImportResult {
        val rows = parseRows(text)
        if (rows.isEmpty()) return ImportResult(emptyList(), 0, "El archivo está vacío.")
        val header = rows[0].map { it.trim().lowercase() }
        val missing = REQUIRED.filter { it !in header }
        if (missing.isNotEmpty()) return ImportResult(emptyList(), 0, "No parece un archivo de ZEN: faltan columnas (${missing.joinToString()}).")
        val index = header.withIndex().associate { (i, name) -> name to i }
        fun cell(row: List<String>, name: String): String = index[name]?.let { row.getOrNull(it) }.orEmpty()

        class Builder(val id: String, val routineId: String?, val routineName: String, val startedAt: Long, val finishedAt: Long, val note: String, val prs: Int) {
            /** Clave = id del ejercicio + nº de aparición: el mismo ejercicio puede estar dos veces en una sesión. */
            val exercises = LinkedHashMap<String, Pair<ExerciseRef, MutableList<SetEntry>>>()
            val notes = HashMap<String, String>()
            val occurrence = HashMap<String, Int>()
            val lastSet = HashMap<String, Int>()
        }

        val sessions = LinkedHashMap<String, Builder>()
        var skipped = 0
        for (row in rows.drop(1)) {
            val sessionId = cell(row, "session_id").trim()
            val finishedAt = cell(row, "finished_at").trim().toLongOrNull()
            val exerciseId = cell(row, "exercise_id").trim()
            val name = ExerciseLibrary.cleanName(unprotect(cell(row, "exercise"))).take(ExerciseLibrary.NAME_MAX)
            if (sessionId.isEmpty() || finishedAt == null || finishedAt <= 0L || exerciseId.isEmpty() || name.isEmpty()) { skipped++; continue }
            val kind = ExerciseKind.fromId(cell(row, "kind").trim())
            val set = SetEntry(
                weightKg = cell(row, "weight_kg").takeIf { it.isNotBlank() }?.let { WorkoutNumbers.parseWeight(it) },
                reps = cell(row, "reps").takeIf { it.isNotBlank() }?.let { WorkoutNumbers.parseReps(it) },
                seconds = cell(row, "seconds").takeIf { it.isNotBlank() }?.let { WorkoutNumbers.parseSeconds(it) },
                type = SetType.fromId(cell(row, "type").trim()),
                rpe = cell(row, "rpe").takeIf { it.isNotBlank() }?.replace(',', '.')?.toDoubleOrNull()?.takeIf { WorkoutNumbers.isValidRpe(it) },
                done = true,
                completedAt = finishedAt,
                restSeconds = cell(row, "rest_seconds").takeIf { it.isNotBlank() }?.toIntOrNull()?.takeIf { it in 0..WorkoutOps.MAX_REST_SECONDS * 6 },
            )
            if (!WorkoutNumbers.canComplete(kind, set)) { skipped++; continue }
            if (sessionId !in sessions && sessions.size >= MAX_SESSIONS) { skipped++; continue }
            val builder = sessions.getOrPut(sessionId) {
                val started = cell(row, "started_at").trim().toLongOrNull()?.takeIf { it in 1..finishedAt } ?: finishedAt
                Builder(
                    sessionId, cell(row, "routine_id").trim().ifEmpty { null },
                    ExerciseLibrary.cleanName(unprotect(cell(row, "routine"))).take(WorkoutOps.ROUTINE_NAME_MAX).ifEmpty { WorkoutOps.FREE_WORKOUT_NAME },
                    started, finishedAt, unprotect(cell(row, "session_note")).take(WorkoutOps.NOTE_MAX),
                    cell(row, "session_prs").toIntOrNull()?.coerceIn(0, 999) ?: 0,
                )
            }
            val ref = ExerciseRef(exerciseId, name, MuscleGroup.fromId(cell(row, "muscle").trim()), kind)
            // La numeración de series reinicia en 1 con cada aparición del ejercicio: un número que no sube abre una nueva.
            val setNumber = cell(row, "set").trim().toIntOrNull() ?: ((builder.lastSet[exerciseId] ?: 0) + 1)
            if (setNumber <= (builder.lastSet[exerciseId] ?: 0)) builder.occurrence[exerciseId] = (builder.occurrence[exerciseId] ?: 0) + 1
            builder.lastSet[exerciseId] = setNumber
            val key = exerciseId + "#" + (builder.occurrence[exerciseId] ?: 0)
            val entry = builder.exercises.getOrPut(key) { ref to mutableListOf() }
            if (entry.second.size < WorkoutOps.MAX_SETS_PER_EXERCISE) entry.second.add(set) else skipped++
            cell(row, "exercise_note").takeIf { it.isNotBlank() }?.let { builder.notes[key] = unprotect(it).take(WorkoutOps.NOTE_MAX) }
        }
        val result = sessions.values.map { b ->
            WorkoutSession(
                id = b.id, routineId = b.routineId, routineName = b.routineName, startedAt = b.startedAt, finishedAt = b.finishedAt,
                exercises = b.exercises.entries.take(WorkoutOps.MAX_EXERCISES_PER_ROUTINE).map { (key, value) ->
                    SessionExercise(value.first, 0, null, value.second, b.notes[key].orEmpty())
                },
                note = b.note, prs = b.prs,
            )
        }
        return ImportResult(result, skipped)
    }

    /** Añade al historial las sesiones importadas que no estaban (por id). Devuelve el historial nuevo y cuántas se añadieron. */
    fun merge(existing: List<WorkoutSession>, imported: List<WorkoutSession>): Pair<List<WorkoutSession>, Int> {
        val known = existing.map { it.id }.toSet()
        val fresh = imported.filter { it.id !in known }.distinctBy { it.id }
        val room = (MAX_SESSIONS - existing.size).coerceAtLeast(0)
        val taken = fresh.take(room)
        return (existing + taken).sortedBy { it.finishedAt ?: it.startedAt } to taken.size
    }

    /** Ejercicios propios que aparecen en las sesiones importadas y aún no están en [custom]. */
    fun customFrom(sessions: List<WorkoutSession>, custom: List<ExerciseRef>): List<ExerciseRef> {
        val known = custom.map { it.id }.toSet()
        val extra = sessions.flatMap { s -> s.exercises.map { it.ref } }
            .filter { ExerciseLibrary.isCustom(it) && it.id !in known }.distinctBy { it.id }
        return (custom + extra).take(ExerciseLibrary.MAX_CUSTOM)
    }
}
