package com.hermesmarcano.zen.layer.link

import com.hermesmarcano.zen.layer.core.BrainCommand
import com.hermesmarcano.zen.layer.core.CommandPolicy
import com.hermesmarcano.zen.layer.core.LayerEvent
import com.hermesmarcano.zen.layer.core.OutboxItem
import com.hermesmarcano.zen.layer.core.WavEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Prueba de integración REAL: el cliente de la app (BrainClient + EventJson) contra el adaptador zen-brain de verdad.
 * No corre en el CI normal: solo si se define `ZEN_BRAIN_LIVE_URL` y `ZEN_BRAIN_LIVE_TOKEN` (ver brain/README.md).
 */
class BrainLiveTest {
    private val url = System.getenv("ZEN_BRAIN_LIVE_URL")
    private val token = System.getenv("ZEN_BRAIN_LIVE_TOKEN")
    private val conv = "com.whatsapp|0|marta-conv-key"

    @Test fun theAppClientTalksToTheRealAdapter() {
        assumeTrue("sin adaptador en marcha: se omite", !url.isNullOrBlank() && !token.isNullOrBlank())
        val cfg = BrainConfig(url!!, token!!, "dev-live-test")
        val client = BrainClient("ZEN-Layer/live-test")

        // 1. Un evento como el que manda la app (notificación contestable), con el JSON REAL de EventJson.
        val event = LayerEvent("ev-live-1", LayerEvent.Kind.NOTIFICATION, "com.whatsapp", "Marta", "¿cenamos hoy?", System.currentTimeMillis(), mapOf("conv" to conv))
        val item = OutboxItem(event.id, OutboxItem.Kind.EVENT, EventJson.encode(event), event.atMs)
        assertEquals(ApiResult.Ok, client.postEvents(cfg, listOf(item)))
        // Reenviar el mismo lote no debe fallar ni duplicar (el teléfono reintenta tras un corte de red).
        assertEquals(ApiResult.Ok, client.postEvents(cfg, listOf(item)))

        // 2. Un tramo de voz de 1 s, con el WAV REAL de WavEncoder y las cabeceras reales.
        val wav = WavEncoder.encode(ShortArray(16_000) { (it % 100).toShort() }, 16_000)
        assertEquals(ApiResult.Ok, client.postAudio(cfg, OutboxItem("seg-live-1", OutboxItem.Kind.AUDIO, "seg.wav", event.atMs), wav))

        // 3. Las órdenes que decidió el adaptador, leídas con el parser REAL de la app y validadas con su política REAL.
        var commands: List<BrainCommand> = emptyList()
        var cursor = ""
        val end = System.currentTimeMillis() + 15_000
        while (commands.isEmpty() && System.currentTimeMillis() < end) {
            val result = client.fetchCommands(cfg, cursor)
            assertEquals(ApiResult.Ok, result.result)
            commands = result.commands
            cursor = result.cursor
            if (commands.isEmpty()) Thread.sleep(300)
        }
        assertTrue("el adaptador debió devolver el borrador", commands.isNotEmpty())
        val draft = commands.filterIsInstance<BrainCommand.DraftReply>().single()
        assertEquals("el destino es la conversación del propio evento", conv, draft.conversationKey)
        assertEquals("Dale, a las 9", draft.text)
        assertEquals(CommandPolicy.Verdict.ACCEPT, CommandPolicy.check(draft, "com.hermesmarcano.zen.xiaomi", emptySet()))
        assertTrue("el cursor es opaco y no vacío", cursor.isNotBlank())

        // 4. Con el cursor devuelto no vuelve lo ya entregado.
        val again = client.fetchCommands(cfg, cursor)
        assertEquals(ApiResult.Ok, again.result)
        assertTrue(again.commands.isEmpty())

        // 5. Un token equivocado es «token rechazado», no un fallo de red.
        assertEquals(ApiResult.Unauthorized, client.postEvents(cfg.copy(token = "x".repeat(43)), listOf(item)))
    }
}
