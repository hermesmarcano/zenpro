package com.hermesmarcano.zen.layer.core

/**
 * Lo que el cerebro puede pedirle al teléfono. Es deliberadamente corto y asimétrico: el cerebro puede
 * RESTRINGIR (proponer una respuesta, bloquear una app, pausar la observación, avisar) pero no relajar
 * nada. Quitar un bloqueo solo lo hace el usuario, con fricción (ver RelaxPolicy). Así, un cerebro
 * engañado por un mensaje malicioso como mucho molesta, nunca abre puertas.
 */
sealed interface BrainCommand {
    val id: String

    data class DraftReply(override val id: String, val conversationKey: String, val text: String) : BrainCommand
    data class Block(override val id: String, val pkg: String, val minutes: Int) : BrainCommand
    data class Notify(override val id: String, val title: String, val text: String) : BrainCommand
    data class Pause(override val id: String, val minutes: Int) : BrainCommand
}

object CommandPolicy {
    const val MAX_BLOCK_MINUTES = 24 * 60
    const val MAX_PAUSE_MINUTES = 24 * 60
    const val MAX_NOTIFY_CHARS = 300
    const val REMEMBER_IDS = 500

    enum class Verdict { ACCEPT, DUPLICATE, INVALID, PROTECTED }

    fun check(command: BrainCommand, ownPackage: String, seenIds: Collection<String>): Verdict {
        if (command.id.isBlank()) return Verdict.INVALID
        if (command.id in seenIds) return Verdict.DUPLICATE
        return when (command) {
            is BrainCommand.DraftReply ->
                if (command.conversationKey.isBlank() || command.text.isBlank()) Verdict.INVALID else Verdict.ACCEPT
            is BrainCommand.Block -> when {
                command.pkg.isBlank() || command.minutes !in 1..MAX_BLOCK_MINUTES -> Verdict.INVALID
                NeverBlock.isProtected(command.pkg, ownPackage) -> Verdict.PROTECTED
                else -> Verdict.ACCEPT
            }
            is BrainCommand.Notify ->
                if (command.text.isBlank() || command.text.length > MAX_NOTIFY_CHARS || command.title.length > MAX_NOTIFY_CHARS) Verdict.INVALID else Verdict.ACCEPT
            is BrainCommand.Pause ->
                if (command.minutes !in 1..MAX_PAUSE_MINUTES) Verdict.INVALID else Verdict.ACCEPT
        }
    }

    /** Memoria de órdenes ya vistas (las más recientes), para no ejecutar dos veces una que el cerebro reenvía. */
    fun remember(seen: List<String>, id: String): List<String> = (seen + id).distinct().takeLast(REMEMBER_IDS)
}
