package com.hermesmarcano.zen.layer.link

import com.hermesmarcano.zen.layer.core.BrainCommand
import com.hermesmarcano.zen.layer.core.ChatFailure
import com.hermesmarcano.zen.layer.core.ChatMessage
import com.hermesmarcano.zen.layer.core.ChatProposal
import com.hermesmarcano.zen.layer.core.ChatRole
import com.hermesmarcano.zen.layer.core.ChatRules
import com.hermesmarcano.zen.layer.core.ChatSnapshot
import com.hermesmarcano.zen.layer.core.ChatStore
import com.hermesmarcano.zen.layer.core.CommandApplier
import com.hermesmarcano.zen.layer.core.WavEncoder
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** Quien graba la voz del chat. Una grabación a la vez; el audio solo vive en memoria y se manda al cerebro, nunca se guarda. */
interface VoiceRecorder {
    enum class Start { STARTED, NO_PERMISSION, MIC_BUSY }

    /** Empieza a grabar. [onLimit] se llama (desde otro hilo) si se llega al tope de duración: quien llama debe terminar la grabación. */
    fun start(onLimit: () -> Unit): Start

    /** Termina y devuelve lo grabado (16 kHz, mono, 16 bits), o `null` si no se estaba grabando. */
    fun stop(): ShortArray?

    /** Descarta la grabación en curso, si la hay. */
    fun cancel()
}

enum class ChatPhase { IDLE, RECORDING, WAITING }

/** NO_BRAIN: falta la dirección o el token. PAUSED: la pausa corta toda la red, también la del chat. */
enum class ChatAvailability { READY, NO_BRAIN, PAUSED }

enum class ChatSend { SENT, INVALID, NOT_READY, NO_BRAIN, PAUSED, BUSY }

enum class ChatStart { STARTED, NO_PERMISSION, MIC_BUSY, NOT_READY, NO_BRAIN, PAUSED, BUSY }

/** Lo que la pantalla necesita para dibujarse. Inmutable: una foto del chat en un instante. */
data class ChatUi(
    val loaded: Boolean,
    val messages: List<ChatMessage>,
    val phase: ChatPhase,
    val availability: ChatAvailability,
    /** Un aviso que la persona debe ver (grabación demasiado corta, no se pudo guardar…). */
    val notice: String?,
    /** Lo que ganó con la última conversación (XP, logros), ya redactado. */
    val celebration: List<String>,
    val recordingSinceMs: Long?,
)

/**
 * La conversación con el cerebro: texto o voz, respuesta en la misma petición, historial cifrado en el teléfono.
 *
 * Reglas que importan:
 *  - **Pausa y sin cerebro = sin red**: no se envía nada ni se graba nada; es el mismo interruptor de corte que el resto de la capa.
 *  - Las órdenes que trae la respuesta (agendar, bloquear, pausar) pasan por la MISMA validación y los MISMOS topes que las de la
 *    cola ([CommandApplier]); aquí solo se admiten esos tres tipos (el adaptador filtra igual: dos barreras). Un evento no se
 *    agenda solo: es una tarjeta con «Agendar» y lo guarda la persona.
 *  - El audio se graba en memoria, se manda y se olvida. La transcripción vuelve en la respuesta.
 *  - Lo que dice el modelo se muestra como texto plano (sin enlaces activos y sin caracteres que disfracen el texto).
 *  - Nueva conversación = otra sesión en el servidor: una respuesta que llega después de reiniciar se descarta.
 */
