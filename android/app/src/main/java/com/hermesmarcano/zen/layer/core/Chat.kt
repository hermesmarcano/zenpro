package com.hermesmarcano.zen.layer.core

import java.io.IOException

enum class ChatRole {
    /** Lo que escribió o dijo la persona. */
    USER,

    /** Lo que respondió el cerebro. */
    BRAIN,

    /** Una línea de ZEN (no del cerebro): qué se hizo con una orden, o por qué no. */
    NOTE,
}

/**
 * Un evento que el cerebro propone agendar. Se ve como tarjeta con «Agendar»: al tocarla se abre el calendario con el evento
 * relleno y lo guarda la persona. Nada se agenda solo, y la app no pide permisos del calendario.
 */
data class ChatProposal(val commandId: String, val title: String, val startMs: Long, val endMs: Long)

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    /** Vacío en un mensaje de voz hasta que el servidor devuelve la transcripción. */
    val text: String,
    val atMs: Long,
    val voice: Boolean = false,
    /** Solo en mensajes de la persona: por qué no llegó una respuesta. */
    val failure: ChatFailure? = null,
    val proposal: ChatProposal? = null,
)

/** La conversación guardada. [session] es lo que une los mensajes en el servidor (el modelo recuerda lo anterior por ella). */
data class ChatSnapshot(val session: String, val messages: List<ChatMessage>)

/** Por qué un mensaje no obtuvo respuesta, con el texto que se le dice a la persona. */
enum class ChatFailure(val message: String, val retryable: Boolean) {
    UNAUTHORIZED("El cerebro rechazó el token. Revísalo en Capa → Cerebro.", false),
    BUSY("El cerebro está ocupado. Prueba en un momento.", true),

    /** El cortacircuitos del servidor (un modelo que se portó raro): unos minutos sin atender; reintentar enseguida no sirve. */
    COOLDOWN("El cerebro está en pausa de seguridad unos minutos. Prueba más tarde.", false),

    /** Fallo interno del servidor (no es la red ni el modelo). */
    SERVER_ERROR("El cerebro tuvo un fallo interno. Prueba de nuevo en un momento.", true),
    RATE_LIMITED("Llegaste al límite de mensajes por ahora. Prueba más tarde.", true),
    NO_SPEECH("No se oyó nada claro. Acércate al micrófono y prueba de nuevo.", false),
    STT_OFF("El servidor no tiene activada la transcripción de voz.", false),
    STT_FAILED("No se pudo transcribir el audio. Prueba de nuevo.", false),
    CHAT_OFF("El chat está apagado en el servidor.", false),
    TIMEOUT("El modelo tardó demasiado en responder.", true),
    BAD_REPLY("El modelo respondió algo que no se pudo leer.", true),
    UNAVAILABLE("No se pudo conectar con el cerebro.", true),
    REJECTED("El cerebro rechazó el mensaje.", false),

    /** El mensaje se guardó pero la app se cerró antes de que llegara la respuesta. */
    INTERRUPTED("La respuesta no llegó (la app se cerró).", true),
    ;

    companion object {
        /** Traduce la respuesta de error del adaptador (`{"error": "<código>"}` con su HTTP) a un motivo. */
        fun fromHttp(code: Int, error: String): ChatFailure = when {
            code == 401 || code == 403 -> UNAUTHORIZED
            code == 404 && error == "chat_off" -> CHAT_OFF
            code == 422 && error == "no_speech" -> NO_SPEECH
            code == 422 && error == "stt_off" -> STT_OFF
            code == 422 && error == "stt_failed" -> STT_FAILED
            code == 429 -> RATE_LIMITED
            code == 503 && error == "busy" -> BUSY
            code == 503 && error == "cooldown" -> COOLDOWN
            code == 500 || code == 503 -> SERVER_ERROR
            code == 504 -> TIMEOUT
            code == 502 && error == "bad_reply" -> BAD_REPLY
            code in 400..499 && code != 408 && code != 425 -> REJECTED
            else -> UNAVAILABLE
        }

        fun fromName(name: String): ChatFailure? = entries.firstOrNull { it.name == name }
    }
}

object ChatRules {
    /** Lo que el adaptador acepta por mensaje (y el modelo, por respuesta, 4000). */
    const val MAX_TEXT_CHARS = 2000
    const val MAX_HISTORY = 100
    const val MAX_REPLY_CHARS = 4000
    const val SAMPLE_RATE = 16_000

    /** El servidor rechaza más de 30 s: se corta un poco antes. */
    const val MAX_VOICE_MS = 29_000L
    const val MIN_VOICE_MS = 600L

    /** Órdenes por respuesta y las únicas que el chat acepta (el adaptador filtra igual: dos barreras). */
    const val MAX_COMMANDS = 3

    fun isChatCommand(command: BrainCommand): Boolean =
        command is BrainCommand.CalendarEvent || command is BrainCommand.Block || command is BrainCommand.Pause

    /** El texto listo para enviar: sin espacios sobrantes ni caracteres de control; `null` si queda vacío o es demasiado largo. */
    fun cleanInput(raw: String): String? {
        val text = raw.trim().filter { it == '\n' || it == '\t' || !it.isISOControl() }
        return text.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT_CHARS }
    }

    /** Una conversación «de verdad» (para el juego): al menos tres palabras. Un «hola» no suma, ni repetirlo. */
    fun qualifies(text: String): Boolean = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= 3

    /**
     * Lo que dice el modelo (o la transcripción) antes de mostrarlo: sin caracteres de control ni de dirección de texto
     * (con ellos se puede disfrazar lo que se lee), y no más de [MAX_REPLY_CHARS]. Se muestra como texto plano, sin enlaces activos.
     */
    fun cleanReply(raw: String): String {
        val kept = raw.filter { c -> c == '\n' || c == '\t' || !c.isISOControl() }
        return kept.replace(INVISIBLE, "").trim().take(MAX_REPLY_CHARS)
    }

    /** Dirección de texto, ceros anchos, marca de orden de bytes, U+061C y las «etiquetas» U+E0000: invisibles que disfrazan lo que se lee. (Sí se conservan ZWJ/ZWNJ de emojis y de otros alfabetos.) */
    private val INVISIBLE = Regex("[\\u202A-\\u202E\\u2066-\\u2069\\u200B\\u200E\\u200F\\u2060-\\u2064\\uFEFF\\u061C\\x{E0000}-\\x{E007F}]")

    fun trim(messages: List<ChatMessage>): List<ChatMessage> = messages.takeLast(MAX_HISTORY)

    fun voiceMs(samples: Int): Long = samples * 1000L / SAMPLE_RATE
}

/** Dónde se guarda la conversación (cifrada). Sin plan B en claro: si no se puede cifrar, no se guarda. */
interface ChatStore {
    /** `null`: no hay nada guardado, o es irrecuperable. `IOException`: ahora no se pudo leer (no hay que pisarlo). */
    @Throws(IOException::class)
    fun load(): ChatSnapshot?

    @Throws(IOException::class)
    fun save(snapshot: ChatSnapshot)

    @Throws(IOException::class)
    fun clear()
}
