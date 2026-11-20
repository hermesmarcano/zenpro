package com.hermesmarcano.zen.layer.link

import com.hermesmarcano.zen.layer.core.BrainCommand
import com.hermesmarcano.zen.layer.core.ChatFailure
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

        // 3b. La propuesta de calendario del adaptador (hora local → ms) la lee la app y la acepta su política.
        val proposal = commands.filterIsInstance<BrainCommand.CalendarEvent>().single()
        assertEquals("Cena con Marta", proposal.title)
        assertEquals("el origen lo fija el servidor: quién lo escribió", "Marta", proposal.from)
        assertEquals(90 * 60_000L, proposal.endMs - proposal.startMs)
        assertTrue("empieza en el futuro", proposal.startMs > System.currentTimeMillis())
        assertEquals(CommandPolicy.Verdict.ACCEPT, CommandPolicy.check(proposal, "com.hermesmarcano.zen.xiaomi", emptySet()))

        // 4. Con el cursor devuelto no vuelve lo ya entregado.
        val again = client.fetchCommands(cfg, cursor)
        assertEquals(ApiResult.Ok, again.result)
        assertTrue(again.commands.isEmpty())

        // 5. Un token equivocado es «token rechazado», no un fallo de red.
        assertEquals(ApiResult.Unauthorized, client.postEvents(cfg.copy(token = "x".repeat(43)), listOf(item)))
    }

    @Test fun theAppClientChatsWithTheRealAdapter() {
        assumeTrue("sin adaptador en marcha: se omite", !url.isNullOrBlank() && !token.isNullOrBlank())
        val cfg = BrainConfig(url!!, token!!, "dev-live-test")
        val client = BrainClient("ZEN-Layer/live-test")
        val session = java.util.UUID.randomUUID().toString()

        // 1. Texto: la respuesta llega en la misma petición, con las órdenes que el chat admite (agendar y pausar), no las de otro tipo.
        val reply = client.chat(cfg, session, "agéndame la cena con Marta mañana a las 9", null)
        reply as ChatResult.Reply
        assertEquals("Te propongo la cena.", reply.reply)
        assertEquals("el borrador no es del chat y el adaptador lo quita", 2, reply.commands.size)
        assertTrue(reply.commands[0] is BrainCommand.CalendarEvent)
        assertTrue(reply.commands[1] is BrainCommand.Pause)
        val proposal = reply.commands.filterIsInstance<BrainCommand.CalendarEvent>().single()
        assertEquals("el origen lo fija el servidor", "chat", proposal.from)
        assertEquals(90 * 60_000L, proposal.endMs - proposal.startMs)
        assertEquals(CommandPolicy.Verdict.ACCEPT, CommandPolicy.check(proposal, "com.hermesmarcano.zen.xiaomi", emptySet()))
        assertTrue("las órdenes del chat llevan id propio", reply.commands.all { it.id.isNotBlank() })

        // 2. Voz: este adaptador de pruebas no tiene la transcripción activada y lo dice con su motivo.
        val wav = WavEncoder.encode(ShortArray(16_000) { (it % 100).toShort() }, 16_000)
        assertEquals(ChatFailure.STT_OFF, (client.chat(cfg, session, null, wav) as ChatResult.Failed).failure)

        // 3. Un token equivocado es «token rechazado».
        assertEquals(ChatFailure.UNAUTHORIZED, (client.chat(cfg.copy(token = "x".repeat(43)), session, "hola", null) as ChatResult.Failed).failure)
    }
}
