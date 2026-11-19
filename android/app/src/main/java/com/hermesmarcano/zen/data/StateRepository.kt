package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.AlarmSound
import com.hermesmarcano.zen.core.CompletionSound
import com.hermesmarcano.zen.core.Levels
import com.hermesmarcano.zen.core.TaskBoard
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

    private fun load(): ZenState =
        if (!file.exists()) ZenState() else AtomicFiles.readOrQuarantine(file) { StateJson.decode(it) } ?: ZenState()

    private fun write(state: ZenState) = AtomicFiles.write(file, StateJson.encode(state))
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
        root.put(
            "tasks",
            JSONArray().also { array ->
                state.tasks.forEach { t ->
                    array.put(
                        JSONObject().put("id", t.id).put("title", t.title).put("done", t.done)
                            .put("day", t.day).put("position", t.position).put("xp", t.xpAwarded),
                    )
                }
            },
        )
        root.put("trip", state.trip?.let { JSONObject().put("until", it.untilEpochDay).put("anchorId", it.anchorId ?: JSONObject.NULL) } ?: JSONObject.NULL)
        root.put("progress", encodeProgress(state.progress))
        return root.toString()
    }

    private fun encodeProgress(p: Progress): JSONObject = JSONObject()
        .put("xp", p.xp)
        .put("mitStreak", encodeStats(p.mitStreak))
        .put("tasksCompleted", p.tasksCompleted)
        .put("mitsCompleted", p.mitsCompleted)
        .put("perfectDays", p.perfectDays)
        .put("fullDays", p.fullDays)
        .put("tripsStarted", p.tripsStarted)
        .put("taskXpDay", p.taskXpDay ?: JSONObject.NULL)
        .put("taskXpToday", p.taskXpToday)
        .put("completeDayBonusDay", p.completeDayBonusDay ?: JSONObject.NULL)
        .put("perfectDayBonusDay", p.perfectDayBonusDay ?: JSONObject.NULL)
        .put("achievements", JSONObject().also { o -> p.achievements.forEach { (k, v) -> o.put(k, v) } })
        .put("pausedRanges", JSONArray().also { a -> p.pausedRanges.forEach { a.put(JSONObject().put("from", it.from).put("to", it.to)) } })
        .put("workouts", p.workouts)
        .put("workoutVolumeKg", p.workoutVolumeKg)
        .put("personalRecords", p.personalRecords)
        .put("workoutXpDay", p.workoutXpDay ?: JSONObject.NULL)
        .put("goalBonusWeek", p.goalBonusWeek ?: JSONObject.NULL)
        .put("goalWeeks", p.goalWeeks)
        .put("goalWeekStreak", p.goalWeekStreak)
        .put("bestGoalWeekStreak", p.bestGoalWeekStreak)
        .put("lastGoalWeek", p.lastGoalWeek ?: JSONObject.NULL)
        .put("alarmTestsPassed", p.alarmTestsPassed)

    private fun decodeProgress(json: JSONObject?): Progress {
        if (json == null) return Progress()
        fun longOrNull(key: String): Long? = if (json.isNull(key) || !json.has(key)) null else json.optLong(key)
        val achievements = json.optJSONObject("achievements")?.let { o ->
            o.keys().asSequence().associateWith { o.optLong(it) }
        } ?: emptyMap()
        val ranges = json.optJSONArray("pausedRanges").objects().mapNotNull { r ->
            val from = r.optLong("from"); val to = r.optLong("to")
            if (to >= from) DayRange(from, to) else null
        }
        return Progress(
            xp = json.optInt("xp").coerceIn(0, Levels.MAX_XP),
            mitStreak = decodeStats(json.optJSONObject("mitStreak")),
            tasksCompleted = json.optInt("tasksCompleted").coerceAtLeast(0),
            mitsCompleted = json.optInt("mitsCompleted").coerceAtLeast(0),
            perfectDays = json.optInt("perfectDays").coerceAtLeast(0),
            fullDays = json.optInt("fullDays").coerceAtLeast(0),
            tripsStarted = json.optInt("tripsStarted").coerceAtLeast(0),
            taskXpDay = longOrNull("taskXpDay"),
            taskXpToday = json.optInt("taskXpToday").coerceAtLeast(0),
            completeDayBonusDay = longOrNull("completeDayBonusDay"),
            perfectDayBonusDay = longOrNull("perfectDayBonusDay"),
            achievements = achievements,
            pausedRanges = ranges,
            workouts = json.optInt("workouts").coerceAtLeast(0),
            workoutVolumeKg = json.optLong("workoutVolumeKg").coerceIn(0L, 1_000_000_000L),
            personalRecords = json.optInt("personalRecords").coerceAtLeast(0),
            workoutXpDay = longOrNull("workoutXpDay"),
            goalBonusWeek = longOrNull("goalBonusWeek"),
            goalWeeks = json.optInt("goalWeeks").coerceAtLeast(0),
            goalWeekStreak = json.optInt("goalWeekStreak").coerceAtLeast(0),
            bestGoalWeekStreak = maxOf(json.optInt("bestGoalWeekStreak").coerceAtLeast(0), json.optInt("goalWeekStreak").coerceAtLeast(0)),
            lastGoalWeek = longOrNull("lastGoalWeek"),
            alarmTestsPassed = json.optInt("alarmTestsPassed").coerceAtLeast(0),
        )
    }

    /** Tareas válidas, sin duplicados, con posiciones 0..n-1 por día y como mucho 10 por día. */
    private fun decodeTasks(array: JSONArray?): List<TaskItem> {
        val raw = array.objects().mapNotNull { o ->
            val id = o.optString("id"); val title = o.optString("title")
            if (id.isBlank() || title.isBlank()) null
            else TaskItem(id, title.take(TaskBoard.TITLE_MAX), o.optBoolean("done"), o.optLong("day"), o.optInt("position"), o.optBoolean("xp"))
        }.distinctBy { it.id }
        return raw.groupBy { it.day }.flatMap { (_, list) ->
            list.sortedBy { it.position }.take(TaskBoard.MAX_PER_DAY).mapIndexed { i, t -> t.copy(position = i) }
        }
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
        .put("taskHintSeen", settings.taskHintSeen)
        .put("diagnosticsAckAt", settings.diagnosticsAckAt)

    private fun decodeSettings(json: JSONObject?): ZenSettings {
        val defaults = ZenSettings()
        if (json == null) return defaults
        return ZenSettings(
            alarmSound = decodeSound(json.optJSONObject("alarmSound"), defaults.alarmSound, AlarmSound.entries.map { it.id }.toSet()),
            completionSound = decodeSound(json.optJSONObject("completionSound"), defaults.completionSound, CompletionSound.entries.map { it.id }.toSet()),
            startVolumePercent = json.optInt("startVolumePercent", defaults.startVolumePercent)
                .coerceIn(ZenSettings.MIN_START_VOLUME_PERCENT, ZenSettings.MAX_START_VOLUME_PERCENT),
            taskHintSeen = json.optBoolean("taskHintSeen", false),
            diagnosticsAckAt = json.optLong("diagnosticsAckAt", 0L).coerceAtLeast(0L),
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
        val trip = root.optJSONObject("trip")?.let { t ->
            Trip(t.optLong("until"), if (t.isNull("anchorId")) null else t.optString("anchorId").ifBlank { null })
        }
        return ZenState(
            alarms, anchors,
            decodeSettings(root.optJSONObject("settings")),
            decodeStats(root.optJSONObject("stats")),
            decodeTasks(root.optJSONArray("tasks")),
            trip,
            decodeProgress(root.optJSONObject("progress")),
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONArray?.ints(): List<Int> =
        if (this == null) emptyList() else (0 until length()).map { getInt(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
