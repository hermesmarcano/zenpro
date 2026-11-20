package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.ExerciseKind
import com.hermesmarcano.zen.data.ExerciseRef
import com.hermesmarcano.zen.data.Progress
import com.hermesmarcano.zen.data.Routine
import com.hermesmarcano.zen.data.SetEntry
import com.hermesmarcano.zen.data.SetType
import com.hermesmarcano.zen.data.WorkoutSession
import java.time.Instant
import java.time.ZoneId

/** Semanas de lunes a domingo, numeradas desde el 1970 (el 5-ene-1970, un lunes, es la semana 1). */
object WorkoutWeeks {
    fun weekIndex(epochDay: Long): Long = Math.floorDiv(epochDay + 3L, 7L)

    fun startDay(week: Long): Long = week * 7L - 3L

    fun dayOf(millis: Long, zone: ZoneId): Long = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /** Semanas seguidas cumpliendo el objetivo, tal como las guardó el juego; se apaga si ya pasó una semana entera sin cumplirlo. */
    fun displayGoalStreak(progress: Progress, currentWeek: Long): Int {
        val last = progress.lastGoalWeek ?: return 0
        return if (last >= currentWeek - 1 && last <= currentWeek) progress.goalWeekStreak else 0
    }
}

/** Lo que se puede medir de un ejercicio a lo largo del tiempo (gráficas y récords). */
enum class Metric(val title: String, val unit: String) {
    E1RM("1RM estimada", "kg"),
    MAX_WEIGHT("Peso máximo", "kg"),
    SESSION_VOLUME("Volumen por sesión", "kg"),
    SET_VOLUME("Mejor serie", "kg"),
    MAX_REPS("Repeticiones máximas", "rep."),
    LONGEST("Mayor tiempo", "s");

    companion object {
        fun forKind(kind: ExerciseKind): List<Metric> = when (kind) {
            ExerciseKind.WEIGHT_REPS -> listOf(E1RM, MAX_WEIGHT, SESSION_VOLUME)
            ExerciseKind.BODYWEIGHT_REPS -> listOf(MAX_REPS)
            ExerciseKind.DURATION -> listOf(LONGEST)
        }

        /** Métricas que cuentan como récord personal. */
        fun recordsFor(kind: ExerciseKind): List<Metric> = when (kind) {
            ExerciseKind.WEIGHT_REPS -> listOf(MAX_WEIGHT, E1RM, SET_VOLUME)
            ExerciseKind.BODYWEIGHT_REPS -> listOf(MAX_REPS)
            ExerciseKind.DURATION -> listOf(LONGEST)
        }
    }
}

object WorkoutStats {
    private const val EPSILON = 1e-9

    data class Point(val day: Long, val value: Double)
    data class NewRecord(val ref: ExerciseRef, val metrics: List<Metric>)

    private fun finished(history: List<WorkoutSession>) = history.filter { it.isFinished }.sortedBy { it.finishedAt }

    /** Series de trabajo hechas la última vez que se hizo este ejercicio (para rellenar la sesión nueva). */
    fun lastSets(history: List<WorkoutSession>, exerciseId: String): List<SetEntry> {
        for (session in finished(history).asReversed()) {
            val sets = session.exercises.filter { it.ref.id == exerciseId }.flatMap { it.sets }.filter { it.done && it.type == SetType.NORMAL }
            if (sets.isNotEmpty()) return sets
        }
        return emptyList()
    }

    /** Mejor valor de [metric] en las series hechas de [exerciseId] dentro de [sessions] (sin calentamientos). */
    fun best(sessions: List<WorkoutSession>, exerciseId: String, metric: Metric): Double? =
        sessions.mapNotNull { sessionValue(it, exerciseId, metric) }.maxOrNull()

    /** Valor de [metric] de esa sesión para el ejercicio, o `null` si no lo hizo (o no aplica). */
    fun sessionValue(session: WorkoutSession, exerciseId: String, metric: Metric): Double? {
        val exercises = session.exercises.filter { it.ref.id == exerciseId }
        if (exercises.isEmpty()) return null
        val sets = exercises.flatMap { ex -> ex.sets.filter { it.done && it.type == SetType.NORMAL }.map { ex.ref.kind to it } }
        if (sets.isEmpty()) return null
        val value = when (metric) {
            Metric.MAX_WEIGHT -> sets.mapNotNull { (_, s) -> s.weightKg?.takeIf { (s.reps ?: 0) >= 1 } }.maxOrNull()
            Metric.E1RM -> sets.mapNotNull { (_, s) -> WorkoutNumbers.estimatedOneRepMax(s.weightKg ?: 0.0, s.reps ?: 0) }.maxOrNull()
            Metric.SET_VOLUME -> sets.maxOfOrNull { (k, s) -> WorkoutNumbers.setVolume(k, s) }
            Metric.SESSION_VOLUME -> sets.sumOf { (k, s) -> WorkoutNumbers.setVolume(k, s) }
            Metric.MAX_REPS -> sets.mapNotNull { (_, s) -> s.reps?.toDouble() }.maxOrNull()
            Metric.LONGEST -> sets.mapNotNull { (_, s) -> s.seconds?.toDouble() }.maxOrNull()
        }
        return value?.takeIf { it > 0.0 }
    }

