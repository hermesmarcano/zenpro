package com.hermesmarcano.zen.layer.link

import com.hermesmarcano.zen.layer.core.BrainCommand
import com.hermesmarcano.zen.layer.core.LayerEvent
import com.hermesmarcano.zen.layer.core.OutboxItem
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventJsonTest {
    private val event = LayerEvent("e1", LayerEvent.Kind.NOTIFICATION, "com.whatsapp", "Marta", "¿cenamos?", 1_234L, mapOf("conv" to "wa|marta"))

    @Test fun encodesTheDocumentedFields() {
        val o = JSONObject(EventJson.encode(event))
        assertEquals("e1", o.getString("id"))
        assertEquals("notification", o.getString("kind"))
        assertEquals("com.whatsapp", o.getString("package"))
        assertEquals("Marta", o.getString("title"))
        assertEquals("¿cenamos?", o.getString("text"))
        assertEquals(1_234L, o.getLong("at"))
        assertEquals("wa|marta", o.getJSONObject("extra").getString("conv"))
    }

    @Test fun quotesAndNewlinesSurviveTheTrip() {
        val tricky = event.copy(text = "dijo \"hola\"\ny\\adiós ")
        assertEquals(tricky.text, JSONObject(EventJson.encode(tricky)).getString("text"))
    }

    @Test fun batchBodyIsValidJsonWithTheDeviceAndEveryEvent() {
        val body = JSONObject(EventJson.batchBody("dev \"1\"", listOf(EventJson.encode(event), EventJson.encode(event.copy(id = "e2")))))
        assertEquals("dev \"1\"", body.getString("device"))
        assertEquals(listOf("e1", "e2"), (0 until 2).map { body.getJSONArray("events").getJSONObject(it).getString("id") })
        assertEquals(0, JSONObject(EventJson.batchBody("d", emptyList())).getJSONArray("events").length())
    }

    @Test fun parsesAllFourCommandTypes() {
        val (commands, cursor) = EventJson.parseCommands(
            """{"cursor":"c9","commands":[
                {"id":"1","type":"draft_reply","conversation":"wa|marta","text":"ok"},
                {"id":"2","type":"block","package":"com.tiktok","minutes":60},
                {"id":"3","type":"notify","title":"T","text":"X"},
                {"id":"4","type":"pause","minutes":15}]}""",
        )
        assertEquals("c9", cursor)
        assertEquals(
            listOf(
                BrainCommand.DraftReply("1", "wa|marta", "ok"), BrainCommand.Block("2", "com.tiktok", 60),
                BrainCommand.Notify("3", "T", "X"), BrainCommand.Pause("4", 15),
            ),
            commands,
        )
    }

    @Test fun unknownTypesAreSkippedAndMissingFieldsStayEmpty() {
        val (commands, _) = EventJson.parseCommands(
            """{"commands":[{"id":"1","type":"launch_missiles"},{"id":"2","type":"block"},"basura",{"type":"pause"}]}""",
        )
        assertEquals(listOf<BrainCommand>(BrainCommand.Block("2", "", 0), BrainCommand.Pause("", 0)), commands)
    }

    @Test fun emptyOrCommandlessBodiesGiveNothing() {
        assertEquals(emptyList<BrainCommand>() to "", EventJson.parseCommands("{}"))
        assertEquals(emptyList<BrainCommand>() to "x", EventJson.parseCommands("{\"cursor\":\"x\"}"))
    }

    @Test fun malformedJsonThrows() {
        try {
            EventJson.parseCommands("no es json")
            fail("debería lanzar")
        } catch (_: JSONException) {
        }
    }
}

private class Request(val method: String, val uri: String, val headers: Map<String, String>, val body: ByteArray)

/** [writer] permite una respuesta en flujo (sin Content-Length) para probar respuestas enormes. */
private class Response(
    val code: Int,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
    val delayMs: Long = 0,
    val writer: ((OutputStream) -> Unit)? = null,
)

