package com.hermesmarcano.zen.layer.core

/**
 * La lista de servicios de accesibilidad activos de Android es un texto `paquete/Clase:paquete2/Clase2`.
 * Estas funciones la manipulan sin romper las entradas de otras apps y sin duplicar la nuestra.
 */
object AccessibilitySetting {
    /** `pkg/.Clase` y `pkg/pkg.Clase` son la misma cosa: se comparan en su forma larga. */
    fun normalize(entry: String): String {
        val trimmed = entry.trim()
        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash == trimmed.lastIndex) return trimmed
        val pkg = trimmed.substring(0, slash)
        val cls = trimmed.substring(slash + 1)
        return if (cls.startsWith(".")) "$pkg/$pkg$cls" else "$pkg/$cls"
    }

    fun entries(raw: String?): List<String> = raw.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }

    fun contains(raw: String?, component: String): Boolean {
        val wanted = normalize(component)
        return entries(raw).any { normalize(it) == wanted }
    }

    fun withEnabled(raw: String?, component: String): String {
        if (contains(raw, component)) return entries(raw).joinToString(":")
        return (entries(raw) + component.trim()).joinToString(":")
    }

    fun without(raw: String?, component: String): String {
        val wanted = normalize(component)
        return entries(raw).filter { normalize(it) != wanted }.joinToString(":")
    }
}

sealed interface HealDecision {
    /** Todo bien. */
    data object Healthy : HealDecision

    /** Parece caído pero aún puede estar arrancando: anotar desde cuándo y volver a mirar. */
    data class Wait(val markDownAtMs: Long?) : HealDecision

    /** No está en la lista de activos y se puede escribir en ella: añadirlo. */
    data object Enable : HealDecision

    /** Está en la lista pero no responde: apagarlo y volver a encenderlo. */
    data object Restart : HealDecision

    /** No se puede arreglar solo: hay que avisar al usuario. */
    data class NeedUser(val reason: Reason) : HealDecision

    enum class Reason { NOT_ENABLED, NOT_RESPONDING, REPAIR_LIMIT }
}

/**
 * Decide si hay que reparar el servicio de accesibilidad. Xiaomi puede matarlo dejando el interruptor
 * de Ajustes en "activado"; con el permiso WRITE_SECURE_SETTINGS (concedido una vez por ADB) la capa puede
 * reactivarse sola. Hay una espera de gracia (el servicio tarda en volver a conectarse tras un arranque)
 * y un tope de reparaciones para no entrar en un bucle de apagar/encender.
 */
object SelfHealPolicy {
    const val GRACE_MS = 2 * 60_000L
    const val MIN_BETWEEN_REPAIRS_MS = 10 * 60_000L
    const val WINDOW_MS = 6 * 60 * 60_000L
    const val MAX_REPAIRS_IN_WINDOW = 4

    fun decide(
        wanted: Boolean,
        listed: Boolean,
        connected: Boolean,
        canWriteSecure: Boolean,
        nowMs: Long,
        downSinceMs: Long?,
        recentRepairsMs: List<Long>,
    ): HealDecision {
        if (!wanted || (listed && connected)) return HealDecision.Healthy
        val repairsAllowed = repairsAllowed(nowMs, recentRepairsMs)
        if (!listed) {
            if (!canWriteSecure) return HealDecision.NeedUser(HealDecision.Reason.NOT_ENABLED)
            return if (repairsAllowed) HealDecision.Enable else HealDecision.NeedUser(HealDecision.Reason.REPAIR_LIMIT)
        }
        // Está en la lista pero no conectado.
        if (downSinceMs == null || downSinceMs > nowMs) return HealDecision.Wait(nowMs)
        if (nowMs - downSinceMs < GRACE_MS) return HealDecision.Wait(downSinceMs)
        if (!canWriteSecure) return HealDecision.NeedUser(HealDecision.Reason.NOT_RESPONDING)
        return if (repairsAllowed) HealDecision.Restart else HealDecision.NeedUser(HealDecision.Reason.REPAIR_LIMIT)
    }

    fun repairsAllowed(nowMs: Long, recentRepairsMs: List<Long>): Boolean {
        // Las marcas "del futuro" (el reloj retrocedió) no cuentan: ni bloquean ni se acumulan.
        val inWindow = recentRepairsMs.filter { nowMs - it in 0..WINDOW_MS }
        if (inWindow.size >= MAX_REPAIRS_IN_WINDOW) return false
        val last = inWindow.maxOrNull() ?: return true
        return nowMs - last >= MIN_BETWEEN_REPAIRS_MS
    }

    fun recordRepair(recentRepairsMs: List<Long>, nowMs: Long): List<Long> =
        (recentRepairsMs.filter { nowMs - it in 0..WINDOW_MS } + nowMs).takeLast(MAX_REPAIRS_IN_WINDOW * 2)
}
