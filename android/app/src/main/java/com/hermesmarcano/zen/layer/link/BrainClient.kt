package com.hermesmarcano.zen.layer.link

import com.hermesmarcano.zen.layer.core.BrainUrl
import com.hermesmarcano.zen.layer.core.OutboxItem
import java.io.File
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

    override fun postAudio(cfg: BrainConfig, item: OutboxItem, file: File): ApiResult {
        val extra = mapOf(
            "X-Zen-Segment-Id" to item.id,
            "X-Zen-Started-At" to item.createdAtMs.toString(),
            // 16 kHz, 16 bits, mono: 32 000 bytes por segundo, menos la cabecera de 44.
            "X-Zen-Duration-Ms" to (((file.length() - 44).coerceAtLeast(0) * 1000) / 32_000).toString(),
        )
        return send(cfg, "POST", "/v1/audio", "audio/wav", headers = extra, file = file)
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

    private fun send(
        cfg: BrainConfig,
        method: String,
        path: String,
        contentType: String?,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        file: File? = null,
        onBody: (String) -> Unit = {},
    ): ApiResult {
        // Se vuelve a validar: el estado pudo cambiar o corromperse desde que se configuró.
        val base = (BrainUrl.validate(cfg.baseUrl) as? BrainUrl.Result.Ok)?.baseUrl ?: return ApiResult.Transient("dirección del cerebro no válida")
        if (cfg.token.isBlank()) return ApiResult.Unauthorized
        val connection = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return ApiResult.Transient(e.message ?: "no se pudo abrir la conexión")
        }
        return try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
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
            } else if (file != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(file.length())
                file.inputStream().use { input -> connection.outputStream.use { input.copyTo(it) } }
            }
            val code = connection.responseCode
            val text = if (code in 200..299) readLimited(connection) else ""
            if (code in 200..299) onBody(text)
            classify(code)
        } catch (e: IOException) {
            ApiResult.Transient(e.message ?: "sin conexión")
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

    /** Lee como mucho 1 MB: una respuesta absurda no debe agotar la memoria. */
    private fun readLimited(connection: HttpURLConnection): String {
        val out = java.io.ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (out.size() < MAX_RESPONSE_BYTES) {
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
    }
}
