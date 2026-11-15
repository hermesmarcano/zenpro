package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.StreakPolicy
import com.hermesmarcano.zen.core.Weekday
import java.time.LocalDate

/** Registra un despertar verificado en el estado. Pura: el día y la hora los pone quien llama. */
object WakeRecorder {
    /**
     * Días previstos = unión de los días de las alarmas activas que se repiten. Si ninguna se repite
     * (o no hay alarmas activas) se entiende "todos los días".
     */
    fun scheduledDays(state: ZenState): Set<Weekday> =
        state.alarms.filter { it.enabled && it.weekdays.isNotEmpty() }.flatMap { it.weekdays }.toSet()

    fun record(state: ZenState, today: LocalDate, solveSeconds: Int): ZenState =
        state.copy(stats = StreakPolicy.record(state.stats, today, scheduledDays(state), solveSeconds))

    fun currentStreak(state: ZenState, today: LocalDate): Int =
        StreakPolicy.displayStreak(state.stats, today, scheduledDays(state))
}