    /**
     * Récords que bate [session] respecto a [before] (el historial anterior). Un ejercicio **sin historial
     * previo no puntúa**: la primera vez todo sería "récord" y no dice nada.
     */
    fun newRecords(before: List<WorkoutSession>, session: WorkoutSession): List<NewRecord> {
        val past = finished(before).filter { it.id != session.id }
        return session.exercises.map { it.ref }.distinctBy { it.id }.mapNotNull { ref ->
            val metrics = Metric.recordsFor(ref.kind).filter { metric ->
                val previous = best(past, ref.id, metric) ?: return@filter false
                val now = sessionValue(session, ref.id, metric) ?: return@filter false
                now > previous + EPSILON
            }
            if (metrics.isEmpty()) null else NewRecord(ref, metrics)
        }
    }

    /** Un punto por sesión (día local) con el valor de [metric], de la más antigua a la más reciente. */
    fun series(history: List<WorkoutSession>, exerciseId: String, metric: Metric, zone: ZoneId): List<Point> =
        finished(history).mapNotNull { s ->
            val v = sessionValue(s, exerciseId, metric) ?: return@mapNotNull null
            Point(WorkoutWeeks.dayOf(s.finishedAt ?: s.startedAt, zone), v)
        }

    // ---------- Semanas ----------

    data class WeekSummary(
        val week: Long,
        /** Días distintos con un entrenamiento que cuenta (ver WorkoutOps.qualifies). */
        val daysTrained: Int,
        val sessions: Int,
        val sets: Int,
        val volumeKg: Double,
        val minutes: Int,
        val records: Int,
    )

    fun weekSummary(history: List<WorkoutSession>, week: Long, zone: ZoneId): WeekSummary {
        val inWeek = finished(history).filter { WorkoutWeeks.weekIndex(WorkoutWeeks.dayOf(it.finishedAt ?: it.startedAt, zone)) == week }
        val days = inWeek.filter { WorkoutOps.qualifies(it) }.map { WorkoutWeeks.dayOf(it.finishedAt ?: it.startedAt, zone) }.distinct().size
        return WeekSummary(
            week = week,
            daysTrained = days,
            sessions = inWeek.size,
            sets = inWeek.sumOf { WorkoutOps.doneSets(it) },
            volumeKg = inWeek.sumOf { WorkoutOps.volumeKg(it) },
            minutes = inWeek.sumOf { WorkoutOps.durationMinutes(it, it.finishedAt ?: it.startedAt) },
            records = inWeek.sumOf { it.prs },
        )
    }

    /** Volumen de las últimas [count] semanas terminando en [endWeek] (incluida), de la más antigua a la actual. */
    fun weeklyVolumes(history: List<WorkoutSession>, endWeek: Long, count: Int, zone: ZoneId): List<Pair<Long, Double>> =
        (endWeek - count + 1..endWeek).map { w -> w to weekSummary(history, w, zone).volumeKg }
}

/** Qué rutina toca ahora: la siguiente a la última que se terminó (da igual el día de la semana). */
object Rotation {
    fun next(routines: List<Routine>, history: List<WorkoutSession>): Routine? {
        val ordered = routines.sortedBy { it.position }
        if (ordered.isEmpty()) return null
        val lastId = history.filter { it.isFinished && it.routineId != null && ordered.any { r -> r.id == it.routineId } }
            .maxByOrNull { it.finishedAt ?: 0L }?.routineId
        val index = ordered.indexOfFirst { it.id == lastId }
        return if (index < 0) ordered.first() else ordered[(index + 1) % ordered.size]
    }
}

/** Qué discos poner a cada lado de la barra, de mayor a menor (aritmética en centésimas de kg: sin errores de coma flotante). */
object PlateCalculator {
    val DEFAULT_PLATES = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)
    val BAR_OPTIONS = listOf(20.0, 15.0, 10.0)

    /** [perSide]: (disco, cantidad) por lado. [remainderKg] > 0 si el peso pedido no se puede formar exactamente. */
    data class Result(val perSide: List<Pair<Double, Int>>, val loadedKg: Double, val remainderKg: Double)

    private fun hundredths(kg: Double): Long = Math.round(kg * 100.0)

    /** `null` si [targetKg] es menor que la barra o los datos no son válidos. */
    fun perSide(targetKg: Double, barKg: Double, plates: List<Double> = DEFAULT_PLATES): Result? {
        if (targetKg.isNaN() || barKg.isNaN() || barKg < 0.0 || targetKg < barKg || targetKg > WorkoutNumbers.MAX_WEIGHT_KG) return null
        val target = hundredths(targetKg)
        val bar = hundredths(barKg)
        var side = (target - bar) / 2
        val used = mutableListOf<Pair<Double, Int>>()
        var loadedSide = 0L
        for (plate in plates.filter { it > 0.0 }.sortedDescending()) {
            val w = hundredths(plate)
            val count = (side / w).toInt()
            if (count > 0) {
                used += plate to count
                side -= count * w
                loadedSide += count * w
            }
        }
        val loaded = bar + 2 * loadedSide
        return Result(used, loaded / 100.0, (target - loaded) / 100.0)
    }

    fun describe(result: Result): String =
        if (result.perSide.isEmpty()) "Solo la barra"
        else result.perSide.joinToString(" + ") { (plate, n) -> (if (n > 1) "$n × " else "") + WorkoutNumbers.formatKg(plate) }
}
