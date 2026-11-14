package com.hermesmarcano.zen.core

/**
 * Decide si una foto candidata coincide con las referencias de un ancla, a partir de las
 * distancias entre descriptores (menor = más parecida). Lógica pura: calcular las distancias
 * vive en [PhotoDescriptor].
 */
object PhotoMatchPolicy {
    /**
     * Distancia máxima (exclusiva) para aceptar una foto como "el mismo lugar". Calibrada con
     * variaciones sintéticas (luz, ruido, desplazamiento) en los tests; [CALIBRAR EN DISPOSITIVO]
     * con fotos reales de día vs. madrugada. Si se queda corto el usuario cae al código de
     * respaldo; si es laxo, se aceptan lugares parecidos. Es la única constante que ajustar.
     */
    const val DEFAULT_THRESHOLD = 0.40f

    /** Fallos consecutivos tras los cuales la pantalla sugiere más luz o el código de respaldo. */
    const val FAILURES_BEFORE_HINT = 3

    /**
     * Mejor (menor) distancia válida. Ignora NaN, infinitos, negativos y **ceros**: una
     * distancia exactamente 0 no ocurre entre dos fotos tomadas por separado (el ruido del
     * sensor basta para que sea > 0). Sí ocurriría con un descriptor degenerado, y entonces
     * "todo coincide" apagaría la alarma sin ir al lugar. Descartarlas lo convierte en "no se
     * pudo analizar", nunca en un acierto.
     */
    fun bestDistance(distances: List<Float>): Float? =
        distances.filter { it.isFinite() && it > 0f }.minOrNull()

    /** Coincide si la mejor distancia es estrictamente menor al umbral. Sin distancias válidas, nunca. */
    fun isMatch(distances: List<Float>, threshold: Float = DEFAULT_THRESHOLD): Boolean {
        val best = bestDistance(distances) ?: return false
        return best < threshold
    }

    fun shouldShowHint(consecutiveFailures: Int): Boolean = consecutiveFailures >= FAILURES_BEFORE_HINT
}
