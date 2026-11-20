package com.hermesmarcano.zen.layer.data

import com.hermesmarcano.zen.layer.core.ChatFailure
import com.hermesmarcano.zen.layer.core.ChatMessage
import com.hermesmarcano.zen.layer.core.ChatProposal
import com.hermesmarcano.zen.layer.core.ChatRole
import com.hermesmarcano.zen.layer.core.ChatRules
import com.hermesmarcano.zen.layer.core.ChatSnapshot
import com.hermesmarcano.zen.layer.core.ChatStore
import com.hermesmarcano.zen.layer.core.CommandPolicy
import com.hermesmarcano.zen.layer.core.SecretBox
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * La conversación, en un archivo CIFRADO con la clave del Keystore (lo que se le dice al cerebro es lo más privado de la app):
 * fuera de este teléfono el archivo no sirve. Si no se puede cifrar, falla: nunca se guarda en claro como plan B.
 *
 * Lo ilegible de verdad (manipulado, clave perdida, JSON roto) se descarta y se empieza de cero; un fallo pasajero del
 * Keystore (`IOException`) NO se toma por eso, para no borrar una conversación buena.
 */
class SealedChatStore(private val file: File, private val secrets: SecretBox) : ChatStore {
    @Throws(IOException::class)
    override fun load(): ChatSnapshot? {
        val raw = try {
            file.readText(Charsets.UTF_8)
        } catch (_: FileNotFoundException) {
            return null
        }
        val json = secrets.open(raw.trim(), CONTEXT)
        val snapshot = json?.let(::decode)
        if (snapshot == null) file.delete() // irrecuperable: no vuelve a intentarse en cada arranque
        return snapshot
    }

    @Throws(IOException::class)
    override fun save(snapshot: ChatSnapshot) {
        val sealed = secrets.seal(encode(snapshot), CONTEXT)
        file.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("No se pudo crear $it") }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(sealed, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("No se pudo guardar la conversación")
        }
    }

    /** Borra la conversación (y un temporal que pudiera haber quedado). Lanza si el archivo sigue ahí: no se dice «borrado» sin estarlo. */
    @Throws(IOException::class)
    override fun clear() {
        File(file.parentFile, file.name + ".tmp").delete()
        if (file.exists() && !file.delete()) throw IOException("No se pudo borrar la conversación")
    }

    companion object {
        /** Lo autenticado junto al texto: un archivo cifrado de otra cosa no vale aquí. */
        private const val CONTEXT = "chat:history:v1"
        private val SESSION = Regex("[A-Za-z0-9\\-]{8,64}")

        internal fun encode(s: ChatSnapshot): String = JSONObject()
            .put("session", s.session)
            .put(
                "messages",
                JSONArray().also { array ->
                    s.messages.forEach { m ->
                        array.put(
                            JSONObject()
                                .put("id", m.id)
                                .put("role", m.role.name)
                                .put("text", m.text)
                                .put("at", m.atMs)
                                .put("voice", m.voice)
                                .put("failure", m.failure?.name ?: JSONObject.NULL)
                                .put(
                                    "proposal",
                                    m.proposal?.let { p -> JSONObject().put("cid", p.commandId).put("title", p.title).put("start", p.startMs).put("end", p.endMs) } ?: JSONObject.NULL,
                                ),
                        )
                    }
                },
            )
            .toString()

        /** `null` si no es una conversación válida. Se valida todo: el archivo es nuestro, pero un archivo dañado no debe tumbar la app. */
        internal fun decode(text: String): ChatSnapshot? = try {
            val root = JSONObject(text)
            val session = root.optString("session", "")
            if (!SESSION.matches(session)) {
                null
            } else {
                val array = root.optJSONArray("messages") ?: JSONArray()
                val messages = ArrayList<ChatMessage>()
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val role = ChatRole.entries.firstOrNull { it.name == o.optString("role") } ?: continue
                    val id = o.optString("id", "")
                    if (id.isBlank()) continue
                    val proposal = o.optJSONObject("proposal")?.let { p ->
                        val title = p.optString("title", "")
                        val start = p.optLong("start", 0L)
                        val end = p.optLong("end", 0L)
                        val valid = title.isNotBlank() && title.length <= CommandPolicy.MAX_EVENT_TITLE_CHARS && start > 0 && end > start
                        if (valid) ChatProposal(p.optString("cid", ""), title, start, end) else null
                    }
                    messages += ChatMessage(
                        id = id,
                        role = role,
                        text = o.optString("text", "").take(ChatRules.MAX_REPLY_CHARS),
                        atMs = o.optLong("at", 0L),
                        voice = o.optBoolean("voice", false),
                        failure = if (o.isNull("failure")) null else ChatFailure.fromName(o.optString("failure")),
                        proposal = proposal,
                    )
                }
                ChatSnapshot(session, ChatRules.trim(messages.distinctBy { it.id }))
            }
        } catch (_: JSONException) {
            null
        }
    }
}
