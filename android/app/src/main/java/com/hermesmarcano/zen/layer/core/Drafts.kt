package com.hermesmarcano.zen.layer.core

import java.io.IOException
import java.util.UUID

/** A quién se le puede responder desde una notificación. */
data class ReplyTarget(val pkg: String, val contact: String)

enum class ReplySendResult { SENT, GONE, FAILED }

/** Quien sabe responder a una conversación (en Android: la acción de respuesta de su notificación). */
interface ReplySender {
    fun target(conversationKey: String): ReplyTarget?
    fun send(conversationKey: String, text: String): ReplySendResult
}

/** Lo que la capa le muestra al usuario. La implementación de Android lo traduce a notificaciones. */
interface Notifier {
    fun notice(title: String, text: String)
    fun draftReady(draft: Draft)
    fun nudge(nudge: LimitDecision.Nudge)
    fun blocked(entry: BlockEntry)
    fun needsAttention(title: String, text: String)
}

/**
 * Borradores de respuesta. El cerebro propone ([propose]) pero SOLO el usuario envía ([approve]): no hay
 * otro camino hacia el envío, y eso se prueba. Enviar es "como mucho una vez": el borrador se marca antes
 * de mandarlo, así un doble toque o un reinicio a medias nunca duplican un mensaje a otra persona.
 */
class DraftController(
    private val state: LayerStateStore,
    private val sender: ReplySender,
    private val pipeline: LayerPipeline,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    enum class Result { SENT, EXPIRED, GONE, FAILED, NOT_FOUND }

    /** Crea un borrador pendiente. `null` si esa conversación ya no está disponible (la notificación se descartó). */
    fun propose(conversationKey: String, text: String): Draft? {
        val target = sender.target(conversationKey) ?: return null
        val now = clock()
        val draft = Draft(newId(), conversationKey, target.pkg, target.contact, text, now, now + DraftBoard.TTL_MS)
        return try {
            state.update { it.copy(drafts = DraftBoard.add(it.drafts, draft, now)) }
                .drafts.firstOrNull { it.id == draft.id && it.status == DraftStatus.PENDING }
        } catch (_: IOException) {
            null
        }
    }

    @Synchronized
    fun approve(id: String, editedText: String? = null): Result {
        val now = clock()
        val draft = state.current.drafts.firstOrNull { it.id == id && it.status == DraftStatus.PENDING } ?: return Result.NOT_FOUND
        if (draft.expiresAtMs <= now) {
            resolve(id, DraftStatus.EXPIRED)
            return Result.EXPIRED
        }
        val text = LayerEvent.clamp(editedText?.trim()?.takeIf { it.isNotEmpty() } ?: draft.text, DraftBoard.MAX_TEXT)
        // Se reclama antes de enviar: "como mucho una vez".
        if (!resolve(id, DraftStatus.SENT)) return Result.FAILED
        return when (sender.send(draft.conversationKey, text)) {
            ReplySendResult.SENT -> {
                pipeline.onSystem(LayerEvent.Kind.SYSTEM, "Respuesta enviada a ${draft.contact}", text, draft.pkg)
                Result.SENT
            }
            ReplySendResult.GONE -> {
                resolve(id, DraftStatus.FAILED, force = true)
                Result.GONE
            }
            ReplySendResult.FAILED -> {
                resolve(id, DraftStatus.FAILED, force = true)
                Result.FAILED
            }
        }
    }

    fun discard(id: String) {
        resolve(id, DraftStatus.DISCARDED)
    }

    /** [force] permite pasar de SENT (el "reclamo") a FAILED cuando el envío no llegó a hacerse. */
    private fun resolve(id: String, status: DraftStatus, force: Boolean = false): Boolean = try {
        state.update { s ->
            s.copy(
                drafts = s.drafts.map {
                    val open = it.status == DraftStatus.PENDING || (force && it.status == DraftStatus.SENT)
                    if (it.id == id && open) it.copy(status = status) else it
                },
            )
        }
        true
    } catch (_: IOException) {
        false
    }
}

/**
 * Ejecuta las órdenes del cerebro, tras validarlas. Una orden se anota como vista ANTES de ejecutarla
 * (como mucho una vez) y las inválidas también, para que no vuelvan en cada sincronización.
 */
class CommandApplier(
    private val state: LayerStateStore,
    private val blocks: BlockActions,
    private val drafts: DraftController,
    private val notifier: Notifier,
    private val ownPackage: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { APPLIED, DUPLICATE, REJECTED }

    // Un cerebro engañado no debe poder inundar de avisos ni bloquear apps en ráfaga.
    private val notices = RateWindow(NOTICES_PER_HOUR, HOUR_MS)
    private val brainBlocks = RateWindow(BLOCKS_PER_HOUR, HOUR_MS)

    fun apply(command: BrainCommand): Outcome {
        val blockable = state.current.limits.mapTo(HashSet()) { it.pkg }
        val verdict = CommandPolicy.check(command, ownPackage, state.current.seenCommandIds, blockable)
        if (verdict == CommandPolicy.Verdict.DUPLICATE) return Outcome.DUPLICATE
        if (command.id.isNotBlank()) {
            try {
                state.update { it.copy(seenCommandIds = CommandPolicy.remember(it.seenCommandIds, command.id)) }
            } catch (_: IOException) {
                // Sin poder anotarla, es más seguro no ejecutarla que arriesgarse a repetirla.
                return Outcome.REJECTED
            }
        }
        if (verdict != CommandPolicy.Verdict.ACCEPT) return Outcome.REJECTED
        val now = clock()
        if (command is BrainCommand.Notify && !notices.tryAcquire(now)) return Outcome.REJECTED
        if (command is BrainCommand.Block && !brainBlocks.tryAcquire(now)) return Outcome.REJECTED
        // Una escritura fallida dentro de una orden no debe tumbar la sincronización entera.
        try {
            when (command) {
                is BrainCommand.DraftReply -> {
                    val draft = drafts.propose(command.conversationKey, command.text)
                    if (draft != null) {
                        notifier.draftReady(draft)
                    } else {
                        notifier.notice("Respuesta no disponible", "El cerebro propuso una respuesta, pero esa conversación ya no está en las notificaciones.")
                    }
                }
                is BrainCommand.Block -> {
                    val until = now + command.minutes * 60_000L
                    if (blocks.blockUntil(command.pkg, until, BlockReason.BRAIN, now)) {
                        notifier.blocked(BlockEntry(command.pkg, until, BlockReason.BRAIN))
                    }
                }
                is BrainCommand.Notify -> notifier.notice(command.title.ifBlank { "ZEN" }, command.text)
                is BrainCommand.Pause -> {
                    val until = now + command.minutes * 60_000L
                    state.update { it.copy(capture = it.capture.copy(pausedUntilMs = maxOf(it.capture.pausedUntilMs, until))) }
                }
            }
        } catch (_: IOException) {
            return Outcome.REJECTED
        }
        return Outcome.APPLIED
    }

    private companion object {
        const val NOTICES_PER_HOUR = 6
        const val BLOCKS_PER_HOUR = 3
        const val HOUR_MS = 60 * 60_000L
    }
}
