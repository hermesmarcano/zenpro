package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.ExerciseKind
import com.hermesmarcano.zen.data.SetEntry
import com.hermesmarcano.zen.data.SetType
import java.util.Locale
import kotlin.math.roundToInt

/** Lectura, formato y validación de los números de una serie (kg, repeticiones, segundos, RPE). Unidad: kg. */
object WorkoutNumbers {
    const val MAX_WEIGHT_KG = 1500.0
    const val MAX_REPS = 999
    const val MAX_SECONDS = 36_000
    const val MIN_RPE = 6.0
    const val MAX_RPE = 10.0

    /** Acepta "62,5" y "62.5". Rechaza vacío, negativos, texto, NaN/infinito y valores absurdos; redondea a 0,01. */
    fun parseWeight(text: String): Double? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite() || value < 0.0 || value > MAX_WEIGHT_KG) return null
        return (value * 100.0).roundToInt() / 100.0
    }

    fun parseReps(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..MAX_REPS }

    fun parseSeconds(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..MAX_SECONDS }

    /** "62,5" / "60" (sin ceros sobrantes; coma decimal como en español). */
    fun formatKg(kg: Double): String {
        val rounded = (kg * 100.0).roundToInt() / 100.0
        val text = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0')
        return text.replace('.', ',')
    }

    /** Para archivos (CSV): punto decimal, independiente del idioma. */
    fun formatKgPlain(kg: Double): String = formatKg(kg).replace(',', '.')

    /** Toneladas como "12,4 t" desde 1000 kg; si no, "850 kg". */
    fun formatVolume(kg: Double): String =
        if (kg >= 1000.0) String.format(Locale("es"), "%.1f t", kg / 1000.0) else "${kg.roundToInt()} kg"

    /** "1:05" / "12:30" (minutos:segundos). Una hora o más: "1:05:00". */
    fun clock(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    /** "1 h 05 min" / "45 min". */
    fun durationText(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return if (m >= 60) String.format(Locale.ROOT, "%d h %02d min", m / 60, m % 60) else "$m min"
    }

    /** RPE válido: 6..10 en pasos de 0,5. */
    fun isValidRpe(rpe: Double): Boolean = rpe in MIN_RPE..MAX_RPE && (rpe * 2.0) == (rpe * 2.0).toLong().toDouble()

    fun formatRpe(rpe: Double): String = if (rpe == rpe.toLong().toDouble()) rpe.toLong().toString() else formatKg(rpe)

    /** 1RM estimada (Epley). Solo es fiable hasta ~12 repeticiones: más allá no se usa para récords. */
    fun estimatedOneRepMax(weightKg: Double, reps: Int): Double? = when {
        weightKg <= 0.0 || reps < 1 || reps > 12 -> null
        reps == 1 -> weightKg
        else -> weightKg * (1.0 + reps / 30.0)
    }

    /** ¿Se puede marcar como hecha? Pide los datos que el tipo de ejercicio necesita (reps ≥ 1, o tiempo ≥ 1 s). */
    fun canComplete(kind: ExerciseKind, set: SetEntry): Boolean = when (kind) {
        ExerciseKind.WEIGHT_REPS -> (set.weightKg ?: -1.0) >= 0.0 && (set.reps ?: 0) >= 1
        ExerciseKind.BODYWEIGHT_REPS -> (set.reps ?: 0) >= 1
        ExerciseKind.DURATION -> (set.seconds ?: 0) >= 1
    }

    /** kg × repeticiones de una serie hecha que no es de calentamiento (0 si no cuenta). */
    fun setVolume(kind: ExerciseKind, set: SetEntry): Double =
        if (kind == ExerciseKind.WEIGHT_REPS && set.done && set.type == SetType.NORMAL) (set.weightKg ?: 0.0) * (set.reps ?: 0) else 0.0

    /** Resumen corto de una serie: "60 kg × 8", "12 rep.", "0:45". */
    fun describe(kind: ExerciseKind, set: SetEntry): String = when (kind) {
        ExerciseKind.WEIGHT_REPS -> "${formatKg(set.weightKg ?: 0.0)} kg × ${set.reps ?: 0}"
        ExerciseKind.BODYWEIGHT_REPS -> "${set.reps ?: 0} rep."
        ExerciseKind.DURATION -> clock(set.seconds ?: 0)
    }
}