class ChatController(
    private val api: BrainApi,
    private val brain: () -> BrainConfig?,
    private val paused: () -> Boolean,
    private val store: ChatStore,
    private val recorder: VoiceRecorder,
    private val applyCommand: (BrainCommand) -> CommandApplier.Outcome,
    /** El nombre que ve la persona para un paquete (la etiqueta de su regla en «Límites»). */
    private val appLabel: (String) -> String,
    /** Se llama una vez por intercambio «de verdad»; devuelve lo que se gana (para decírselo). `voice` si fue hablado. */
    private val onTurn: (voice: Boolean) -> List<String>,
    /** La red (puede tardar más de un minuto). */
    private val net: Executor,
    /** El disco: un solo hilo, para que lo guardado conserve el orden. */
    private val io: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private companion object {
        const val UNREADABLE_NOTICE =
            "No se pudo abrir la conversación guardada (el almacén de claves no responde). Vuelve a abrir el chat en un momento, o toca «Nueva» para empezar de cero."
    }

    private class Delivery(
        val cfg: BrainConfig,
        val session: String,
        val epoch: Int,
        val messageId: String,
        val text: String?,
        val wav: ByteArray?,
    ) {
        val voice: Boolean get() = wav != null
    }

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(ChatUi) -> Unit>()

    private var loaded = false
    private var session = newId()
    private var messages: List<ChatMessage> = emptyList()
    private var phase = ChatPhase.IDLE
    private var notice: String? = null
    private var celebration: List<String> = emptyList()
    private var recordingSince: Long? = null
    /** La grabación (por id) que ya se está terminando: un segundo aviso (toque + tope de duración) no la termina dos veces. */
    private var finishingId = 0

    /** Identifica cada grabación: una que se canceló o se reinició no puede ser adoptada por la siguiente. */
    private var recordingId = 0

    /** Cada «nueva conversación» lo sube: lo que llega de una anterior se descarta. */
    private var epoch = 0

    // ---------- Estado ----------

    fun ui(): ChatUi = synchronized(lock) {
        val availability = when {
            brain() == null -> ChatAvailability.NO_BRAIN
            paused() -> ChatAvailability.PAUSED
            else -> ChatAvailability.READY
        }
        ChatUi(loaded, messages, phase, availability, notice, celebration, recordingSince)
    }

    /** `true` si hay una pantalla mirando el chat (si no, lo que haga una respuesta tardía hay que avisarlo con una notificación). */
    val watched: Boolean get() = listeners.isNotEmpty()

    /** Avisa de cada cambio (desde cualquier hilo). Devuelve cómo dejar de escuchar. */
    fun listen(listener: (ChatUi) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun dismissNotice() {
        synchronized(lock) { notice = null }
        changed()
    }

    private fun changed() {
        val snapshot = ui()
        for (l in listeners) {
            try {
                l(snapshot)
            } catch (_: Exception) {
                // Una pantalla que falla al redibujarse no debe cortar el envío ni el guardado.
            }
        }
    }

    private fun afterChange() {
        changed()
        persist()
    }

    // ---------- Carga y guardado ----------

    /**
     * Lee la conversación guardada. Toca el disco y el Keystore: no llamar desde el hilo principal. Si ahora no se puede leer
     * (el Keystore no responde), el chat NO se abre —ni se guarda nada que pise el archivo, que sigue siendo recuperable—: se
     * reintenta cada vez que se llama (al abrir la pantalla), y «Nueva» es la salida explícita para empezar de cero.
     */
    fun ensureLoaded() {
        synchronized(lock) { if (loaded) return }
        var snapshot: ChatSnapshot? = null
        var unreadable = false
        try {
            snapshot = store.load()
        } catch (_: IOException) {
            unreadable = true
        }
        synchronized(lock) {
            if (loaded) return
            if (unreadable) {
                notice = UNREADABLE_NOTICE
            } else {
                if (notice == UNREADABLE_NOTICE) notice = null
                if (snapshot != null) {
                    session = snapshot.session
                    messages = ChatRules.trim(snapshot.messages)
                }
                // Un mensaje de la persona sin respuesta detrás: la app se cerró esperándola.
                val last = messages.lastOrNull()
                if (last != null && last.role == ChatRole.USER && last.failure == null) {
                    messages = messages.dropLast(1) + last.copy(failure = ChatFailure.INTERRUPTED)
                }
                loaded = true
            }
        }
        changed()
    }

    /** El estado se toma DENTRO de la tarea de disco: así lo último que se guarda es siempre lo más reciente (tras un «Nueva», nunca reaparece lo viejo). */
    private fun persist() {
        io.execute {
            val snapshot = synchronized(lock) {
                if (!loaded) return@execute
                ChatSnapshot(session, messages)
            }
            // Una conversación vacía no se guarda: se borra lo que hubiera (así «Nueva» no deja la anterior en el disco).
            val empty = snapshot.messages.isEmpty()
            try {
                if (empty) store.clear() else store.save(snapshot)
            } catch (_: IOException) {
                synchronized(lock) {
                    if (notice == null) notice = if (empty) "No se pudo borrar la conversación guardada." else "No se pudo guardar la conversación en este teléfono."
                }
                changed()
            }
        }
    }

    // ---------- Enviar ----------

    private enum class Gate { OK, NOT_READY, NO_BRAIN, PAUSED, BUSY }

    /** Llamar con [lock] tomado. */
    private fun gate(): Gate = when {
        !loaded -> Gate.NOT_READY
        brain() == null -> Gate.NO_BRAIN
        paused() -> Gate.PAUSED
        phase != ChatPhase.IDLE -> Gate.BUSY
        else -> Gate.OK
    }

    private fun Gate.toSend(): ChatSend = when (this) {
        Gate.OK -> ChatSend.SENT
        Gate.NOT_READY -> ChatSend.NOT_READY
        Gate.NO_BRAIN -> ChatSend.NO_BRAIN
        Gate.PAUSED -> ChatSend.PAUSED
        Gate.BUSY -> ChatSend.BUSY
    }

    /** Envía un mensaje escrito. Si no se acepta, lo escrito no se pierde (la pantalla lo conserva). */
    fun sendText(raw: String): ChatSend {
        val text = ChatRules.cleanInput(raw) ?: return ChatSend.INVALID
        val job = synchronized(lock) {
            val g = gate()
            if (g != Gate.OK) return g.toSend()
            val cfg = brain() ?: return ChatSend.NO_BRAIN
            val message = ChatMessage(newId(), ChatRole.USER, text, clock())
            messages = ChatRules.trim(messages + message)
            beginWaiting()
            Delivery(cfg, session, epoch, message.id, text, null)
        }
        afterChange()
        net.execute { deliver(job) }
        return ChatSend.SENT
    }

    /** Reenvía un mensaje escrito que no obtuvo respuesta (solo si el motivo se puede arreglar esperando). */
    fun retry(messageId: String): ChatSend {
        val job = synchronized(lock) {
            val g = gate()
            if (g != Gate.OK) return g.toSend()
            val message = messages.firstOrNull { it.id == messageId && it.role == ChatRole.USER && !it.voice && it.failure?.retryable == true }
                ?: return ChatSend.INVALID
            val cfg = brain() ?: return ChatSend.NO_BRAIN
            messages = messages.map { if (it.id == messageId) it.copy(failure = null) else it }
            beginWaiting()
            Delivery(cfg, session, epoch, messageId, message.text, null)
        }
        afterChange()
        net.execute { deliver(job) }
        return ChatSend.SENT
    }

    /** Llamar con [lock] tomado. */
    private fun beginWaiting() {
        phase = ChatPhase.WAITING
        notice = null
        celebration = emptyList()
    }

    // ---------- Voz ----------

    fun startRecording(): ChatStart {
        val id = synchronized(lock) {
            when (gate()) {
                Gate.NOT_READY -> return ChatStart.NOT_READY
                Gate.NO_BRAIN -> return ChatStart.NO_BRAIN
                Gate.PAUSED -> return ChatStart.PAUSED
                Gate.BUSY -> return ChatStart.BUSY
                Gate.OK -> Unit
            }
            phase = ChatPhase.RECORDING
            recordingSince = clock()
            notice = null
            celebration = emptyList()
            ++recordingId
        }
        changed()
        val started = try {
            recorder.start { io.execute { finishRecording() } }
        } catch (_: Exception) {
            VoiceRecorder.Start.MIC_BUSY
        }
        if (started == VoiceRecorder.Start.STARTED) {
            // Si mientras se abría el micrófono la conversación se reinició o se canceló, esta grabación ya no es de nadie: se suelta.
            val stillMine = synchronized(lock) { phase == ChatPhase.RECORDING && recordingId == id }
            if (!stillMine) recorder.cancel()
            return ChatStart.STARTED
        }
        synchronized(lock) {
            if (phase == ChatPhase.RECORDING && recordingId == id) {
                phase = ChatPhase.IDLE
                recordingSince = null
                notice = if (started == VoiceRecorder.Start.NO_PERMISSION) "Falta el permiso del micrófono." else "El micrófono está en uso. Prueba de nuevo en un momento."
            }
        }
        changed()
        return if (started == VoiceRecorder.Start.NO_PERMISSION) ChatStart.NO_PERMISSION else ChatStart.MIC_BUSY
    }

    /** Termina la grabación y la manda. Se puede llamar dos veces a la vez (el toque y el tope de duración): solo una cuenta. */
    fun finishRecording() {
        val mine = synchronized(lock) {
            if (phase != ChatPhase.RECORDING || finishingId == recordingId) return
            finishingId = recordingId
            recordingId
        }
        val pcm = try {
            recorder.stop()
        } catch (_: Exception) {
            null
        }
        val wav = if (pcm != null && ChatRules.voiceMs(pcm.size) >= ChatRules.MIN_VOICE_MS) WavEncoder.encode(pcm, ChatRules.SAMPLE_RATE) else null
        val job = synchronized(lock) {
            // Si se reinició mientras tanto, `reset()` ya limpió esto y puede haber otra grabación en marcha: no se toca.
            if (recordingId != mine || phase != ChatPhase.RECORDING) return // se canceló o se reinició mientras tanto
            recordingSince = null
            val cfg = brain()
            when {
                wav == null -> {
                    phase = ChatPhase.IDLE
                    notice = "Muy corto: mantén la grabación un poco más."
                    null
                }
                cfg == null || paused() -> {
                    phase = ChatPhase.IDLE
                    notice = "El chat no está disponible ahora."
                    null
                }
                else -> {
                    val message = ChatMessage(newId(), ChatRole.USER, "", clock(), voice = true)
                    messages = ChatRules.trim(messages + message)
                    beginWaiting()
                    Delivery(cfg, session, epoch, message.id, null, wav)
                }
            }
        }
        afterChange()
        if (job != null) net.execute { deliver(job) }
    }

    fun cancelRecording() {
        val was = synchronized(lock) {
            val recording = phase == ChatPhase.RECORDING
            if (recording) {
                phase = ChatPhase.IDLE
                recordingSince = null
            }
            recording
        }
        if (was) {
            recorder.cancel()
            changed()
        }
    }

    // ---------- Nueva conversación ----------

    /** Vacía la conversación y abre otra sesión (lo que llegue de la anterior se descarta). Devuelve si se estaba grabando. */
    private fun reset(): Boolean = synchronized(lock) {
        val recording = phase == ChatPhase.RECORDING
        epoch++
        recordingId++
        session = newId()
        messages = emptyList()
        phase = ChatPhase.IDLE
        recordingSince = null
        notice = null
        celebration = emptyList()
        loaded = true // empezar de cero también es la salida cuando el historial guardado no se puede leer
        recording
    }

    fun newConversation() {
        if (reset()) recorder.cancel()
        afterChange()
    }

    /**
     * Borra la conversación también del disco (para «Borrar todo» y «Quitar cerebro»), aunque el chat no se haya abierto aún.
     * Devuelve si el archivo quedó borrado: quien lo llama no debe decir «borrado» si no lo está. Toca el disco: no desde el hilo principal.
     */
    fun wipe(): Boolean {
        if (reset()) recorder.cancel()
        val erased = try {
            store.clear()
            true
        } catch (_: IOException) {
            false
        }
        changed()
        return erased
    }

    // ---------- Respuesta ----------

    private fun deliver(d: Delivery) {
        try {
            val result = try {
                api.chat(d.cfg, d.session, d.text, d.wav)
            } catch (_: Exception) {
                ChatResult.Failed(ChatFailure.UNAVAILABLE)
            }
            when (result) {
                is ChatResult.Failed -> fail(d, result.failure)
                is ChatResult.Reply -> succeed(d, result)
            }
        } catch (_: Exception) {
            // Nada inesperado debe dejar el chat esperando una respuesta que ya no va a llegar.
            fail(d, ChatFailure.UNAVAILABLE)
        }
    }

    private fun fail(d: Delivery, failure: ChatFailure) {
        synchronized(lock) {
            if (d.epoch != epoch) return
            messages = messages.map { if (it.id == d.messageId) it.copy(failure = failure) else it }
            phase = ChatPhase.IDLE
        }
        afterChange()
    }

    private fun succeed(d: Delivery, reply: ChatResult.Reply) {
        if (stale(d)) return
        // Primero las órdenes (con sus validaciones y topes), luego se escribe lo que se hizo. Cada paso mira de nuevo que la
        // conversación siga siendo la misma: una orden o un XP de una conversación ya descartada no deben aplicarse.
        val extras = ArrayList<ChatMessage>()
        for (command in reply.commands.filter(ChatRules::isChatCommand).take(ChatRules.MAX_COMMANDS)) {
            if (stale(d)) return
            act(command)?.let { extras += it }
        }
        val said = reply.transcript ?: d.text.orEmpty()
        if (stale(d)) return
        val earned = if (ChatRules.qualifies(said)) {
            try {
                onTurn(d.voice)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val answer = ChatMessage(newId(), ChatRole.BRAIN, reply.reply, clock())
        synchronized(lock) {
            if (d.epoch != epoch) return
            val withTranscript = messages.map { if (it.id == d.messageId && reply.transcript != null) it.copy(text = reply.transcript) else it }
            messages = ChatRules.trim(withTranscript + answer + extras)
            celebration = earned
            phase = ChatPhase.IDLE
        }
        afterChange()
    }

    private fun stale(d: Delivery): Boolean = synchronized(lock) { d.epoch != epoch }

    /** Aplica una orden del cerebro y devuelve lo que se le muestra a la persona (`null`: una repetida, nada que decir). */
    private fun act(command: BrainCommand): ChatMessage? {
        val outcome = try {
            applyCommand(command)
        } catch (_: Exception) {
            CommandApplier.Outcome.REJECTED
        }
        if (outcome == CommandApplier.Outcome.DUPLICATE) return null
        val ok = outcome == CommandApplier.Outcome.APPLIED
        val now = clock()
        return when (command) {
            is BrainCommand.CalendarEvent ->
                if (ok) {
                    ChatMessage(newId(), ChatRole.BRAIN, command.title, now, proposal = ChatProposal(command.id, command.title, command.startMs, command.endMs))
                } else {
                    note("No pude proponer ese evento (fecha o título no válidos, o ya hay demasiadas propuestas esta hora).", now)
                }
            is BrainCommand.Block ->
                if (ok) {
                    note("Bloqueé ${appLabel(command.pkg)} durante ${duration(command.minutes)}.", now)
                } else {
                    note("No bloqueé ${appLabel(command.pkg)}: no cumple las reglas de seguridad (la app debe tener una regla que bloquee en «Límites», y hay un tope por hora).", now)
                }
            is BrainCommand.Pause ->
                if (ok) note("Pausé la capa durante ${duration(command.minutes)}.", now) else note("No pude pausar la capa.", now)
            else -> null
        }
    }

    private fun note(text: String, now: Long) = ChatMessage(newId(), ChatRole.NOTE, text, now)

    private fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}
