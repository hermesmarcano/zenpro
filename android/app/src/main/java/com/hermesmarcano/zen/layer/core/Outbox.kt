package com.hermesmarcano.zen.layer.core

import java.net.URI

/** Algo pendiente de enviar al cerebro: un evento (JSON) o un tramo de audio (nombre de archivo). */
data class OutboxItem(
    val id: String,
    val kind: Kind,
    /** EVENT: el JSON del evento. AUDIO: el nombre del archivo WAV dentro de `layer/audio/`. */
    val payload: String,
    val createdAtMs: Long,
    /** Intentos de envío (sirve para alargar la espera). Una caída de red también los suma. */
    val attempts: Int = 0,
    /** Veces que el servidor rechazó este elemento en concreto (4xx). Con muchas, se descarta: nunca se arreglará solo. */
    val rejections: Int = 0,
    val notBeforeMs: Long = 0L,
) {
    enum class Kind { EVENT, AUDIO }
}

/** Espera entre reintentos: exponencial con tope y algo de azar para que no se sincronicen. */
object Backoff {
    const val BASE_MS = 30_000L
    const val MAX_MS = 60 * 60 * 1000L

    /** [random] en [0,1): se inyecta para probar. Resultado entre 75 % y 125 % del valor base. */
    fun delayMs(attempt: Int, random: Double): Long {
        val exp = BASE_MS shl minOf(maxOf(attempt - 1, 0), 7)
        val capped = minOf(exp, MAX_MS)
        return (capped * (0.75 + 0.5 * random.coerceIn(0.0, 0.999999))).toLong().coerceAtMost(MAX_MS)
    }
}

object OutboxPolicy {
    /** Lo que espera en el teléfono es lo más sensible: se retiene poco (24 h o 500 elementos). */
    const val MAX_ITEMS = 500
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    const val EVENT_BATCH = 100
    /** Un tramo de audio pesa mucho más que un evento: se envía de uno en uno. */
    const val AUDIO_BATCH = 1

    /** Elementos listos para enviar ahora, del tipo pedido, los más antiguos primero. */
    fun due(items: List<OutboxItem>, kind: OutboxItem.Kind, nowMs: Long, limit: Int): List<OutboxItem> =
        items.asSequence().filter { it.kind == kind && it.notBeforeMs <= nowMs }.sortedBy { it.createdAtMs }.take(limit).toList()

    /** Rechazos tras los cuales se descarta un elemento. Los fallos de red NO cuentan: sin conexión no se pierde nada. */
    const val MAX_REJECTIONS = 5

    /** [rejected]: el servidor lo recibió y dijo que no (a diferencia de no poder llegar a él). */
    fun afterFailure(item: OutboxItem, nowMs: Long, random: Double, rejected: Boolean = false): OutboxItem = item.copy(
        attempts = item.attempts + 1,
        rejections = item.rejections + if (rejected) 1 else 0,
        notBeforeMs = nowMs + Backoff.delayMs(item.attempts + 1, random),
    )

    fun isExhausted(item: OutboxItem): Boolean = item.rejections >= MAX_REJECTIONS

    fun afterSuccess(items: List<OutboxItem>, sentIds: Set<String>): List<OutboxItem> = items.filter { it.id !in sentIds }

    /** Quita lo caducado y, si aún sobra, lo más antiguo. Devuelve lo que se queda y lo descartado. */
    fun prune(items: List<OutboxItem>, nowMs: Long): Pair<List<OutboxItem>, List<OutboxItem>> {
        val (fresh, expired) = items.partition { nowMs - it.createdAtMs <= MAX_AGE_MS }
        if (fresh.size <= MAX_ITEMS) return fresh to expired
        val sorted = fresh.sortedBy { it.createdAtMs }
        val overflow = sorted.size - MAX_ITEMS
        return sorted.drop(overflow) to (expired + sorted.take(overflow))
    }
}

/**
 * Valida la dirección del cerebro. HTTPS siempre vale. HTTP solo cuando el tráfico no sale en claro de todos modos:
 * este mismo teléfono (localhost) o la red cifrada de Tailscale (WireGuard: 100.64.0.0/10, `*.ts.net`). Una red local
 * (192.168.x.x, 10.x.x.x, `*.local`) NO vale: en una wifi cualquiera podría leer el token y lo que se envía.
 * Sin credenciales dentro de la URL.
 */
object BrainUrl {
    enum class Error { EMPTY, MALFORMED, NO_HOST, CREDENTIALS_IN_URL, CLEARTEXT_NOT_ALLOWED }

    sealed interface Result {
        data class Ok(val baseUrl: String) : Result
        data class Invalid(val error: Error) : Result
    }

    fun validate(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Invalid(Error.EMPTY)
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return Result.Invalid(Error.MALFORMED)
        }
        val scheme = uri.scheme?.lowercase() ?: return Result.Invalid(Error.MALFORMED)
        if (scheme != "https" && scheme != "http") return Result.Invalid(Error.MALFORMED)
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
        if (host.isNullOrEmpty()) return Result.Invalid(Error.NO_HOST)
        if (uri.userInfo != null) return Result.Invalid(Error.CREDENTIALS_IN_URL)
        if (scheme == "http" && !isEncryptedPath(host)) return Result.Invalid(Error.CLEARTEXT_NOT_ALLOWED)
        val shownHost = if (':' in host) "[$host]" else host // IPv6 va entre corchetes
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val path = (uri.rawPath ?: "").trimEnd('/')
        return Result.Ok("$scheme://$shownHost$port$path")
    }

    /** ¿Llegar a este host por HTTP deja el tráfico cifrado (o dentro del propio teléfono)? */
    internal fun isEncryptedPath(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host.endsWith(".ts.net")) return true
        if (host.startsWith("fd7a:115c:a1e0:")) return true // IPv6 de Tailscale
        val parts = host.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        // «100.064.0.1» (ceros a la izquierda) puede leerse como octal en otros sitios: no se acepta.
        if (parts.zip(n).any { (text, value) -> text != value.toString() }) return false
        return n[0] == 127 || (n[0] == 100 && n[1] in 64..127) // este teléfono, o Tailscale
    }
}
