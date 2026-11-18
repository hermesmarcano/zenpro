package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.Progress
import com.hermesmarcano.zen.data.WakeStats

/** Puntos que da cada cosa. Pocos números, fáciles de entender y con tope diario para que no se pueda "farmear". */
object XpRules {
    const val TASK_HIGH = 30
    const val TASK_MEDIUM = 15
    const val TASK_LOW = 5
    const val WAKE = 10

    /** Despertar verificado + tarea prioritaria hecha el mismo día. */
    const val COMPLETE_DAY_BONUS = 25

    /** Todas las tareas del día hechas (mínimo 3). */
    const val PERFECT_DAY_BONUS = 20

    /** Tope de XP por tareas en un día: borrar y volver a crear tareas no da más. */
    const val DAILY_TASK_XP_CAP = 100

    /** Entrenamiento que cuenta (≥ 3 series de trabajo y ≥ 5 min). Solo el primero de cada día da XP. */
    const val WORKOUT = 40

    /** Por cada ejercicio en el que se bate un récord personal, con tope por entrenamiento. */
    const val WORKOUT_RECORD = 5
    const val WORKOUT_RECORD_MAX = 3

    /** Objetivo semanal de días de entreno cumplido (una vez por semana). */
    const val WEEKLY_GOAL = 50

    fun forTier(tier: TaskBoard.Tier): Int = when (tier) {
        TaskBoard.Tier.HIGH -> TASK_HIGH
        TaskBoard.Tier.MEDIUM -> TASK_MEDIUM
        TaskBoard.Tier.LOW -> TASK_LOW
    }
}

/** Niveles: el n-ésimo pide 50·n·(n−1) XP en total (100, 300, 600, 1000…). */
object Levels {
    class LevelProgress(val level: Int, val xpIntoLevel: Int, val xpForNext: Int, val title: String) {
        val fraction: Float get() = if (xpForNext <= 0) 1f else (xpIntoLevel.toFloat() / xpForNext).coerceIn(0f, 1f)
    }

    private val titles = listOf("Despertando", "Constante", "Enfocado", "Disciplinado", "Imparable", "Maestro del alba")

    /** Tope de nivel y de XP: con la aritmética en Int, un XP enorme (archivo dañado) desbordaba y el bucle no acababa. */
    const val MAX_LEVEL = 100
    const val MAX_XP = 50 * (MAX_LEVEL + 1) * MAX_LEVEL

    fun xpToReach(level: Int): Int = 50 * level.coerceIn(1, MAX_LEVEL + 1) * (level.coerceIn(1, MAX_LEVEL + 1) - 1)

    /** Suma XP sin pasar del tope. */
    fun addXp(current: Int, gain: Int): Int = (current.toLong() + gain).coerceIn(0L, MAX_XP.toLong()).toInt()

    fun levelFor(xp: Int): Int {
        val safe = xp.coerceIn(0, MAX_XP)
        var level = 1
        while (level < MAX_LEVEL && safe >= xpToReach(level + 1)) level++
        return level
    }

    fun title(level: Int): String = titles[(level - 1).coerceIn(0, titles.lastIndex)]

    fun progress(xp: Int): LevelProgress {
        val safe = xp.coerceIn(0, MAX_XP)
        val level = levelFor(safe)
        val start = xpToReach(level)
        return LevelProgress(level, safe - start, xpToReach(level + 1) - start, title(level))
    }
}