/**
 * Servidor HTTP mínimo hecho con `ServerSocket`: el classpath de pruebas de Android no incluye
 * `com.sun.net.httpserver`, y así no hace falta ninguna dependencia nueva.
 */
private class MiniServer {
    val seen = CopyOnWriteArrayList<Request>()
    private val handlers = ConcurrentHashMap<String, (Request) -> Response>()
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        pool.execute {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                pool.execute { handle(client) }
            }
        }
    }

    fun on(path: String, handler: (Request) -> Response) {
        handlers[path] = handler
    }

    fun stop() {
        socket.close()
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        client.use {
            try {
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) return
                    read += n
                }
                val request = Request(parts[0], parts[1], headers, body)
                seen += request
                val response = (handlers[parts[1].substringBefore('?')] ?: { Response(404) })(request)
                if (response.delayMs > 0) Thread.sleep(response.delayMs)
                val out = client.getOutputStream()
                val head = StringBuilder("HTTP/1.1 ${response.code} X\r\nConnection: close\r\n")
                response.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
                if (response.writer == null) head.append("Content-Length: ${response.body.size}\r\n")
                head.append("\r\n")
                out.write(head.toString().toByteArray())
                if (response.writer != null) response.writer.invoke(out) else out.write(response.body)
                out.flush()
            } catch (_: Exception) {
                // El cliente pudo cortar a mitad (pruebas de tiempo de espera / respuesta enorme).
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }
}

class BrainClientTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MiniServer
    private val client = BrainClient("ZEN-Layer-Test", connectTimeoutMs = 2_000, readTimeoutMs = 600)
    private fun cfg(url: String = server.baseUrl, token: String = "tok") = BrainConfig(url, token, "dev-1")
    private fun item(id: String = "e1") = OutboxItem(id, OutboxItem.Kind.EVENT, EventJson.encode(LayerEvent(id, LayerEvent.Kind.SCREEN, "a.b", "", "hola", 1L)), 1L)

    @Before fun start() { server = MiniServer() }
    @After fun stop() = server.stop()

    @Test fun postsEventsWithTheTokenInTheHeaderOnly() {
        server.on("/v1/events") { Response(200) }
        assertEquals(ApiResult.Ok, client.postEvents(cfg(), listOf(item("a"), item("b"))))
        val req = server.seen.single()
        assertEquals("POST", req.method)
        assertEquals("/v1/events", req.uri)
        assertEquals("Bearer tok", req.headers["authorization"])
        assertEquals("application/json", req.headers["content-type"])
        assertEquals("ZEN-Layer-Test", req.headers["user-agent"])
        assertTrue("el token nunca va en la URL", "tok" !in req.uri)
        val body = JSONObject(String(req.body))
        assertEquals("dev-1", body.getString("device"))
        assertEquals(2, body.getJSONArray("events").length())
    }

    @Test fun statusCodesMapToTheRightKindOfFailure() {
        val expected = mapOf(
            200 to ApiResult.Ok, 204 to ApiResult.Ok,
            401 to ApiResult.Unauthorized, 403 to ApiResult.Unauthorized,
            408 to "T", 429 to "T", 500 to "T", 502 to "T", 503 to "T",
            400 to "R", 404 to "R", 413 to "R", 422 to "R",
        )
        for ((code, want) in expected) {
            server.on("/v1/events") { Response(code) }
            val got = client.postEvents(cfg(), listOf(item()))
            when (want) {
                "T" -> assertTrue("HTTP $code → $got", got is ApiResult.Transient)
                "R" -> assertTrue("HTTP $code → $got", got is ApiResult.Rejected && got.code == code)
                else -> assertEquals("HTTP $code", want, got)
            }
        }
    }

    @Test fun redirectsAreNotFollowedSoTheTokenNeverLeaves() {
        server.on("/v1/events") { Response(302, headers = mapOf("Location" to "${server.baseUrl}/otro")) }
        server.on("/otro") { Response(200) }
        val result = client.postEvents(cfg(), listOf(item()))
        assertTrue(result is ApiResult.Transient)
        assertTrue(server.seen.none { it.uri == "/otro" })
    }

    @Test fun anUnreachableServerIsTransient() {
        assertTrue(client.postEvents(cfg("http://127.0.0.1:1"), listOf(item())) is ApiResult.Transient)
    }

    @Test fun aSlowServerIsTransientNotAHang() {
        server.on("/v1/events") { Response(200, delayMs = 2_000) }
        val started = System.nanoTime()
        assertTrue(client.postEvents(cfg(), listOf(item())) is ApiResult.Transient)
        assertTrue("debe cortar por tiempo", (System.nanoTime() - started) / 1_000_000 < 1_900)
    }

    @Test fun aPublicCleartextAddressIsRefusedWithoutTouchingTheNetwork() {
        val result = client.postEvents(cfg("http://example.com"), listOf(item()))
        assertTrue(result is ApiResult.Transient)
        assertTrue(server.seen.isEmpty())
    }

    @Test fun aBlankTokenIsUnauthorizedWithoutARequest() {
        assertEquals(ApiResult.Unauthorized, client.postEvents(cfg(token = " "), listOf(item())))
        assertTrue(server.seen.isEmpty())
    }

    @Test fun uploadsAudioWithItsMetadata() {
        val wav = ByteArray(44) + ByteArray(32_000) { 7 } // 1 s a 16 kHz mono
        server.on("/v1/audio") { Response(200) }
        val audioItem = OutboxItem("seg1", OutboxItem.Kind.AUDIO, "seg.wav", 5_000L)
        assertEquals(ApiResult.Ok, client.postAudio(cfg(), audioItem, wav))
        val req = server.seen.single()
        assertEquals("audio/wav", req.headers["content-type"])
        assertEquals("seg1", req.headers["x-zen-segment-id"])
        assertEquals("5000", req.headers["x-zen-started-at"])
        assertEquals("1000", req.headers["x-zen-duration-ms"])
        assertEquals("Bearer tok", req.headers["authorization"])
        assertTrue(req.body.contentEquals(wav))
    }

    @Test fun fetchesAndParsesCommands() {
        server.on("/v1/commands") { Response(200, """{"cursor":"c2","commands":[{"id":"1","type":"pause","minutes":10}]}""".toByteArray()) }
        val r = client.fetchCommands(cfg(), "c 1&x")
        assertEquals(ApiResult.Ok, r.result)
        assertEquals(listOf<BrainCommand>(BrainCommand.Pause("1", 10)), r.commands)
        assertEquals("c2", r.cursor)
        val req = server.seen.single()
        assertEquals("GET", req.method)
        assertEquals("/v1/commands?device=dev-1&cursor=c+1%26x", req.uri)
    }

    @Test fun aServerWithoutCommandsIsRejectedNotFatal() {
        val r = client.fetchCommands(cfg(), "")
        assertTrue(r.result is ApiResult.Rejected)
        assertTrue(r.commands.isEmpty())
    }

    @Test fun anUnreadableCommandsBodyIsRejected() {
        server.on("/v1/commands") { Response(200, "<html>no soy json</html>".toByteArray()) }
        assertTrue(client.fetchCommands(cfg(), "").result is ApiResult.Rejected)
    }

    @Test fun anAbsurdlyLargeResponseIsCutOffInsteadOfExhaustingMemory() {
        server.on("/v1/commands") {
            val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
            Response(200, writer = { out -> repeat(48) { out.write(chunk) } }) // 3 MB
        }
        assertTrue(client.fetchCommands(cfg(), "").result is ApiResult.Rejected)
    }

    @Test fun trailingSlashesInTheBaseUrlAreHandled() {
        server.on("/v1/events") { Response(200) }
        assertEquals(ApiResult.Ok, client.postEvents(cfg(server.baseUrl + "/"), listOf(item())))
        assertEquals("/v1/events", server.seen.single().uri)
    }

    @Test fun aBasePathIsKept() {
        server.on("/zen/v1/events") { Response(200) }
        assertEquals(ApiResult.Ok, client.postEvents(cfg(server.baseUrl + "/zen"), listOf(item())))
    }
}
