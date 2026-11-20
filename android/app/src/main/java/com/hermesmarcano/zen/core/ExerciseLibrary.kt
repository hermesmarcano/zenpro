package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.ExerciseKind
import com.hermesmarcano.zen.data.ExerciseKind.BODYWEIGHT_REPS
import com.hermesmarcano.zen.data.ExerciseKind.DURATION
import com.hermesmarcano.zen.data.ExerciseKind.WEIGHT_REPS
import com.hermesmarcano.zen.data.ExerciseRef
import com.hermesmarcano.zen.data.MuscleGroup
import com.hermesmarcano.zen.data.MuscleGroup.BACK
import com.hermesmarcano.zen.data.MuscleGroup.BICEPS
import com.hermesmarcano.zen.data.MuscleGroup.CALVES
import com.hermesmarcano.zen.data.MuscleGroup.CHEST
import com.hermesmarcano.zen.data.MuscleGroup.CORE
import com.hermesmarcano.zen.data.MuscleGroup.FULL_BODY
import com.hermesmarcano.zen.data.MuscleGroup.GLUTES
import com.hermesmarcano.zen.data.MuscleGroup.LEGS
import com.hermesmarcano.zen.data.MuscleGroup.SHOULDERS
import com.hermesmarcano.zen.data.MuscleGroup.TRICEPS
import java.text.Normalizer
import java.util.Locale

/** Biblioteca incluida de ejercicios básicos. Los ids son estables: el historial los usa para reconocerlos. */
object ExerciseLibrary {
    const val NAME_MAX = 40
    const val MAX_CUSTOM = 100
    const val CUSTOM_PREFIX = "custom:"

    private fun e(id: String, name: String, muscle: MuscleGroup, kind: ExerciseKind = WEIGHT_REPS) = ExerciseRef(id, name, muscle, kind)

    val presets: List<ExerciseRef> = listOf(
        e("bench_press", "Press de banca", CHEST),
        e("incline_bench", "Press inclinado con barra", CHEST),
        e("db_bench", "Press con mancuernas", CHEST),
        e("incline_db", "Press inclinado con mancuernas", CHEST),
        e("machine_chest", "Press de pecho en máquina", CHEST),
        e("chest_fly", "Aperturas con mancuernas", CHEST),
        e("cable_fly", "Cruce de poleas", CHEST),
        e("pushup", "Flexiones", CHEST, BODYWEIGHT_REPS),
        e("dips", "Fondos", CHEST, BODYWEIGHT_REPS),

        e("deadlift", "Peso muerto", BACK),
        e("pullup", "Dominadas", BACK, BODYWEIGHT_REPS),
        e("chinup", "Dominadas supinas", BACK, BODYWEIGHT_REPS),
        e("lat_pulldown", "Jalón al pecho", BACK),
        e("barbell_row", "Remo con barra", BACK),
        e("db_row", "Remo con mancuerna", BACK),
        e("seated_row", "Remo en polea sentado", BACK),
        e("tbar_row", "Remo en T", BACK),
        e("hyperextension", "Hiperextensiones", BACK, BODYWEIGHT_REPS),

        e("ohp", "Press militar", SHOULDERS),
        e("db_shoulder", "Press de hombros con mancuernas", SHOULDERS),
        e("lateral_raise", "Elevaciones laterales", SHOULDERS),
        e("front_raise", "Elevaciones frontales", SHOULDERS),
        e("rear_delt", "Pájaros (deltoide posterior)", SHOULDERS),
        e("face_pull", "Face pull", SHOULDERS),
        e("shrug", "Encogimientos", SHOULDERS),

        e("bb_curl", "Curl con barra", BICEPS),
        e("db_curl", "Curl con mancuernas", BICEPS),
        e("hammer_curl", "Curl martillo", BICEPS),
        e("preacher_curl", "Curl predicador", BICEPS),
        e("cable_curl", "Curl en polea", BICEPS),

        e("tri_pushdown", "Extensión de tríceps en polea", TRICEPS),
        e("skullcrusher", "Press francés", TRICEPS),
        e("overhead_ext", "Extensión de tríceps sobre la cabeza", TRICEPS),
        e("close_grip_bench", "Press de banca agarre cerrado", TRICEPS),

        e("squat", "Sentadilla", LEGS),
        e("front_squat", "Sentadilla frontal", LEGS),
        e("goblet_squat", "Sentadilla goblet", LEGS),
        e("leg_press", "Prensa de piernas", LEGS),
        e("lunge", "Zancadas", LEGS),
        e("bulgarian", "Sentadilla búlgara", LEGS),
        e("leg_ext", "Extensión de cuádriceps", LEGS),
        e("leg_curl", "Curl femoral", LEGS),
        e("rdl", "Peso muerto rumano", LEGS),

        e("hip_thrust", "Hip thrust", GLUTES),
        e("glute_bridge", "Puente de glúteos", GLUTES, BODYWEIGHT_REPS),
        e("cable_kickback", "Patada de glúteo en polea", GLUTES),
        e("abduction", "Abductores en máquina", GLUTES),

        e("standing_calf", "Gemelos de pie", CALVES),
        e("seated_calf", "Gemelos sentado", CALVES),

        e("plank", "Plancha", CORE, DURATION),
        e("side_plank", "Plancha lateral", CORE, DURATION),
        e("crunch", "Abdominales", CORE, BODYWEIGHT_REPS),
        e("hanging_leg", "Elevación de piernas colgado", CORE, BODYWEIGHT_REPS),
        e("ab_wheel", "Rueda abdominal", CORE, BODYWEIGHT_REPS),
        e("cable_crunch", "Crunch en polea", CORE),
        e("russian_twist", "Giros rusos", CORE, BODYWEIGHT_REPS),

        e("kb_swing", "Swing con kettlebell", FULL_BODY),
        e("burpee", "Burpees", FULL_BODY, BODYWEIGHT_REPS),
    )

