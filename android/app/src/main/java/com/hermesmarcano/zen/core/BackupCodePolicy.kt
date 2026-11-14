package com.hermesmarcano.zen.core

/**
 * Compuerta del código de respaldo. Sin fricción sería un atajo: el código se puede memorizar
 * y teclear desde la cama, anulando el propósito de la alarma. Solo se habilita tras una espera
 * desde que empieza a sonar; el tiempo es monotónico (`elapsedRealtime`) para que cambiar la
 * hora del sistema no lo salte.
 */
object BackupCodePolicy {
    const val DELAY_SECONDS = 120.0
    const val LENGTH = 6

    fun remaining(since: Double, now: Double): Double {
        val elapsed = now - since
        // Un reloj monotónico no retrocede; si lo hiciera, se aplica la espera completa.
        if (elapsed < 0) return DELAY_SECONDS
        return maxOf(0.0, DELAY_SECONDS - elapsed)
    }

    fun isAvailable(since: Double, now: Double): Boolean = remaining(since, now) == 0.0

    /** Solo dígitos ASCII, como máximo [LENGTH]. */
    fun sanitize(input: String): String = input.filter { it in '0'..'9' }.take(LENGTH)

    /** "1:05" para una cuenta atrás. */
    fun countdownText(seconds: Double): String {
        val total = Math.ceil(seconds).toInt()
        return String.format(java.util.Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }
}
