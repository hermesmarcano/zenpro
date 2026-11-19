package com.hermesmarcano.zen.layer.link

import com.hermesmarcano.zen.layer.core.BrainUrl
import com.hermesmarcano.zen.layer.core.CommandApplier
import com.hermesmarcano.zen.layer.core.LayerStateStore
import com.hermesmarcano.zen.layer.core.OutboxItem
import com.hermesmarcano.zen.layer.core.OutboxPolicy
import com.hermesmarcano.zen.layer.core.OutboxStore
import com.hermesmarcano.zen.layer.data.AudioFiles
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Una pasada de sincronización con el cerebro: manda los eventos y el audio pendientes, recoge las órdenes y las
 * aplica. Corre en el trabajo periódico y cuando hay algo nuevo y red. No hace nada si no hay cerebro configurado.
 *
 * Reglas que importan:
 *  - Sin red (o 5xx) NO se pierde nada: se pospone con espera creciente y los elementos se conservan hasta 3 días.
 *  - Si el servidor rechaza un lote (4xx), se reenvía uno a uno para aislar el elemento malo; con 5 rechazos de un
 *    mismo elemento se descarta (y se borra su audio).
 *  - Un token rechazado (401/403) detiene la pasada y queda anotado para que el usuario lo vea.
 */
class SyncEngine(
    private val state: LayerStateStore,
    private val outbox: OutboxStore,
    private val audio: AudioFiles,
    private val api: BrainApi,
    private val commands: CommandApplier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = { Random.nextDouble() },
) {
    data class Report(
        val skipped: Boolean = false,
        val eventsSent: Int = 0,
        val audioSent: Int = 0,
        val commandsApplied: Int = 0,
        val error: String? = null,
    )

    private val running = AtomicBoolean(false)

    fun run(): Report {
        val s = state.current
        val base = (BrainUrl.validate(s.brainUrl) as? BrainUrl.Result.Ok)?.baseUrl
        if (base == null || s.brainToken.isBlank()) return Report(skipped = true)
        // Dos pasadas a la vez (trabajo periódico + envío inmediato) enviarían duplicados.
        if (!running.compareAndSet(false, true)) return Report(skipped = true)
        try {
            val cfg = BrainConfig(base, s.brainToken, s.deviceId.ifBlank { "unknown" })
            val pass = Pass(cfg)
            pass.events()
            pass.audio()
            pass.pullCommands(s.commandCursor)
            pass.finish()
            return Report(false, pass.eventsSent, pass.audioSent, pass.commandsApplied, pass.error)
        } finally {
            running.set(false)
        }
    }

    private inner class Pass(val cfg: BrainConfig) {
        var eventsSent = 0
        var audioSent = 0
        var commandsApplied = 0
        var error: String? = null
        var reached = false
        var unauthorized = false

        fun events() {
            repeat(MAX_BATCHES) {
                if (unauthorized) return
                val batch = outbox.due(OutboxItem.Kind.EVENT, clock(), OutboxPolicy.EVENT_BATCH)
                if (batch.isEmpty()) return
                when (val r = api.postEvents(cfg, batch)) {
                    ApiResult.Ok -> {
                        reached = true
                        outbox.remove(batch.map { it.id })
                        eventsSent += batch.size
                    }
                    ApiResult.Unauthorized -> return unauthorizedStop()
                    is ApiResult.Transient -> {
                        error = r.message
                        dropFiles(outbox.markFailed(batch, clock(), random(), rejected = false))
                        return
                    }
                    is ApiResult.Rejected -> {
                        reached = true
                        error = r.message
                        if (batch.size == 1) dropFiles(outbox.markFailed(batch, clock(), random(), rejected = true)) else isolate(batch)
                    }
                }
            }
        }

        /** Un lote rechazado: se reenvía de uno en uno para saber cuál es el malo. */
        private fun isolate(batch: List<OutboxItem>) {
            for (item in batch) {
                when (val r = api.postEvents(cfg, listOf(item))) {
                    ApiResult.Ok -> {
                        outbox.remove(listOf(item.id))
                        eventsSent++
                    }
                    ApiResult.Unauthorized -> return unauthorizedStop()
                    is ApiResult.Transient -> {
                        error = r.message
                        outbox.markFailed(listOf(item), clock(), random(), rejected = false)
                        return
                    }
                    is ApiResult.Rejected -> dropFiles(outbox.markFailed(listOf(item), clock(), random(), rejected = true))
                }
            }
        }

        fun audio() {
            repeat(MAX_AUDIO) {
                if (unauthorized) return
                val item = outbox.due(OutboxItem.Kind.AUDIO, clock(), OutboxPolicy.AUDIO_BATCH).firstOrNull() ?: return
                val file = audio.file(item.payload)
                if (file == null) {
                    outbox.remove(listOf(item.id)) // el archivo ya no existe: no hay nada que enviar
                    return@repeat
                }
                when (val r = api.postAudio(cfg, item, file)) {
                    ApiResult.Ok -> {
                        reached = true
                        outbox.remove(listOf(item.id))
                        audio.delete(item.payload)
                        audioSent++
                    }
                    ApiResult.Unauthorized -> return unauthorizedStop()
                    is ApiResult.Transient -> {
                        error = error ?: r.message
                        outbox.markFailed(listOf(item), clock(), random(), rejected = false)
                        return
                    }
                    is ApiResult.Rejected -> {
                        reached = true
                        error = error ?: r.message
                        dropFiles(outbox.markFailed(listOf(item), clock(), random(), rejected = true))
                    }
                }
            }
        }

        fun pullCommands(cursor: String) {
            if (unauthorized) return
            val result = api.fetchCommands(cfg, cursor)
            when (val r = result.result) {
                ApiResult.Ok -> {
                    reached = true
                    for (command in result.commands) {
                        if (commands.apply(command) == CommandApplier.Outcome.APPLIED) commandsApplied++
                    }
                    if (result.cursor.isNotBlank() && result.cursor != cursor) {
                        try {
                            state.update { it.copy(commandCursor = result.cursor) }
                        } catch (_: IOException) {
                        }
                    }
                }
                ApiResult.Unauthorized -> unauthorizedStop()
                is ApiResult.Transient -> error = error ?: r.message
                is ApiResult.Rejected -> {
                    // Un cerebro sin órdenes (404) es válido: solo recibe.
                    reached = true
                }
            }
        }

        fun finish() {
            val now = clock()
            val message = if (unauthorized) "El cerebro rechazó el token (401/403)." else error.orEmpty()
            try {
                state.update {
                    it.copy(
                        lastSyncOkAtMs = if (reached && !unauthorized) now else it.lastSyncOkAtMs,
                        lastSyncError = message,
                    )
                }
            } catch (_: IOException) {
            }
            if (unauthorized) error = message
        }

        private fun unauthorizedStop() {
            unauthorized = true
        }

        private fun dropFiles(dropped: List<OutboxItem>) {
            dropped.filter { it.kind == OutboxItem.Kind.AUDIO }.forEach { audio.delete(it.payload) }
        }
    }

    private companion object {
        const val MAX_BATCHES = 10
        const val MAX_AUDIO = 5
    }
}
