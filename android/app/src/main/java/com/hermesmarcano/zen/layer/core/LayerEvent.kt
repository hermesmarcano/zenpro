package com.hermesmarcano.zen.layer.core

/** Algo que la capa observó o hizo, ya filtrado y sin secretos. Es la unidad de la memoria local y del envío al cerebro. */
data class LayerEvent(
    val id: String,
    val kind: Kind,
    val pkg: String,
    val title: String,
    val text: String,
    val atMs: Long,
    val extra: Map<String, String> = emptyMap(),
) {
    enum class Kind { SCREEN, NOTIFICATION, AUDIO, USAGE, SYSTEM, OVERRIDE }

    companion object {
        const val MAX_TEXT = 4_000
        const val MAX_TITLE = 200

        /** Recorta sin partir un par de sustitución (emoji) por la mitad. */
        fun clamp(text: String, max: Int): String {
            if (text.length <= max) return text
            var end = max
            if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
            return text.substring(0, end)
        }
    }
}

/** Respuesta propuesta a una conversación. Nunca se envía sin que el usuario la apruebe. */
enum class DraftStatus { PENDING, SENT, DISCARDED, EXPIRED, FAILED }

data class Draft(
    val id: String,
    /** Clave de la conversación en el lector de notificaciones (ver ReplyActionCache). */
    val conversationKey: String,
    val pkg: String,
    val contact: String,
    val text: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val status: DraftStatus = DraftStatus.PENDING,
)

object DraftBoard {
    const val TTL_MS = 2 * 60 * 60 * 1000L
    const val MAX_PENDING = 20
    const val MAX_TEXT = 1_000
    const val KEEP_RESOLVED = 30

    /**
     * Añade un borrador. Uno nuevo para la misma conversación sustituye al pendiente anterior (el cerebro
     * suele refinar), y si hay demasiados pendientes se descartan los más viejos.
     */
    fun add(drafts: List<Draft>, draft: Draft, nowMs: Long): List<Draft> {
        val text = LayerEvent.clamp(draft.text.trim(), MAX_TEXT)
        if (text.isEmpty() || draft.conversationKey.isBlank()) return expire(drafts, nowMs)
        val live = expire(drafts, nowMs).map {
            if (it.status == DraftStatus.PENDING && it.conversationKey == draft.conversationKey) it.copy(status = DraftStatus.DISCARDED) else it
        }
        val withNew = live + draft.copy(text = text, status = DraftStatus.PENDING)
        val pending = withNew.filter { it.status == DraftStatus.PENDING }.sortedBy { it.createdAtMs }
        val dropIds = pending.take(maxOf(0, pending.size - MAX_PENDING)).map { it.id }.toSet()
        val capped = withNew.map { if (it.id in dropIds) it.copy(status = DraftStatus.DISCARDED) else it }
        return trimResolved(capped)
    }

    /** Solo un borrador pendiente puede resolverse; uno ya cerrado no cambia. */
    fun resolve(drafts: List<Draft>, id: String, status: DraftStatus): List<Draft> {
        require(status != DraftStatus.PENDING) { "resolve necesita un estado final" }
        return drafts.map { if (it.id == id && it.status == DraftStatus.PENDING) it.copy(status = status) else it }
    }

    fun expire(drafts: List<Draft>, nowMs: Long): List<Draft> =
        drafts.map { if (it.status == DraftStatus.PENDING && it.expiresAtMs <= nowMs) it.copy(status = DraftStatus.EXPIRED) else it }

    fun pending(drafts: List<Draft>, nowMs: Long): List<Draft> =
        drafts.filter { it.status == DraftStatus.PENDING && it.expiresAtMs > nowMs }.sortedBy { it.createdAtMs }

    private fun trimResolved(drafts: List<Draft>): List<Draft> {
        val resolved = drafts.filter { it.status != DraftStatus.PENDING }.sortedBy { it.createdAtMs }
        val dropIds = resolved.take(maxOf(0, resolved.size - KEEP_RESOLVED)).map { it.id }.toSet()
        return drafts.filter { it.id !in dropIds }
    }
}
