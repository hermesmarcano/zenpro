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

    /**
     * Una PROPUESTA de evento para el calendario (hora en ms desde 1970). No agenda nada: la persona ve una notificación y, al
     * tocarla, se abre su calendario con el evento relleno; lo guarda ella. [from] lo pone el servidor (quién lo escribió).
     */
    data class CalendarEvent(override val id: String, val title: String, val startMs: Long, val endMs: Long, val from: String) : BrainCommand
}

object CommandPolicy {
    const val MAX_BLOCK_MINUTES = 24 * 60
    const val MAX_PAUSE_MINUTES = 24 * 60
    const val MAX_NOTIFY_CHARS = 200
    const val MAX_NOTIFY_TITLE_CHARS = 60
    const val REMEMBER_IDS = 500
    const val MAX_EVENT_TITLE_CHARS = 100
    const val MAX_EVENT_FROM_CHARS = 60
    const val MAX_EVENT_MS = 12L * 60 * 60 * 1000
    /** Un evento propuesto debe empezar entre hace una hora y dentro de ~13 meses: lo demás es un error (o una trampa). */
    const val EVENT_PAST_MS = 60L * 60 * 1000
    const val EVENT_FUTURE_MS = 400L * 24 * 60 * 60 * 1000

    enum class Verdict { ACCEPT, DUPLICATE, INVALID, PROTECTED, NOT_ALLOWED, LINK }

    // Sin enlaces en los avisos del cerebro: esquemas, www., cualquier «algo.tld», direcciones IP y ofuscaciones (`evil[.]com`).
    // Red amplia a propósito: un aviso rechazado por «p.ej.» cuesta poco; uno con un enlace malo, mucho.
    private val link = Regex(
        "([a-z][a-z0-9+.\\-]*://|\\bwww\\.|\\b(mailto|tel|sms|intent|javascript|data|file):|" +
            "\\b\\d{1,3}(\\.\\d{1,3}){3}\\b|[\\p{L}\\p{N}_\\-]+(\\.[\\p{L}\\p{N}_\\-]+)*\\.\\p{L}{2,})",
        RegexOption.IGNORE_CASE,
    )
    private val invisible = Regex("\\p{Cf}")
    private val defang = Regex("[\\[({]\\s*(\\.|dot|punto)\\s*[\\])}]", RegexOption.IGNORE_CASE)

    fun hasLink(text: String): Boolean {
        // NFKC: «．» de ancho completo → «.»; luego «[.]», «(dot)»… → «.».
        val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
            .replace(invisible, "") // espacios de ancho cero y otros caracteres de formato: `evil\u200b.com`
            .replace('\u3002', '.') // «。» (punto ideográfico), que NFKC no iguala a «.»
        val flat = defang.replace(normalized, ".")
        return link.containsMatchIn(flat)
    }

    /**
     * [blockable]: las únicas apps que el cerebro puede bloquear, es decir, las que el usuario ya puso bajo una regla
     * en «Límites». Sin regla, el cerebro no toca la app (por defecto no bloquea nada).
     */
    fun check(command: BrainCommand, ownPackage: String, seenIds: Collection<String>, blockable: Set<String> = emptySet()): Verdict {
        if (command.id.isBlank()) return Verdict.INVALID
        if (command.id in seenIds) return Verdict.DUPLICATE
        return when (command) {
            is BrainCommand.DraftReply ->
                if (command.conversationKey.isBlank() || command.text.isBlank()) Verdict.INVALID else Verdict.ACCEPT
            is BrainCommand.Block -> when {
                command.pkg.isBlank() || command.minutes !in 1..MAX_BLOCK_MINUTES -> Verdict.INVALID
                NeverBlock.isProtected(command.pkg, ownPackage) -> Verdict.PROTECTED
                command.pkg !in blockable -> Verdict.NOT_ALLOWED
                else -> Verdict.ACCEPT
            }
            is BrainCommand.Notify -> when {
                command.text.isBlank() || command.text.length > MAX_NOTIFY_CHARS || command.title.length > MAX_NOTIFY_TITLE_CHARS -> Verdict.INVALID
                hasLink(command.text) || hasLink(command.title) -> Verdict.LINK
                else -> Verdict.ACCEPT
            }
            is BrainCommand.Pause ->
                if (command.minutes !in 1..MAX_PAUSE_MINUTES) Verdict.INVALID else Verdict.ACCEPT
            is BrainCommand.CalendarEvent -> when {
                command.title.isBlank() || command.title.length > MAX_EVENT_TITLE_CHARS || command.from.length > MAX_EVENT_FROM_CHARS -> Verdict.INVALID
                command.startMs <= 0 || command.endMs <= command.startMs || command.endMs - command.startMs > MAX_EVENT_MS -> Verdict.INVALID
                hasLink(command.title) -> Verdict.LINK
                else -> Verdict.ACCEPT
            }
        }
    }

    /** Memoria de órdenes ya vistas (las más recientes), para no ejecutar dos veces una que el cerebro reenvía. */
    fun remember(seen: List<String>, id: String): List<String> = (seen + id).distinct().takeLast(REMEMBER_IDS)
}
