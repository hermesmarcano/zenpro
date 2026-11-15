package com.hermesmarcano.zen.core

/** Reglas para aceptar un archivo de audio del usuario como sonido de alarma o de éxito. */
object SoundImportPolicy {
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MIN_DURATION_MS = 300L
    const val MAX_DURATION_MS = 10L * 60 * 1000

    /** Un sonido de éxito largo se corta a este tiempo: es un aviso, no una canción entera. */
    const val COMPLETION_MAX_PLAY_MS = 10_000L

    sealed interface Result {
        data object Ok : Result
        data class Rejected(val reason: String) : Result
    }

    /** [durationMs] es `null` si el sistema no pudo leer el audio (archivo corrupto o no es audio). */
    fun validate(sizeBytes: Long, durationMs: Long?): Result = when {
        sizeBytes <= 0 -> Result.Rejected("El archivo está vacío.")
        sizeBytes > MAX_BYTES -> Result.Rejected("El archivo pesa demasiado (máximo 20 MB).")
        durationMs == null -> Result.Rejected("No se pudo leer como audio.")
        durationMs < MIN_DURATION_MS -> Result.Rejected("El audio es demasiado corto.")
        durationMs > MAX_DURATION_MS -> Result.Rejected("El audio dura demasiado (máximo 10 minutos).")
        else -> Result.Ok
    }

    /** Nombres guardados por la app: sin separadores ni "..", para que nunca salgan de su carpeta. */
    fun isSafeFileName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 80 && name.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } && ".." !in name
}
