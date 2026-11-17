package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.WakeStats
import java.time.LocalDate

/**
 * Racha de despertares verificados. Solo cuenta llegar al ancla (QR o foto); el código de
 * respaldo no suma ni rompe. Respeta los días de las alarmas: con una alarma de lunes a viernes,
 * el fin de semana no corta la racha.
 */
object StreakPolicy {
    /**
     * Último día previsto de alarma estrictamente anterior a [today]. [scheduledDays] vacío significa
     * "todos los días" (alarmas de una sola vez o sin repetición).
     */
    fun previousScheduledDay(today: LocalDate, scheduledDays: Set<Weekday>, excusedDays: Set<Long> = emptySet()): LocalDate {
        // 45 días: más que el viaje más largo (30) más una semana de margen.
        for (back in 1L..45L) {
            val day = today.minusDays(back)
            val weekday = Weekday.fromIso(day.dayOfWeek.value)
            val scheduled = scheduledDays.isEmpty() || weekday in scheduledDays
            if (scheduled && day.toEpochDay() !in excusedDays) return day
        }
        return today.minusDays(1)
    }

    /** Registra un despertar verificado hoy. */
    fun record(stats: WakeStats, today: LocalDate, scheduledDays: Set<Weekday>, solveSeconds: Int, excusedDays: Set<Long> = emptySet()): WakeStats {
        val last = stats.lastWakeDay?.let { LocalDate.ofEpochDay(it) }
        val total = stats.totalWakeups + 1
        val seconds = solveSeconds.coerceAtLeast(0)
        // Segundo despertar del mismo día (dos alarmas): cuenta en el total, no en la racha.
        if (last == today) return stats.copy(totalWakeups = total, lastSolveSeconds = seconds)

        val expected = previousScheduledDay(today, scheduledDays, excusedDays)
        // Continúa si el último despertar fue el día previsto anterior (o uno posterior, p. ej. una
        // alarma extra en un día libre). Un reloj que retrocede (último día en el futuro) reinicia.
        val continues = last != null && last.isBefore(today) && !last.isBefore(expected)
        val streak = if (continues) stats.currentStreak + 1 else 1
        return WakeStats(
            currentStreak = streak,
            bestStreak = maxOf(stats.bestStreak, streak),
            totalWakeups = total,
            lastWakeDay = today.toEpochDay(),
            lastSolveSeconds = seconds,
        )
    }

    /** Racha a mostrar hoy: se apaga a 0 si ya se saltó un día previsto sin despertar verificado. */
    fun displayStreak(stats: WakeStats, today: LocalDate, scheduledDays: Set<Weekday>, excusedDays: Set<Long> = emptySet()): Int {
        val last = stats.lastWakeDay?.let { LocalDate.ofEpochDay(it) } ?: return 0
        if (last == today) return stats.currentStreak
        if (last.isAfter(today)) return 0
        return if (!last.isBefore(previousScheduledDay(today, scheduledDays, excusedDays))) stats.currentStreak else 0
    }

    /** Mensaje sobrio para hitos de racha; `null` el resto de días. */
    fun milestone(streak: Int): String? = when (streak) {
        3 -> "Tres días seguidos."
        7 -> "Una semana entera."
        14 -> "Dos semanas."
        30 -> "Un mes sin fallar."
        60 -> "Dos meses."
        100 -> "Cien días."
        365 -> "Un año."
        else -> null
    }

    /** "1 min 12 s" / "45 s" */
    fun solveTimeText(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
    }
}
