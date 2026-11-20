package com.hermesmarcano.zen.layer.link

import com.hermesmarcano.zen.layer.core.BrainUrl
import com.hermesmarcano.zen.layer.core.ChatFailure
import com.hermesmarcano.zen.layer.core.OutboxItem
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cliente HTTP del cerebro, con la API estándar del JDK (sin dependencias). El token solo viaja en la cabecera
 * `Authorization`, nunca en la URL; no sigue redirecciones (no debe entregar el token a otro servidor) y vuelve
 * a validar la dirección en cada llamada.
 */
class BrainClient(
    private val userAgent: String = "ZEN-Layer",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) : BrainApi {
    override fun postEvents(cfg: BrainConfig, events: List<OutboxItem>): ApiResult {
        val body = EventJson.batchBody(cfg.deviceId, events.map { it.payload }).toByteArray(Charsets.UTF_8)
        return send(cfg, "POST", "/v1/events", "application/json", body = body)
    }

    override fun postAudio(cfg: BrainConfig, item: OutboxItem, wav: ByteArray): ApiResult {
        val extra = mapOf(
            "X-Zen-Segment-Id" to item.id,
            "X-Zen-Started-At" to item.createdAtMs.toString(),
            // 16 kHz, 16 bits, mono: 32 000 bytes por segundo, menos la cabecera de 44.
            "X-Zen-Duration-Ms" to (((wav.size - 44).coerceAtLeast(0).toLong() * 1000) / 32_000).toString(),
        )
        return send(cfg, "POST", "/v1/audio", "audio/wav", headers = extra, body = wav)
    }

    override fun fetchCommands(cfg: BrainConfig, cursor: String): CommandsResult {
        val path = "/v1/commands?device=${enc(cfg.deviceId)}&cursor=${enc(cursor)}"
        var text = ""
        val result = send(cfg, "GET", path, null, onBody = { text = it })
        if (result != ApiResult.Ok) return CommandsResult(result)
        return try {
            val (commands, next) = EventJson.parseCommands(text)
            CommandsResult(ApiResult.Ok, commands, next)
        } catch (e: Exception) {
            CommandsResult(ApiResult.Rejected(200, "respuesta ilegible"))
        }
    }

    override fun chat(cfg: BrainConfig, session: String, text: String?, wav: ByteArray?): ChatResult {
        val x = if (wav != null) {
            exchange(cfg, "POST", "/v1/chat", "audio/wav", mapOf("X-Zen-Session" to session), wav, CHAT_READ_TIMEOUT_MS, errorBody = true)
        } else {
            val json = JSONObject().put("session", session).put("text", text ?: "").toString().toByteArray(Charsets.UTF_8)
            exchange(cfg, "POST", "/v1/chat", "application/json", emptyMap(), json, CHAT_READ_TIMEOUT_MS, errorBody = true)
        }
        return when (x) {
            is Exchange.Failed -> ChatResult.Failed(if (x.result == ApiResult.Unauthorized) ChatFailure.UNAUTHORIZED else ChatFailure.UNAVAILABLE)
            is Exchange.Response -> when {
                x.code in 200..299 -> EventJson.parseChat(x.body) ?: ChatResult.Failed(ChatFailure.BAD_REPLY)
                else -> ChatResult.Failed(ChatFailure.fromHttp(x.code, errorCode(x.body)))
            }
        }
    }

    /** El `error` de `{"error": "<código>"}`, o vacío. */
    private fun errorCode(body: String): String = try {
        JSONObject(body).optString("error", "")
    } catch (_: JSONException) {
        ""
    }

    private fun send(
        cfg: BrainConfig,
        method: String,
        path: String,
        contentType: String?,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        onBody: (String) -> Unit = {},
    ): ApiResult = when (val x = exchange(cfg, method, path, contentType, headers, body, readTimeoutMs, errorBody = false)) {
        is Exchange.Failed -> x.result
        is Exchange.Response -> {
            if (x.code in 200..299) onBody(x.body)
            classify(x.code)
        }
    }

    /** Lo que pasó en la red: una respuesta HTTP (con su cuerpo) o un fallo ya clasificado. */
    private sealed interface Exchange {
        data class Response(val code: Int, val body: String) : Exchange
        data class Failed(val result: ApiResult) : Exchange
    }

    /** [errorBody]: también lee (hasta 4 KB) el cuerpo de las respuestas de error, para leer el código que da el servidor. */
    private fun exchange(
        cfg: BrainConfig,
        method: String,
        path: String,
        contentType: String?,
        headers: Map<String, String>,
        body: ByteArray?,
        readTimeout: Int,
        errorBody: Boolean,
    ): Exchange {
        // Se vuelve a validar: el estado pudo cambiar o corromperse desde que se configuró.
        val base = (BrainUrl.validate(cfg.baseUrl) as? BrainUrl.Result.Ok)?.baseUrl
            ?: return Exchange.Failed(ApiResult.Transient("dirección del cerebro no válida"))
        if (cfg.token.isBlank()) return Exchange.Failed(ApiResult.Unauthorized)
        val connection = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return Exchange.Failed(ApiResult.Transient(e.message ?: "no se pudo abrir la conexión"))
        }
        return try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeout
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val text = when {
                code in 200..299 -> readLimited(connection.inputStream, MAX_RESPONSE_BYTES)
                errorBody -> connection.errorStream?.let { readLimited(it, MAX_ERROR_BYTES) } ?: ""
                else -> ""
            }
            Exchange.Response(code, text)
        } catch (e: IOException) {
            Exchange.Failed(ApiResult.Transient(e.message ?: "sin conexión"))
        } finally {
            connection.disconnect()
        }
    }

    private fun classify(code: Int): ApiResult = when (code) {
        in 200..299 -> ApiResult.Ok
        401, 403 -> ApiResult.Unauthorized
        408, 425, 429 -> ApiResult.Transient("HTTP $code")
        in 300..399 -> ApiResult.Transient("redirección no permitida (HTTP $code)")
        in 400..499 -> ApiResult.Rejected(code, "HTTP $code")
        else -> ApiResult.Transient("HTTP $code")
    }

    /** Lee como mucho [max] bytes: una respuesta absurda no debe agotar la memoria. */
    private fun readLimited(stream: java.io.InputStream, max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (out.size() < max) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_000_000
        const val MAX_ERROR_BYTES = 4_096

        /** Más que el plazo más largo del adaptador para el chat (timeout de Hermes ≤ 90 s + 40 s = 130 s): el error del servidor llega antes que el corte del teléfono. */
        const val CHAT_READ_TIMEOUT_MS = 140_000
    }
}
