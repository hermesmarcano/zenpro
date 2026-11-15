package com.hermesmarcano.zen.core

/**
 * Parámetros de la curva de volumen. Los valores por defecto codifican el comportamiento
 * pedido: arranca bajo; al coger el teléfono baja a la mitad y, mientras hay movimiento, a
 * ~10 %; si se deja quieto sin apagar vuelve al 50 % y sube progresivamente a 100 %.
 */
data class SmartVolumeProfile(
    val startVolume: Float = 0.15f,
    val maxVolume: Float = 1.0f,
    val initialRampDuration: Double = 90.0,
    /** Fracción del volumen que se conserva en el instante de coger el teléfono. */
    val pickupFactor: Float = 0.5f,
    /** Tiempo que se mantiene el volumen "a la mitad" antes de bajar al de marcha. */
    val pickupHoldDuration: Double = 1.5,
    val carriedVolume: Float = 0.10f,
    /** Quietud sostenida (sin interactuar) que se interpreta como "lo dejó por ahí". */
    val stationaryDelay: Double = 5.0,
    val leftBehindStartVolume: Float = 0.5f,
    val leftBehindRampDuration: Double = 60.0,
    /** Límite de pendiente: ningún cambio de volumen es instantáneo. */
    val maxFallPerSecond: Float = 0.35f,
    val maxRisePerSecond: Float = 0.5f,
    /** El intervalo más corto deja una ventana sin vibrar entre pulsos: durante cada pulso se ignora el sensor. */
    val slowestVibrationInterval: Double = 2.5,
    val fastestVibrationInterval: Double = 1.2,
)

/**
 * Perfil con el volumen inicial elegido por el usuario. Al coger el teléfono la alarma baja "un poco"
 * por debajo de donde empezó: el nivel en marcha es el 60 % del inicial (nunca menos de 5 %).
 */
fun SmartVolumeProfile.withStartVolume(start: Float): SmartVolumeProfile =
    copy(startVolume = start, carriedVolume = maxOf(0.05f, start * 0.6f))

/** `vibrationInterval` en segundos entre pulsos; `null` = sin vibración. */
data class SmartVolumeOutput(val volume: Float, val vibrationInterval: Double?)

/**
 * Máquina de estados del volumen de la alarma. Es una función determinista del tiempo y de las
 * señales de entrada (sin relojes ni sensores propios), así que toda la curva se prueba sin
 * teléfono.
 */
class SmartVolumeController(
    private val profile: SmartVolumeProfile = SmartVolumeProfile(),
    private val startTime: Double,
) {
    sealed interface Phase {
        data object InitialRamp : Phase
        data class Carried(val since: Double, val volumeAtPickup: Float) : Phase
        data class LeftBehind(val since: Double) : Phase
    }

    var phase: Phase = Phase.InitialRamp
        private set
    var volume: Float = profile.startVolume
        private set

    private var lastUpdate = startTime
    private var stillSince: Double? = null

    /**
     * @param userEngaged el usuario está interactuando con la pantalla de la alarma (cámara
     *   abierta). Se trata como "en mano" aunque el teléfono esté quieto; si no, sostenerlo
     *   firme para apuntar al QR dispararía `LeftBehind` y subiría el volumen justo cuando
     *   intenta apagarla.
     */
    fun update(rawNow: Double, motion: MotionState, userEngaged: Boolean): SmartVolumeOutput {
        // Un reloj que retrocede no debe producir pasos negativos ni fases al revés.
        val now = maxOf(rawNow, lastUpdate)
        val deltaTime = now - lastUpdate
        lastUpdate = now

        advancePhase(now, motion, userEngaged)
        applySlew(targetVolume(now), deltaTime)
        return SmartVolumeOutput(volume, vibrationInterval())
    }

    private fun advancePhase(now: Double, motion: MotionState, userEngaged: Boolean) {
        when (phase) {
            is Phase.InitialRamp, is Phase.LeftBehind -> {
                if (motion == MotionState.MOVING || userEngaged) {
                    phase = Phase.Carried(since = now, volumeAtPickup = volume)
                    stillSince = null
                }
            }
            is Phase.Carried -> {
                if (motion != MotionState.STILL || userEngaged) {
                    stillSince = null
                    return
                }
                val since = stillSince ?: now
                stillSince = since
                if (now - since >= profile.stationaryDelay) {
                    phase = Phase.LeftBehind(since = now)
                    stillSince = null
                }
            }
        }
    }

    private fun targetVolume(now: Double): Float = when (val current = phase) {
        is Phase.InitialRamp -> interpolate(
            profile.startVolume, profile.maxVolume, now - startTime, profile.initialRampDuration,
        )
        is Phase.Carried -> {
            val halved = maxOf(profile.carriedVolume, current.volumeAtPickup * profile.pickupFactor)
            if (now - current.since < profile.pickupHoldDuration) halved else profile.carriedVolume
        }
        is Phase.LeftBehind -> interpolate(
            profile.leftBehindStartVolume, profile.maxVolume, now - current.since, profile.leftBehindRampDuration,
        )
    }

    private fun interpolate(from: Float, to: Float, elapsed: Double, duration: Double): Float {
        if (duration <= 0) return to
        val progress = (elapsed / duration).coerceIn(0.0, 1.0).toFloat()
        return from + (to - from) * progress
    }

    private fun applySlew(target: Float, deltaTime: Double) {
        val step = deltaTime.toFloat()
        volume = if (target > volume) {
            minOf(target, volume + profile.maxRisePerSecond * step)
        } else {
            maxOf(target, volume - profile.maxFallPerSecond * step)
        }
        volume = volume.coerceIn(0f, 1f)
    }

    private fun vibrationInterval(): Double? {
        if (phase is Phase.Carried) return null
        val range = profile.maxVolume - profile.startVolume
        val fraction = if (range > 0) ((volume - profile.startVolume) / range).coerceIn(0f, 1f).toDouble() else 1.0
        val slowest = profile.slowestVibrationInterval
        val fastest = profile.fastestVibrationInterval
        return slowest + (fastest - slowest) * fraction
    }
}
