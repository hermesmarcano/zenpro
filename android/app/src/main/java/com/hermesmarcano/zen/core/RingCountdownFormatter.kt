package com.hermesmarcano.zen.core

/** "Suena en 7 h 12 min", a partir de la distancia en milisegundos hasta el próximo disparo. */
object RingCountdownFormatter {
    fun format(millisUntil: Long): String {
        if (millisUntil < 60_000) return "Suena en menos de 1 min"
        val totalMinutes = (millisUntil + 59_999) / 60_000 // se redondea hacia arriba: nunca promete menos de lo que falta
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "Suena en $days d $hours h"
            hours > 0 -> "Suena en $hours h $minutes min"
            else -> "Suena en $minutes min"
        }
    }
}
