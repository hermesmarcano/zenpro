package com.hermesmarcano.zen.layer.data

import com.hermesmarcano.zen.layer.core.BlockEntry
import com.hermesmarcano.zen.layer.core.BlockReason
import com.hermesmarcano.zen.layer.core.CaptureSettings
import com.hermesmarcano.zen.layer.core.DayWindow
import com.hermesmarcano.zen.layer.core.Draft
import com.hermesmarcano.zen.layer.core.DraftStatus
import com.hermesmarcano.zen.layer.core.LayerState
import com.hermesmarcano.zen.layer.core.LayerStateStore
import com.hermesmarcano.zen.layer.core.LimitRule
import com.hermesmarcano.zen.layer.core.PendingRule
import com.hermesmarcano.zen.layer.core.SealedText
import com.hermesmarcano.zen.layer.core.SecretBox
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Guarda el estado de la capa en un único JSON con escritura atómica (archivo temporal + renombrado), igual
 * que el estado de ZEN. Un archivo corrupto se aparta como `.corrupt` y se arranca limpio: la capa nunca debe
 * quedarse sin poder iniciar por un archivo roto.
 */
class LayerStateRepository(private val file: File, private val secrets: SecretBox) : LayerStateStore {
    private val flow = MutableStateFlow(load())
    val state: StateFlow<LayerState> get() = flow
    override val current: LayerState get() = flow.value

    @Synchronized
    @Throws(IOException::class)
    override fun update(transform: (LayerState) -> LayerState): LayerState {
        val next = transform(flow.value)
        if (next == flow.value) return next
        write(next)
        flow.value = next
        return next
    }

    private fun load(): LayerState {
        if (!file.exists()) return LayerState()
        return try {
            val text = file.readText(Charsets.UTF_8)
            val loaded = LayerStateJson.decode(text, secrets)
            // Un token guardado en claro por una versión anterior se vuelve a escribir ya cifrado.
            if (LayerStateJson.hasPlainToken(text)) {
                try {
                    write(loaded)
                } catch (_: IOException) {
                    // Sin poder cifrar se queda como estaba; se reintentará en el siguiente cambio.
                }
            }
            loaded
        } catch (_: Exception) {
            try {
                file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
                file.delete()
            } catch (_: Exception) {
                // Mejor arrancar vacío que no arrancar.
            }
            LayerState()
        }
    }

    private fun write(state: LayerState) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw IOException("No se pudo crear $parent")
        val temp = File(parent, file.name + ".tmp")
        temp.writeText(LayerStateJson.encode(state, secrets), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("No se pudo guardar el estado de la capa")
        }
    }
}

/**
 * Codificación del estado. Es tolerante al leer: campos que faltan toman su valor por defecto, valores
 * fuera de rango se acotan y las entradas rotas se omiten en vez de tumbar todo el archivo.
 */
internal object LayerStateJson {
    const val VERSION = 1
    private const val TOKEN_CONTEXT = "layer.brainToken"

    /** ¿El archivo guarda un token sin cifrar? (Lo dejaba así la versión anterior; se corrige al cargar.) */
    fun hasPlainToken(text: String): Boolean = try {
        val token = JSONObject(text).optJSONObject("brain")?.optString("token", "").orEmpty()
        token.isNotEmpty() && !SealedText.isSealed(token)
    } catch (_: Exception) {
        false
    }

