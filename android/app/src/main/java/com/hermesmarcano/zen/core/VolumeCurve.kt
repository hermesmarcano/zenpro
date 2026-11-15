package com.hermesmarcano.zen.core

import kotlin.math.pow

/**
 * Convierte el "nivel" de la alarma (0..1, lo que el usuario entiende por "15 %") en la ganancia
 * lineal que se le da al reproductor.
 *
 * El oído percibe la sonoridad de forma aproximadamente logarítmica (se duplica cada ~10 dB, es
 * decir, sonoridad ∝ amplitud^0,6). Usar el nivel como ganancia lineal hacía que "15 %" fueran solo
 * −16 dB: en un tono agudo y puro seguía sonando fuerte. Con esta curva el nivel es sonoridad
 * *percibida*: 15 % suena como la séptima parte de lo máximo, no como casi un quinto de la amplitud.
 */
object VolumeCurve {
    /** 1 / 0,6: inversa de la ley de potencias de la sonoridad. */
    const val EXPONENT = 1.7f

    fun gain(level: Float): Float {
        if (level.isNaN()) return 0f
        return level.coerceIn(0f, 1f).pow(EXPONENT)
    }

    /** Ganancia en decibelios respecto al máximo (para pruebas y registros). */
    fun decibels(level: Float): Float {
        val g = gain(level)
        return if (g <= 0f) Float.NEGATIVE_INFINITY else 20f * kotlin.math.log10(g)
    }
}
