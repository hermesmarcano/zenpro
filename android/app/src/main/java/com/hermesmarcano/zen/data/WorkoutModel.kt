package com.hermesmarcano.zen.data

/** Grupo muscular principal de un ejercicio: sirve para filtrar la biblioteca, no para planificar. */
enum class MuscleGroup(val id: String, val title: String) {
    CHEST("chest", "Pecho"),
    BACK("back", "Espalda"),
    SHOULDERS("shoulders", "Hombros"),
    BICEPS("biceps", "Bíceps"),
    TRICEPS("triceps", "Tríceps"),
    LEGS("legs", "Piernas"),
    GLUTES("glutes", "Glúteos"),
    CALVES("calves", "Pantorrillas"),
    CORE("core", "Abdomen"),
    FULL_BODY("full_body", "Cuerpo completo");

    companion object {
        fun fromId(id: String?): MuscleGroup = entries.firstOrNull { it.id == id } ?: FULL_BODY
    }
}

/** Qué se anota en cada serie. */
enum class ExerciseKind(val id: String, val title: String) {
    /** Peso en kg × repeticiones. */
    WEIGHT_REPS("weight_reps", "Peso y repeticiones"),

    /** Solo repeticiones (flexiones, dominadas…). */
    BODYWEIGHT_REPS("bodyweight_reps", "Solo repeticiones"),

    /** Solo tiempo (plancha…). */
    DURATION("duration", "Tiempo");

    companion object {
        fun fromId(id: String?): ExerciseKind = entries.firstOrNull { it.id == id } ?: WEIGHT_REPS
    }
}

enum class SetType(val id: String) {
    NORMAL("normal"),
    WARMUP("warmup");

    companion object {
        fun fromId(id: String?): SetType = entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

/**
 * Un ejercicio tal como se usó: se copia (nombre, grupo, tipo) en rutinas y sesiones, así borrar o renombrar
 * un ejercicio propio nunca cambia ni rompe el historial.
 */
data class ExerciseRef(val id: String, val name: String, val muscle: MuscleGroup, val kind: ExerciseKind)

/**
 * Una serie. Los valores pueden venir rellenados con los de la última vez ([done] = false) y no
 * cuentan hasta que se marcan como hechas (y solo si son válidos, ver WorkoutOps.canComplete).
 */
data class SetEntry(
    val weightKg: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val type: SetType = SetType.NORMAL,
    /** Esfuerzo percibido 6..10 en pasos de 0,5; `null` = sin anotar. */
    val rpe: Double? = null,
    val done: Boolean = false,
    val completedAt: Long? = null,
    /** Descanso real (s) entre la serie hecha anterior y esta. */
    val restSeconds: Int? = null,
)

/** Ejercicio dentro de una rutina. [superset] igual en ejercicios contiguos los une en un superset. */
data class RoutineExercise(val ref: ExerciseRef, val sets: Int, val restSeconds: Int, val superset: Int? = null)

data class Routine(val id: String, val name: String, val position: Int, val items: List<RoutineExercise>)

data class SessionExercise(
    val ref: ExerciseRef,
    val restSeconds: Int,
    val superset: Int?,
    val sets: List<SetEntry>,
    val note: String = "",
)

/** Descanso en marcha: se guarda la **hora de fin**, así sobrevive a que Android cierre la app. */
data class RestState(val startedAt: Long, val endsAt: Long, val totalSeconds: Int) {
    fun remainingMillis(now: Long): Long = (endsAt - now).coerceAtLeast(0L)
    fun isOver(now: Long): Boolean = now >= endsAt
}

data class WorkoutSession(
    val id: String,
    val routineId: String?,
    val routineName: String,
    val startedAt: Long,
    /** `null` mientras se entrena. */
    val finishedAt: Long?,
    val exercises: List<SessionExercise>,
    val note: String = "",
    val rest: RestState? = null,
    /** Récords personales que batió esta sesión (se calcula al terminar). */
    val prs: Int = 0,
) {
    val isFinished: Boolean get() = finishedAt != null
}

/** Todo lo de entrenamiento. Vive en su propio archivo (el historial crece sin parar), no en `state.json`. */
data class WorkoutData(
    val routines: List<Routine> = emptyList(),
    val customExercises: List<ExerciseRef> = emptyList(),
    /** Sesiones terminadas, de la más antigua a la más reciente. */
    val history: List<WorkoutSession> = emptyList(),
    /** Sesión en curso (como mucho una). */
    val active: WorkoutSession? = null,
    /** Días de entreno por semana que quiere cumplir el usuario (1..7). */
    val weeklyGoal: Int = DEFAULT_WEEKLY_GOAL,
    /** Descanso por defecto al añadir un ejercicio, en segundos. */
    val defaultRestSeconds: Int = DEFAULT_REST_SECONDS,
    /** Peso de la barra para la calculadora de discos (kg). */
    val barKg: Double = DEFAULT_BAR_KG,
) {
    fun routine(id: String?): Routine? = routines.firstOrNull { it.id == id }

    companion object {
        const val DEFAULT_WEEKLY_GOAL = 3
        const val MIN_WEEKLY_GOAL = 1
        const val MAX_WEEKLY_GOAL = 7
        const val DEFAULT_REST_SECONDS = 90
        const val DEFAULT_BAR_KG = 20.0
    }
}