/** Logros: pocos, claros y ninguno pide perder nada (sin castigos ocultos). */
enum class Achievement(val id: String, val title: String, val description: String) {
    FIRST_WAKE("first_wake", "Primer despertar", "Llegaste al lugar y apagaste la alarma."),
    STREAK_3("streak_3", "Tres seguidos", "3 días seguidos despertando en el lugar."),
    STREAK_7("streak_7", "Una semana", "7 días seguidos despertando en el lugar."),
    STREAK_30("streak_30", "Un mes", "30 días seguidos despertando en el lugar."),
    QUICK_RISER("quick_riser", "Sin demora", "Llegaste al lugar en menos de un minuto."),
    FIRST_MIT("first_mit", "Lo importante primero", "Cumpliste tu primera tarea prioritaria."),
    MIT_STREAK_3("mit_streak_3", "Foco constante", "3 días seguidos cumpliendo la tarea prioritaria."),
    MIT_STREAK_7("mit_streak_7", "Semana de foco", "7 días seguidos cumpliendo la tarea prioritaria."),
    PERFECT_DAY("perfect_day", "Día redondo", "Hiciste todas las tareas de un día (mínimo 3)."),
    FULL_HOUSE("full_house", "Diez de diez", "Completaste una lista entera de 10 tareas."),
    TRAVELER("traveler", "Con la maleta lista", "Usaste el modo viaje sin soltar la rutina."),
    HUNDRED_TASKS("hundred_tasks", "Cien tareas", "Completaste 100 tareas en total."),
    LEVEL_5("level_5", "Nivel 5", "Llegaste al nivel 5."),
    FIRST_WORKOUT("first_workout", "Primer entrenamiento", "Terminaste tu primer entrenamiento."),
    WORKOUTS_10("workouts_10", "Diez entrenos", "Completaste 10 entrenamientos."),
    WORKOUTS_50("workouts_50", "Cincuenta entrenos", "Completaste 50 entrenamientos."),
    FIRST_RECORD("first_record", "Marca personal", "Superaste tu mejor marca en un ejercicio."),
    GOAL_WEEK("goal_week", "Semana cumplida", "Cumpliste tu objetivo semanal de entrenos."),
    GOAL_WEEKS_4("goal_weeks_4", "Un mes de constancia", "4 semanas seguidas cumpliendo tu objetivo."),
    TONNE_CLUB("tonne_club", "Diez toneladas", "Moviste 10 000 kg en total.");
}

object Achievements {
    /** Logros cuya condición se cumple y que aún no estaban desbloqueados. [solveSeconds] solo en el instante de despertar. */
    fun newlyUnlocked(wake: WakeStats, progress: Progress, solveSeconds: Int? = null): List<Achievement> =
        Achievement.entries.filter { it.id !in progress.achievements && isMet(it, wake, progress, solveSeconds) }

    private fun isMet(a: Achievement, wake: WakeStats, p: Progress, solveSeconds: Int?): Boolean = when (a) {
        Achievement.FIRST_WAKE -> wake.totalWakeups >= 1
        Achievement.STREAK_3 -> wake.bestStreak >= 3
        Achievement.STREAK_7 -> wake.bestStreak >= 7
        Achievement.STREAK_30 -> wake.bestStreak >= 30
        Achievement.QUICK_RISER -> solveSeconds != null && solveSeconds <= 60
        Achievement.FIRST_MIT -> p.mitsCompleted >= 1
        Achievement.MIT_STREAK_3 -> p.mitStreak.bestStreak >= 3
        Achievement.MIT_STREAK_7 -> p.mitStreak.bestStreak >= 7
        Achievement.PERFECT_DAY -> p.perfectDays >= 1
        Achievement.FULL_HOUSE -> p.fullDays >= 1
        Achievement.TRAVELER -> p.tripsStarted >= 1
        Achievement.HUNDRED_TASKS -> p.tasksCompleted >= 100
        Achievement.LEVEL_5 -> Levels.levelFor(p.xp) >= 5
        Achievement.FIRST_WORKOUT -> p.workouts >= 1
        Achievement.WORKOUTS_10 -> p.workouts >= 10
        Achievement.WORKOUTS_50 -> p.workouts >= 50
        Achievement.FIRST_RECORD -> p.personalRecords >= 1
        Achievement.GOAL_WEEK -> p.goalWeeks >= 1
        Achievement.GOAL_WEEKS_4 -> p.bestGoalWeekStreak >= 4
        Achievement.TONNE_CLUB -> p.workoutVolumeKg >= 10_000L
    }
}
