package com.hermesmarcano.zen.layer.core

import java.util.UUID

/**
 * Por aquí pasa todo lo que la capa observa antes de guardarse: se comprueba que esté permitido, se
 * descarta lo repetido, se le quitan los secretos, se guarda en la línea de tiempo local y, si hay
 * cerebro configurado, se encola para enviarlo. Los adaptadores de Android (accesibilidad, notificaciones,
 * micrófono) solo extraen datos y llaman aquí; las reglas viven en este archivo y se prueban en JVM.
 */
class LayerPipeline(
    private val state: LayerStateStore,
    private val timeline: TimelineStore,
    private val outbox: OutboxStore,
    private val ownPackage: String,
    private val encode: (LayerEvent) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val screenDeduper: TextDeduper = TextDeduper(),
    private val notificationDeduper: TextDeduper = TextDeduper(minIntervalMs = 0L),
    private val onEnqueued: () -> Unit = {},
) {
    data class Outcome(
        val kind: Kind,
        val event: LayerEvent? = null,
        val decision: CaptureDecision? = null,
        /** El evento quedó en la cola de envío. Para audio: si es falso, el archivo ya no hace falta. */
        val queued: Boolean = false,
    ) {
        enum class Kind { STORED, GATED, DUPLICATE, EMPTY, FAILED }
    }

    fun decision(source: Source, pkg: String): CaptureDecision =
        CapturePolicy.decide(state.current.capture, source, pkg, ownPackage, clock())

    /** Barato: permite al servicio de accesibilidad no recorrer el árbol de la pantalla si de todas formas se descartaría. */
    fun screenReady(pkg: String): Boolean = decision(Source.SCREEN, pkg).allowed && screenDeduper.ready(pkg, clock())

    fun onScreen(pkg: String, lines: List<String>, force: Boolean = false): Outcome {
        val gate = decision(Source.SCREEN, pkg)
        if (!gate.allowed) return Outcome(Outcome.Kind.GATED, decision = gate)
        val now = clock()
        val fresh = screenDeduper.fresh(pkg, lines, now, force)
        if (fresh.isEmpty()) return Outcome(Outcome.Kind.DUPLICATE)
        val text = LayerEvent.clamp(Redactor.redact(fresh.joinToString("\n")), LayerEvent.MAX_TEXT)
        if (text.isBlank()) return Outcome(Outcome.Kind.EMPTY)
        val outcome = store(LayerEvent(newId(), LayerEvent.Kind.SCREEN, pkg, "", text, now))
        // Si no se pudo guardar, esas líneas no deben quedar como "ya vistas": se recogerán en el siguiente evento.
        if (outcome.kind == Outcome.Kind.FAILED) screenDeduper.forget(pkg)
        return outcome
    }

    fun onNotification(pkg: String, title: String, text: String, conversationKey: String? = null): Outcome {
        val gate = decision(Source.NOTIFICATIONS, pkg)
        if (!gate.allowed) return Outcome(Outcome.Kind.GATED, decision = gate)
        val head = title.trim()
        val body = text.trim()
        if (head.isEmpty() && body.isEmpty()) return Outcome(Outcome.Kind.EMPTY)
        val now = clock()
        // Una notificación que se vuelve a publicar con lo mismo (Android lo hace al actualizarla) no es noticia.
        if (notificationDeduper.fresh(pkg, listOf("$head: $body"), now).isEmpty()) return Outcome(Outcome.Kind.DUPLICATE)
        val (safeTitle, safeBody) = Redactor.redactPair(head, body)
        val event = LayerEvent(
            id = newId(),
            kind = LayerEvent.Kind.NOTIFICATION,
            pkg = pkg,
            title = LayerEvent.clamp(safeTitle, LayerEvent.MAX_TITLE),
            text = LayerEvent.clamp(safeBody, LayerEvent.MAX_TEXT),
            atMs = now,
            extra = conversationKey?.let { mapOf("conv" to it) } ?: emptyMap(),
        )
        return store(event)
    }

    /** Hechos de la propia capa (bloqueó una app, el usuario soltó un bloqueo, envió una respuesta). No pasan por el filtro de fuentes. */
    fun onSystem(kind: LayerEvent.Kind, title: String, text: String, pkg: String = ""): Outcome {
        val (safeTitle, safeText) = Redactor.redactPair(title.trim(), text.trim())
        val event = LayerEvent(
            id = newId(),
            kind = kind,
            pkg = pkg,
            title = LayerEvent.clamp(safeTitle, LayerEvent.MAX_TITLE),
            text = LayerEvent.clamp(safeText, LayerEvent.MAX_TEXT),
            atMs = clock(),
        )
        return store(event)
    }

    /** Un tramo de voz ya guardado como WAV. Sin cerebro no hay quien lo transcriba: [Outcome.queued] avisa de que se puede borrar. */
    fun onAudioSegment(fileName: String, durationMs: Long, startedAtMs: Long): Outcome {
        val gate = decision(Source.AUDIO, "")
        if (!gate.allowed) return Outcome(Outcome.Kind.GATED, decision = gate)
        val event = LayerEvent(
            id = newId(),
            kind = LayerEvent.Kind.AUDIO,
            pkg = "",
            title = "",
            text = "[audio ${durationMs / 1000} s]",
            atMs = startedAtMs,
            extra = mapOf("file" to fileName, "durationMs" to durationMs.toString()),
        )
        try {
            timeline.insert(event)
        } catch (_: Exception) {
            return Outcome(Outcome.Kind.FAILED)
        }
        var queued = false
        if (state.current.brainConfigured) {
            queued = tryEnqueue(OutboxItem(event.id, OutboxItem.Kind.AUDIO, fileName, startedAtMs))
        }
        return Outcome(Outcome.Kind.STORED, event, queued = queued)
    }

    private fun store(event: LayerEvent): Outcome {
        try {
            timeline.insert(event)
        } catch (_: Exception) {
            return Outcome(Outcome.Kind.FAILED)
        }
        val queued = state.current.brainConfigured &&
            tryEnqueue(OutboxItem(event.id, OutboxItem.Kind.EVENT, encode(event), event.atMs))
        return Outcome(Outcome.Kind.STORED, event, queued = queued)
    }

    private fun tryEnqueue(item: OutboxItem): Boolean = try {
        outbox.enqueue(item)
        onEnqueued()
        true
    } catch (_: Exception) {
        false
    }
}
