package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.Achievement
import com.hermesmarcano.zen.core.Achievements
import com.hermesmarcano.zen.core.Levels
import com.hermesmarcano.zen.core.StreakPolicy
import com.hermesmarcano.zen.core.TaskBoard
import com.hermesmarcano.zen.core.TripPolicy
import com.hermesmarcano.zen.core.XpRules
import java.time.LocalDate

/** Lo que pasó al aplicar una acción: para celebrarlo en pantalla (XP, subida de nivel, logros). */
data class GameEvents(
    val xpGained: Int = 0,
    val levelBefore: Int = 1,
    val levelAfter: Int = 1,
    val unlocked: List<Achievement> = emptyList(),
    val mitDone: Boolean = false,
    val perfectDay: Boolean = false,
    val completeDay: Boolean = false,
    val workoutDone: Boolean = false,
    /** Ejercicios en los que se batió un récord personal en el entrenamiento. */
    val records: Int = 0,
    val weeklyGoalMet: Boolean = false,
) {
    val leveledUp: Boolean get() = levelAfter > levelBefore
    val isEmpty: Boolean
        get() = xpGained == 0 && unlocked.isEmpty() && !mitDone && !perfectDay && !completeDay && !workoutDone && records == 0 && !weeklyGoalMet
}

/** Lo que el juego necesita saber de un entrenamiento terminado. */
data class WorkoutOutcome(
    /** Cumple el mínimo (ver WorkoutOps.qualifies); si no, se guarda pero no da XP ni cuenta para el objetivo. */
    val qualifies: Boolean,
    val volumeKg: Double,
    /** Ejercicios con récord personal. */
    val records: Int,
    /** Días de entreno de esta semana **incluido** este entrenamiento. */
    val daysThisWeek: Int,
    val weeklyGoal: Int,
)

/**
 * Transiciones del juego sobre el estado: marcar tareas, registrar un despertar, empezar/cancelar un
 * viaje. Puras (el día lo pone quien llama). Cada una devuelve el estado nuevo y los [GameEvents].
 */
object GameEngine {
    data class Result(val state: ZenState, val events: GameEvents = GameEvents())

    private fun unlock(state: ZenState, today: Long, solveSeconds: Int? = null): Pair<ZenState, List<Achievement>> {
        val fresh = Achievements.newlyUnlocked(state.stats, state.progress, solveSeconds)
        if (fresh.isEmpty()) return state to emptyList()
        val map = state.progress.achievements + fresh.associate { it.id to today }
        return state.copy(progress = state.progress.copy(achievements = map)) to fresh
    }

    private fun finish(before: ZenState, after: ZenState, today: Long, xp: Int, solveSeconds: Int? = null, flags: GameEvents = GameEvents()): Result {
        val (withAchievements, fresh) = unlock(after, today, solveSeconds)
        return Result(
            withAchievements,
            flags.copy(
                xpGained = xp,
                levelBefore = Levels.levelFor(before.progress.xp),
                levelAfter = Levels.levelFor(withAchievements.progress.xp),
                unlocked = fresh,
            ),
        )
    }

    /** Marca o desmarca una tarea **de hoy**. Las de otros días no se completan (se planifican, no se "farmean"). */
    fun setTaskDone(state: ZenState, taskId: String, done: Boolean, today: LocalDate): Result {
        val day = today.toEpochDay()
        val task = state.tasks.firstOrNull { it.id == taskId } ?: return Result(state)
        if (task.day != day || task.done == done) return Result(state)
        var tasks = TaskBoard.setDone(state.tasks, taskId, done)
        if (!done) return Result(state.copy(tasks = tasks)) // desmarcar no quita XP ni rachas ya ganadas

        var progress = state.progress
        var xp = 0
        var flags = GameEvents()
        val index = TaskBoard.forDay(tasks, day).indexOfFirst { it.id == taskId }

        if (!task.xpAwarded) {
            val used = if (progress.taskXpDay == day) progress.taskXpToday else 0
            val gain = minOf(XpRules.forTier(TaskBoard.tierAt(index)), (XpRules.DAILY_TASK_XP_CAP - used).coerceAtLeast(0))
            xp += gain
            val isMit = index == 0
            progress = progress.copy(
                xp = Levels.addXp(progress.xp, gain),
                taskXpDay = day,
                taskXpToday = used + gain,
                tasksCompleted = progress.tasksCompleted + 1,
                mitsCompleted = progress.mitsCompleted + if (isMit) 1 else 0,
            )
            tasks = TaskBoard.markXpAwarded(tasks, taskId)
            if (isMit) {
                val mit = StreakPolicy.record(progress.mitStreak, today, WakeRecorder.scheduledDays(state), 0, WakeRecorder.excusedDays(state))
                progress = progress.copy(mitStreak = mit)
                flags = flags.copy(mitDone = true)
                if (state.stats.lastWakeDay == day && progress.completeDayBonusDay != day) {
                    xp += XpRules.COMPLETE_DAY_BONUS
                    progress = progress.copy(xp = Levels.addXp(progress.xp, XpRules.COMPLETE_DAY_BONUS), completeDayBonusDay = day)
                    flags = flags.copy(completeDay = true)
                }
            }
        }

        val list = TaskBoard.forDay(tasks, day)
        if (TaskBoard.isPerfectDay(list) && progress.perfectDayBonusDay != day) {
            xp += XpRules.PERFECT_DAY_BONUS
            progress = progress.copy(
                xp = Levels.addXp(progress.xp, XpRules.PERFECT_DAY_BONUS),
                perfectDays = progress.perfectDays + 1,
                fullDays = progress.fullDays + if (list.size == TaskBoard.MAX_PER_DAY) 1 else 0,
                perfectDayBonusDay = day,
            )
            flags = flags.copy(perfectDay = true)
        }
        return finish(state, state.copy(tasks = tasks, progress = progress), day, xp, flags = flags)
    }