    /** Lanza `IOException` si hay token y no se puede cifrar: nunca se escribe en claro. */
    fun encode(s: LayerState, secrets: SecretBox): String {
        val root = JSONObject().put("version", VERSION)
        root.put(
            "capture",
            JSONObject()
                .put("screenText", s.capture.screenText)
                .put("notifications", s.capture.notifications)
                .put("ambientAudio", s.capture.ambientAudio)
                .put("pausedUntilMs", s.capture.pausedUntilMs)
                .put("denyPackages", JSONArray(s.capture.denyPackages.sorted())),
        )
        root.put("limits", JSONArray().also { a -> s.limits.forEach { a.put(ruleToJson(it)) } })
        root.put(
            "pendingRules",
            JSONArray().also { a ->
                s.pendingRules.forEach {
                    a.put(JSONObject().put("pkg", it.pkg).put("effectiveAtMs", it.effectiveAtMs).put("rule", it.rule?.let(::ruleToJson) ?: JSONObject.NULL))
                }
            },
        )
        root.put(
            "blocks",
            JSONArray().also { a -> s.blocks.forEach { a.put(JSONObject().put("pkg", it.pkg).put("untilMs", it.untilMs).put("reason", it.reason.name)) } },
        )
        root.put("passes", longMap(s.passes))
        root.put("relaxRequests", longMap(s.relaxRequests))
        root.put(
            "drafts",
            JSONArray().also { a ->
                s.drafts.forEach {
                    a.put(
                        JSONObject().put("id", it.id).put("conversationKey", it.conversationKey).put("pkg", it.pkg).put("contact", it.contact)
                            .put("text", it.text).put("createdAtMs", it.createdAtMs).put("expiresAtMs", it.expiresAtMs).put("status", it.status.name),
                    )
                }
            },
        )
        root.put(
            "brain",
            JSONObject().put("url", s.brainUrl).put("token", if (s.brainToken.isEmpty()) "" else secrets.seal(s.brainToken, TOKEN_CONTEXT)).put("deviceId", s.deviceId).put("commandCursor", s.commandCursor)
                .put("seenCommandIds", JSONArray(s.seenCommandIds)).put("lastSyncOkAtMs", s.lastSyncOkAtMs ?: JSONObject.NULL)
                .put("lastSyncError", s.lastSyncError),
        )
        root.put(
            "daily",
            JSONObject().put("day", s.dailyDay).put("usage", longMap(s.usageTotals))
                .put("nudges", JSONObject().also { o -> s.nudgesSent.forEach { (k, v) -> o.put(k, JSONArray(v.sorted())) } })
                .put("relaxGranted", s.relaxGranted),
        )
        root.put(
            "health",
            JSONObject().put("autostartConfirmed", s.autostartConfirmed).put("repairsMs", JSONArray(s.repairsMs))
                .put("accessibilityDownSinceMs", s.accessibilityDownSinceMs ?: JSONObject.NULL).put("lastRepairNoticeAtMs", s.lastRepairNoticeAtMs),
        )
        return root.toString()
    }

