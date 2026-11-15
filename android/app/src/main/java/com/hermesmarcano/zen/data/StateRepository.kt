package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.AlarmSound
import com.hermesmarcano.zen.core.CompletionSound
import com.hermesmarcano.zen.core.Weekday
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persistencia de todo el estado en un único JSON con escritura atómica (archivo temporal +
 * renombrado), así un corte de energía a mitad de guardado nunca deja el archivo a medias. Un
 * archivo corrupto no tumba la app: se aparta como `.corrupt` y se arranca limpio.
 */
class StateRepository(private val file: File) {
    private val flow = MutableStateFlow(load())
    val state: StateFlow<ZenState> get() = flow

    /** Aplica [transform] y guarda. Si la escritura falla, lanza y NO cambia el estado en memoria. */
    @Synchronized
    @Throws(IOException::class)
    fun update(transform: (ZenState) -> ZenState): ZenState {
        val next = transform(flow.value)
        if (next == flow.value) return next
        write(next)
        flow.value = next
        return next
    }

    private fun load(): ZenState {
        if (!file.exists()) return ZenState()
        return try {
            StateJson.decode(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            // JSONException, IOException o un valor fuera de rango: apartar el archivo para no perderlo.
            try {
                file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
                file.delete()
            } catch (_: Exception) {
                // Mejor arrancar vacío que no arrancar.
            }
            ZenState()
        }
    }

    private fun write(state: ZenState) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw IOException("No se pudo crear $parent")
        val temp = File(parent, file.name + ".tmp")
        temp.writeText(StateJson.encode(state), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            // Algunos sistemas de archivos no sustituyen al renombrar.
            file.delete()
            if (!temp.renameTo(file)) throw IOException("No se pudo guardar el estado")
        }
    }
}

internal object StateJson {
    private const val VERSION = 1

    fun encode(state: ZenState): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put(
            "alarms",
            JSONArray().also { array ->
                state.alarms.forEach { a ->
                    array.put(
                        JSONObject()
                            .put("id", a.id)
                            .put("hour", a.hour)
                            .put("minute", a.minute)
                            .put("weekdays", JSONArray(a.weekdays.map { it.iso }.sorted()))
                            .put("enabled", a.enabled)
                            .put("anchorId", a.anchorId ?: JSONObject.NULL)
                            .put("nextFireAt", a.nextFireAt ?: JSONObject.NULL),
                    )
                }
            },
        )
        root.put(
            "anchors",
            JSONArray().also { array ->
                state.anchors.forEach { anchor ->
                    val json = JSONObject().put("id", anchor.id).put("label", anchor.label).put("backupCode", anchor.backupCode)
                    when (anchor) {
                        is QrAnchor -> json.put("type", "qr").put("secret", anchor.secret)
                        is PhotoAnchor -> json.put("type", "photo").put("references", JSONArray(anchor.references))
                    }
                    array.put(json)
                }
            },
        )
        root.put("settings", encodeSettings(state.settings))
        root.put("stats", encodeStats(state.stats))
        return root.toString()
    }

    private fun encodeSound(choice: SoundChoice): JSONObject = when (choice) {
        is SoundChoice.Preset -> JSONObject().put("type", "preset").put("id", choice.id)
        is SoundChoice.Custom -> JSONObject().put("type", "custom").put("file", choice.fileName).put("name", choice.displayName)
    }

    private fun decodeSound(json: JSONObject?, fallback: SoundChoice, knownIds: Set<String>): SoundChoice {
        if (json == null) return fallback
        return when (json.optString("type")) {
            // Un id que esta versión no conoce (p. ej. de una versión futura) vuelve al valor por defecto.
            "preset" -> json.optString("id").takeIf { it in knownIds }?.let { SoundChoice.Preset(it) } ?: fallback
            "custom" -> {
                val file = json.optString("file")
                if (file.isBlank()) fallback else SoundChoice.Custom(file, json.optString("name", file))
            }
            else -> fallback
        }
    }

    private fun encodeSettings(settings: ZenSettings): JSONObject = JSONObject()
        .put("alarmSound", encodeSound(settings.alarmSound))
        .put("completionSound", encodeSound(settings.completionSound))
        .put("startVolumePercent", settings.startVolumePercent)

    private fun decodeSettings(json: JSONObject?): ZenSettings {
        val defaults = ZenSettings()
        if (json == null) return defaults
        return ZenSettings(
            alarmSound = decodeSound(json.optJSONObject("alarmSound"), defaults.alarmSound, AlarmSound.entries.map { it.id }.toSet()),
            completionSound = decodeSound(json.optJSONObject("completionSound"), defaults.completionSound, CompletionSound.entries.map { it.id }.toSet()),
            startVolumePercent = json.optInt("startVolumePercent", defaults.startVolumePercent)
                .coerceIn(ZenSettings.MIN_START_VOLUME_PERCENT, ZenSettings.MAX_START_VOLUME_PERCENT),
        )
    }

    private fun encodeStats(stats: WakeStats): JSONObject = JSONObject()
        .put("currentStreak", stats.currentStreak)
        .put("bestStreak", stats.bestStreak)
        .put("totalWakeups", stats.totalWakeups)
        .put("lastWakeDay", stats.lastWakeDay ?: JSONObject.NULL)
        .put("lastSolveSeconds", stats.lastSolveSeconds ?: JSONObject.NULL)

    private fun decodeStats(json: JSONObject?): WakeStats {
        if (json == null) return WakeStats()
        val streak = json.optInt("currentStreak", 0).coerceAtLeast(0)
        return WakeStats(
            currentStreak = streak,
            bestStreak = maxOf(streak, json.optInt("bestStreak", 0).coerceAtLeast(0)),
            totalWakeups = json.optInt("totalWakeups", 0).coerceAtLeast(0),
            lastWakeDay = if (json.isNull("lastWakeDay")) null else json.optLong("lastWakeDay"),
            lastSolveSeconds = if (json.isNull("lastSolveSeconds")) null else json.optInt("lastSolveSeconds").coerceAtLeast(0),
        )
    }

    @Throws(JSONException::class)
    fun decode(text: String): ZenState {
        val root = JSONObject(text)
        val alarms = root.optJSONArray("alarms").objects().map { o ->
            val hour = o.getInt("hour")
            val minute = o.getInt("minute")
            if (hour !in 0..23 || minute !in 0..59) throw JSONException("hora fuera de rango")
            AlarmEntity(
                id = o.getString("id"),
                hour = hour,
                minute = minute,
                weekdays = o.optJSONArray("weekdays").ints().mapNotNull { Weekday.fromIso(it) }.toSet(),
                enabled = o.optBoolean("enabled", false),
                anchorId = if (o.isNull("anchorId")) null else o.getString("anchorId"),
                nextFireAt = if (o.isNull("nextFireAt")) null else o.getLong("nextFireAt"),
            )
        }
        val anchors = root.optJSONArray("anchors").objects().mapNotNull { o ->
            when (o.optString("type")) {
                "qr" -> QrAnchor(o.getString("id"), o.getString("label"), o.getString("secret"), o.getString("backupCode"))
                "photo" -> PhotoAnchor(
                    o.getString("id"),
                    o.getString("label"),
                    o.getString("backupCode"),
                    o.optJSONArray("references").strings(),
                )
                else -> null // un ancla de una versión futura: se ignora, no se rompe la carga
            }
        }
        return ZenState(alarms, anchors, decodeSettings(root.optJSONObject("settings")), decodeStats(root.optJSONObject("stats")))
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONArray?.ints(): List<Int> =
        if (this == null) emptyList() else (0 until length()).map { getInt(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
