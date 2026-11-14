package com.hermesmarcano.zen.core

/** Día de la semana (numeración ISO: 1 = lunes … 7 = domingo). */
enum class Weekday(val iso: Int, val shortLabel: String, val fullName: String) {
    MONDAY(1, "L", "lunes"),
    TUESDAY(2, "M", "martes"),
    WEDNESDAY(3, "X", "miércoles"),
    THURSDAY(4, "J", "jueves"),
    FRIDAY(5, "V", "viernes"),
    SATURDAY(6, "S", "sábado"),
    SUNDAY(7, "D", "domingo");

    companion object {
        /** Orden de la semana en español (empieza en lunes). */
        val mondayFirst: List<Weekday> = entries.sortedBy { it.iso }

        fun fromIso(iso: Int): Weekday? = entries.firstOrNull { it.iso == iso }

        /** "L M X" / "Una vez" / "Todos los días" para el resumen de una alarma. */
        fun summary(days: Set<Weekday>): String {
            val ordered = mondayFirst.filter { it in days }
            return when {
                ordered.isEmpty() -> "Una vez"
                ordered.size == 7 -> "Todos los días"
                else -> ordered.joinToString(" ") { it.shortLabel }
            }
        }
    }
}