    private val byId = presets.associateBy { it.id }

    fun preset(id: String?): ExerciseRef? = byId[id]

    fun isCustom(ref: ExerciseRef): Boolean = ref.id.startsWith(CUSTOM_PREFIX)

    fun newCustomId(uuid: String): String = CUSTOM_PREFIX + uuid

    fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    /** Minúsculas y sin tildes: "extension" encuentra "Extensión". */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)

    /** Biblioteca + propios, filtrada por texto y grupo; los propios primero. Orden alfabético estable. */
    fun search(custom: List<ExerciseRef>, query: String, muscle: MuscleGroup? = null): List<ExerciseRef> {
        val q = normalize(query.trim())
        val all = custom.sortedBy { normalize(it.name) } + presets.sortedBy { normalize(it.name) }
        return all.filter { (muscle == null || it.muscle == muscle) && (q.isEmpty() || normalize(it.name).contains(q)) }
    }

    sealed interface AddResult {
        data class Added(val list: List<ExerciseRef>, val ref: ExerciseRef) : AddResult
        data object Blank : AddResult
        data object TooLong : AddResult
        data object Duplicate : AddResult
        data object Full : AddResult
    }

    /** Añade un ejercicio propio. Rechaza vacíos, nombres largos y duplicados (también de la biblioteca). */
    fun addCustom(custom: List<ExerciseRef>, rawName: String, muscle: MuscleGroup, kind: ExerciseKind, uuid: String): AddResult {
        val name = cleanName(rawName)
        if (name.isEmpty()) return AddResult.Blank
        if (name.length > NAME_MAX) return AddResult.TooLong
        if (custom.size >= MAX_CUSTOM) return AddResult.Full
        val key = normalize(name)
        if ((custom + presets).any { normalize(it.name) == key }) return AddResult.Duplicate
        val ref = ExerciseRef(newCustomId(uuid), name, muscle, kind)
        return AddResult.Added(custom + ref, ref)
    }
}
