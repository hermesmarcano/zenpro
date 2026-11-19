package com.hermesmarcano.zen.layer.core

/**
 * Valida el formulario de un límite y lo convierte en [LimitRule]. Está aparte de la pantalla para probarlo a fondo:
 * un límite mal formado (una franja de 0 minutos, un día de 25 horas) no debe poder guardarse.
 */
object RuleForm {
    sealed interface Result {
        data class Ok(val rule: LimitRule) : Result
        data class Error(val message: String) : Result
    }

    const val MAX_MINUTES = 24 * 60

    fun build(
        pkg: String,
        label: String,
        minutesText: String,
        blockAtLimit: Boolean,
        windowOn: Boolean,
        fromHourText: String,
        toHourText: String,
        ownPackage: String,
    ): Result {
        if (pkg.isBlank()) return Result.Error("Elige una app.")
        if (NeverBlock.isProtected(pkg, ownPackage)) return Result.Error("Esa app está protegida y no se puede limitar.")
        val minutes = minutesText.trim().toIntOrNull() ?: 0
        if (minutes < 0 || minutes > MAX_MINUTES) return Result.Error("Un día tiene $MAX_MINUTES minutos.")
        val window = if (windowOn) {
            val from = fromHourText.trim().toIntOrNull()
            val to = toHourText.trim().toIntOrNull()
            val candidate = if (from != null && to != null && from in 0..23 && to in 0..23) DayWindow(from * 60, to * 60) else null
            if (candidate == null || !candidate.isValid) return Result.Error("La franja necesita dos horas distintas entre 0 y 23.")
            candidate
        } else {
            null
        }
        if (minutes == 0 && window == null) return Result.Error("Pon un límite de minutos o una franja prohibida.")
        // "Bloquear al llegar al límite" no significa nada sin un límite de minutos: no se guarda a medias.
        return Result.Ok(LimitRule(pkg, label.ifBlank { pkg }, minutes * 60_000L, blockAtLimit && minutes > 0, window))
    }
}