    /** Despertar verificado en el ancla: racha, XP (una vez al día) y logros. */
    fun recordWake(state: ZenState, today: LocalDate, solveSeconds: Int): Result {
        val day = today.toEpochDay()
        val firstOfDay = state.stats.lastWakeDay != day
        var next = WakeRecorder.record(state, today, solveSeconds)
        var xp = 0
        var flags = GameEvents()
        if (firstOfDay) {
            xp += XpRules.WAKE
            var progress = next.progress.copy(xp = Levels.addXp(next.progress.xp, XpRules.WAKE))
            if (progress.mitStreak.lastWakeDay == day && progress.completeDayBonusDay != day) {
                xp += XpRules.COMPLETE_DAY_BONUS
                progress = progress.copy(xp = Levels.addXp(progress.xp, XpRules.COMPLETE_DAY_BONUS), completeDayBonusDay = day)
                flags = flags.copy(completeDay = true)
            }
            next = next.copy(progress = progress)
        }
        return finish(state, next, day, xp, solveSeconds, flags)
    }

    /**
     * Entrenamiento terminado: XP (el primero de cada día), bonus por récords (con tope), objetivo semanal
     * (una vez por semana) y logros. Un entrenamiento que no cumple el mínimo no cambia nada.
     */
    fun recordWorkout(state: ZenState, outcome: WorkoutOutcome, today: LocalDate): Result {
        if (!outcome.qualifies) return Result(state)
        val day = today.toEpochDay()
        val week = com.hermesmarcano.zen.core.WorkoutWeeks.weekIndex(day)
        var progress = state.progress
        var xp = 0
        var flags = GameEvents(workoutDone = true, records = outcome.records)
        progress = progress.copy(
            workouts = progress.workouts + 1,
            workoutVolumeKg = (progress.workoutVolumeKg + outcome.volumeKg.toLong()).coerceAtMost(1_000_000_000L),
            personalRecords = progress.personalRecords + outcome.records,
        )
        if (progress.workoutXpDay != day) {
            xp += XpRules.WORKOUT + minOf(outcome.records, XpRules.WORKOUT_RECORD_MAX) * XpRules.WORKOUT_RECORD
            progress = progress.copy(workoutXpDay = day)
        }
        if (outcome.daysThisWeek >= outcome.weeklyGoal && progress.goalBonusWeek != week) {
            xp += XpRules.WEEKLY_GOAL
            val streak = if (progress.lastGoalWeek == week - 1) progress.goalWeekStreak + 1 else 1
            progress = progress.copy(
                goalBonusWeek = week,
                goalWeeks = progress.goalWeeks + 1,
                goalWeekStreak = streak,
                bestGoalWeekStreak = maxOf(progress.bestGoalWeekStreak, streak),
                lastGoalWeek = week,
            )
            flags = flags.copy(weeklyGoalMet = true)
        }
        progress = progress.copy(xp = Levels.addXp(progress.xp, xp))
        return finish(state, state.copy(progress = progress), day, xp, flags = flags)
    }

    /**
     * Una prueba de alarma de 1 minuto llegó a tiempo. Solo da el logro (la primera vez): no hay XP, porque
     * repetirla a voluntad no es un hábito que premiar; su valor es saber que la alarma funciona.
     */
    fun recordAlarmTest(state: ZenState, today: LocalDate): Result {
        val progress = state.progress.copy(alarmTestsPassed = (state.progress.alarmTestsPassed + 1).coerceAtMost(1_000_000))
        return finish(state, state.copy(progress = progress), today.toEpochDay(), 0)
    }

    /** Empieza un viaje (sustituye al anterior si lo hubiera). */
    fun startTrip(state: ZenState, trip: Trip, today: LocalDate): Result {
        val day = today.toEpochDay()
        val base = cancelTrip(state, today)
        val ranges = if (trip.isPause) TripPolicy.addPausedRange(base.progress.pausedRanges, day, trip.untilEpochDay) else base.progress.pausedRanges
        val next = base.copy(trip = trip, progress = base.progress.copy(tripsStarted = base.progress.tripsStarted + 1, pausedRanges = ranges))
        return finish(state, next, day, 0)
    }

    /** Termina el viaje ya: si era una pausa, los días que no llegaron a pasar vuelven a contar. */
    fun cancelTrip(state: ZenState, today: LocalDate): ZenState {
        val trip = state.trip ?: return state
        val ranges = if (trip.isPause) TripPolicy.truncatePausedRanges(state.progress.pausedRanges, today.toEpochDay()) else state.progress.pausedRanges
        return state.copy(trip = null, progress = state.progress.copy(pausedRanges = ranges))
    }
}