    fun decode(text: String, secrets: SecretBox): LayerState {
        val root = JSONObject(text)
        val capture = root.optJSONObject("capture") ?: JSONObject()
        val brain = root.optJSONObject("brain") ?: JSONObject()
        val daily = root.optJSONObject("daily") ?: JSONObject()
        val health = root.optJSONObject("health") ?: JSONObject()
        return LayerState(
            capture = CaptureSettings(
                screenText = capture.optBoolean("screenText", false),
                notifications = capture.optBoolean("notifications", false),
                ambientAudio = capture.optBoolean("ambientAudio", false),
                pausedUntilMs = capture.optLong("pausedUntilMs", 0L).coerceAtLeast(0L),
                denyPackages = strings(capture.optJSONArray("denyPackages")).toSet(),
            ),
            limits = objects(root.optJSONArray("limits")).mapNotNull(::ruleFromJson),
            pendingRules = objects(root.optJSONArray("pendingRules")).mapNotNull { o ->
                val pkg = o.optString("pkg").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val ruleJson = o.optJSONObject("rule")
                val rule = ruleJson?.let(::ruleFromJson)
                // Una regla ilegible no debe convertirse en "quitar la regla" por accidente.
                if (ruleJson != null && rule == null) return@mapNotNull null
                PendingRule(pkg, rule, o.optLong("effectiveAtMs", 0L))
            },
            blocks = objects(root.optJSONArray("blocks")).mapNotNull { o ->
                val pkg = o.optString("pkg").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val reason = enumOrNull<BlockReason>(o.optString("reason")) ?: BlockReason.MANUAL
                BlockEntry(pkg, o.optLong("untilMs", 0L), reason)
            },
            passes = longMap(root.optJSONObject("passes")),
            relaxRequests = longMap(root.optJSONObject("relaxRequests")),
            drafts = objects(root.optJSONArray("drafts")).mapNotNull { o ->
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val key = o.optString("conversationKey").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Draft(
                    id = id, conversationKey = key, pkg = o.optString("pkg"), contact = o.optString("contact"), text = o.optString("text"),
                    createdAtMs = o.optLong("createdAtMs", 0L), expiresAtMs = o.optLong("expiresAtMs", 0L),
                    status = enumOrNull<DraftStatus>(o.optString("status")) ?: DraftStatus.DISCARDED,
                )
            },
            brainUrl = brain.optString("url", ""),
            brainToken = openToken(brain.optString("token", ""), secrets),
            deviceId = brain.optString("deviceId", ""),
            commandCursor = brain.optString("commandCursor", ""),
            seenCommandIds = strings(brain.optJSONArray("seenCommandIds")),
            lastSyncOkAtMs = nullableLong(brain, "lastSyncOkAtMs"),
            lastSyncError = brain.optString("lastSyncError", ""),
            dailyDay = daily.optLong("day", -1L),
            usageTotals = longMap(daily.optJSONObject("usage")).filterValues { it > 0 },
            nudgesSent = daily.optJSONObject("nudges")?.let { o ->
                o.keys().asSequence().associateWith { k -> (o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()).toSet() }
            } ?: emptyMap(),
            relaxGranted = daily.optInt("relaxGranted", 0).coerceAtLeast(0),
            autostartConfirmed = health.optBoolean("autostartConfirmed", false),
            repairsMs = longs(health.optJSONArray("repairsMs")),
            accessibilityDownSinceMs = nullableLong(health, "accessibilityDownSinceMs"),
            lastRepairNoticeAtMs = health.optLong("lastRepairNoticeAtMs", 0L),
        )
    }

    /** Cifrado → se abre (o queda vacío si la clave ya no sirve: hay que volver a poner el token). Sin cifrar → versión anterior. */
    private fun openToken(stored: String, secrets: SecretBox): String = when {
        stored.isEmpty() -> ""
        SealedText.isSealed(stored) -> secrets.open(stored, TOKEN_CONTEXT).orEmpty()
        else -> stored
    }

    private fun ruleToJson(r: LimitRule): JSONObject = JSONObject().put("pkg", r.pkg).put("label", r.label).put("dailyLimitMs", r.dailyLimitMs)
        .put("blockAtLimit", r.blockAtLimit)
        .put("window", r.window?.let { JSONObject().put("start", it.startMinute).put("end", it.endMinute) } ?: JSONObject.NULL)

    private fun ruleFromJson(o: JSONObject): LimitRule? {
        val pkg = o.optString("pkg").takeIf { it.isNotBlank() } ?: return null
        val window = o.optJSONObject("window")?.let { DayWindow(it.optInt("start", 0), it.optInt("end", 0)) }?.takeIf { it.isValid }
        return LimitRule(
            pkg = pkg,
            label = o.optString("label", pkg).ifBlank { pkg },
            dailyLimitMs = o.optLong("dailyLimitMs", 0L).coerceAtLeast(0L),
            blockAtLimit = o.optBoolean("blockAtLimit", false),
            window = window,
        )
    }

    private fun longMap(m: Map<String, Long>): JSONObject = JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, v) } }

    private fun longMap(o: JSONObject?): Map<String, Long> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().associateWith { o.optLong(it, 0L) }.filterKeys { it.isNotBlank() }
    }

    private fun nullableLong(o: JSONObject, key: String): Long? = if (o.isNull(key)) null else o.optLong(key)

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }

    private fun longs(a: JSONArray?): List<Long> = if (a == null) emptyList() else (0 until a.length()).map { a.optLong(it) }

    private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
}
