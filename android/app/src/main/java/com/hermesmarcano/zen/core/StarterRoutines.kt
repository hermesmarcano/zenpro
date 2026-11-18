package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.RoutineExercise

/** Plantillas para empezar rápido: se copian a rutinas propias que luego se editan como cualquier otra. */
object StarterRoutines {
    class Template(val name: String, val items: List<RoutineExercise>)

    private fun item(id: String, sets: Int = 3, rest: Int = 90): RoutineExercise =
        RoutineExercise(requireNotNull(ExerciseLibrary.preset(id)) { "ejercicio $id" }, sets, rest)

    val pushPullLegs: List<Template> = listOf(
        Template("Día 1 · Empuje", listOf(item("bench_press", 4, 120), item("db_shoulder"), item("incline_db"), item("lateral_raise", 3, 60), item("tri_pushdown", 3, 60))),
        Template("Día 2 · Tirón", listOf(item("deadlift", 3, 150), item("lat_pulldown"), item("seated_row"), item("face_pull", 3, 60), item("db_curl", 3, 60))),
        Template("Día 3 · Piernas", listOf(item("squat", 4, 150), item("rdl"), item("leg_press"), item("leg_curl", 3, 60), item("standing_calf", 4, 60))),
    )
}
