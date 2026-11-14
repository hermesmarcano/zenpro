package com.hermesmarcano.zen.core

/** Aceleración en g (sin gravedad) y rotación en rad/s, con la marca de tiempo en segundos. */
data class MotionSample(
    val timestamp: Double,
    val userAcceleration: Double,
    val rotationRate: Double,
)

enum class MotionState { STILL, MOVING }

/**
 * Clasifica el flujo de muestras de movimiento en quieto / en movimiento con histéresis,
 * para que el ruido del sensor o un golpe suelto no hagan oscilar el volumen. Es lógica pura
 * (sin Android): la lectura real de los sensores vive en `MotionMonitor`.
 *
 * Los umbrales de [Tuning] son estimaciones sin medir en un teléfono real [CALIBRAR EN DISPOSITIVO].
 */
class MotionClassifier(private val tuning: Tuning = Tuning()) {
    data class Tuning(
        val windowDuration: Double = 1.5,
        /** Aceleración RMS (g) que, sola, cuenta como un punto de "energía". */
        val accelerationScale: Double = 0.04,
        /** Rotación RMS (rad/s) que, sola, cuenta como un punto de "energía". */
        val rotationScale: Double = 0.2,
        val movingScore: Double = 1.0,
        val stillScore: Double = 0.4,
    )

    private class Entry(val timestamp: Double, val accelerationSquared: Double, val rotationSquared: Double)

    var state: MotionState = MotionState.STILL
        private set
    private val window = ArrayDeque<Entry>()

    fun reset() {
        state = MotionState.STILL
        window.clear()
    }

    fun ingest(sample: MotionSample): MotionState {
        // Si el reloj de los sensores retrocede (reinicio del sensor) las entradas viejas nunca
        // caducarían: se descarta la ventana en vez de arrastrarlas.
        val last = window.lastOrNull()
        if (last != null && sample.timestamp < last.timestamp) window.clear()

        window.addLast(
            Entry(
                sample.timestamp,
                sample.userAcceleration * sample.userAcceleration,
                sample.rotationRate * sample.rotationRate,
            ),
        )
        val cutoff = sample.timestamp - tuning.windowDuration
        while (window.isNotEmpty() && window.first().timestamp < cutoff) window.removeFirst()

        val count = window.size.toDouble()
        val accelerationRms = Math.sqrt(window.sumOf { it.accelerationSquared } / count)
        val rotationRms = Math.sqrt(window.sumOf { it.rotationSquared } / count)
        val score = accelerationRms / tuning.accelerationScale + rotationRms / tuning.rotationScale

        state = when (state) {
            MotionState.STILL -> if (score >= tuning.movingScore) MotionState.MOVING else MotionState.STILL
            MotionState.MOVING -> if (score <= tuning.stillScore) MotionState.STILL else MotionState.MOVING
        }
        return state
    }
}

/**
 * Separa la gravedad de la lectura del acelerómetro con un filtro paso-bajo cuando el
 * teléfono no ofrece el sensor de aceleración lineal. Devuelve la magnitud (en m/s²) de la
 * aceleración sin gravedad.
 */
class GravityFilter(private val alpha: Double = 0.9) {
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var primed = false

    fun linearMagnitude(x: Double, y: Double, z: Double): Double {
        if (!primed) {
            gx = x; gy = y; gz = z
            primed = true
        }
        gx = alpha * gx + (1 - alpha) * x
        gy = alpha * gy + (1 - alpha) * y
        gz = alpha * gz + (1 - alpha) * z
        val lx = x - gx
        val ly = y - gy
        val lz = z - gz
        return Math.sqrt(lx * lx + ly * ly + lz * lz)
    }
}
