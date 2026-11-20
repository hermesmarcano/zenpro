package com.hermesmarcano.zen.layer.link

import com.hermesmarcano.zen.layer.core.BrainCommand
import com.hermesmarcano.zen.layer.core.LayerEvent
import com.hermesmarcano.zen.layer.core.OutboxItem
import org.json.JSONArray
import org.json.JSONObject

/** Dónde está el cerebro y cómo identificarse. La URL ya viene validada ([com.hermesmarcano.zen.layer.core.BrainUrl]). */
data class BrainConfig(val baseUrl: String, val token: String, val deviceId: String)

sealed interface ApiResult {
    data object Ok : ApiResult

    /** No se pudo llegar o el servidor falló (red, 5xx, 429): se reintenta más tarde y NO se pierde nada. */
    data class Transient(val message: String) : ApiResult

    /** El servidor recibió esto y dijo que no (4xx): reintentar igual no sirve; tras varios rechazos se descarta. */
    data class Rejected(val code: Int, val message: String) : ApiResult

    /** Token rechazado (401/403): no se insiste hasta que el usuario lo arregle. */
    data object Unauthorized : ApiResult
}

data class CommandsResult(val result: ApiResult, val commands: List<BrainCommand> = emptyList(), val cursor: String = "")

/** Lo que la capa le pide al cerebro. Es una interfaz para poder probar la sincronización sin red. */
interface BrainApi {
    fun postEvents(cfg: BrainConfig, events: List<OutboxItem>): ApiResult
    fun postAudio(cfg: BrainConfig, item: OutboxItem, wav: ByteArray): ApiResult
    fun fetchCommands(cfg: BrainConfig, cursor: String): CommandsResult
}

/** El protocolo (JSON) con el cerebro. Está documentado en docs/XIAOMI-LAYER.md. */
object EventJson {
    fun encode(e: LayerEvent): String = JSONObject()
        .put("id", e.id)
        .put("kind", e.kind.name.lowercase())
        .put("package", e.pkg)
        .put("title", e.title)
        .put("text", e.text)
        .put("at", e.atMs)
        .put("extra", JSONObject(e.extra as Map<*, *>))
        .toString()

    /** `{"device": "...", "events": [ ... ]}`. Cada elemento de [payloads] ya es un objeto JSON producido por [encode]. */
    fun batchBody(deviceId: String, payloads: List<String>): String =
        "{\"device\":${JSONObject.quote(deviceId)},\"events\":[${payloads.joinToString(",")}]}"

    /**
     * Lee `{"cursor": "...", "commands": [ {"id","type",...} ]}`. Es tolerante: tipos que no conoce se saltan
     * (el cerebro puede ser más nuevo que el teléfono) y los campos que faltan quedan vacíos para que la
     * validación ([com.hermesmarcano.zen.layer.core.CommandPolicy]) los rechace.
     */
    fun parseCommands(body: String): Pair<List<BrainCommand>, String> {
        val root = JSONObject(body)
        val cursor = root.optString("cursor", "")
        val array = root.optJSONArray("commands") ?: JSONArray()
        val commands = ArrayList<BrainCommand>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val command = when (o.optString("type")) {
                "draft_reply" -> BrainCommand.DraftReply(id, o.optString("conversation", ""), o.optString("text", ""))
                "block" -> BrainCommand.Block(id, o.optString("package", ""), o.optInt("minutes", 0))
                "notify" -> BrainCommand.Notify(id, o.optString("title", ""), o.optString("text", ""))
                "pause" -> BrainCommand.Pause(id, o.optInt("minutes", 0))
                else -> null
            }
            if (command != null) commands += command
        }
        return commands to cursor
    }
}
