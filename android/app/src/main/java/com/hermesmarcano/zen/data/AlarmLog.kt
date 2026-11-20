package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.AlarmHealth
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registro corto (los últimos [AlarmHealth.MAX_EVENTS] eventos) de lo que pasó con las alarmas, en su propio archivo.
 * Nunca debe estorbar a una alarma: si no se puede leer o escribir, se ignora.
 */
class AlarmLog(private val file: File) {
    // Perezoso: los receptores que levantan el proceso no leen el registro si no lo necesitan.
    private val flow by lazy { MutableStateFlow(load()) }
    val events: StateFlow<List<AlarmHealth.Event>> get() = flow

    @Synchronized
    fun record(event: AlarmHealth.Event) {
        val current = flow.value
        // Programar la misma alarma para la misma hora (reprogramaciones) no llena el registro.
        val last = current.lastOrNull { it.alarmId == event.alarmId && (it.kind == event.kind) }
        if (event.kind == AlarmHealth.Kind.SCHEDULED && last?.expectedAt == event.expectedAt && last?.expectedAt != null) return
        // Cambios de hora repetidos no deben expulsar del registro los eventos que sí explican un fallo.
        val lastRestore = current.lastOrNull { it.kind == AlarmHealth.Kind.RESTORED }
        if (event.kind == AlarmHealth.Kind.RESTORED && lastRestore?.note == event.note && event.at - lastRestore.at < RESTORE_DEDUP_MS) return
        val next = (current + event).takeLast(AlarmHealth.MAX_EVENTS)
        try {
            AtomicFiles.write(file, encode(next))
        } catch (_: IOException) {
            // El registro es diagnóstico: perderlo no es motivo para fallar.
        }
        flow.value = next
    }

    /** Vacía el registro (para pruebas). */
    @androidx.annotation.VisibleForTesting
    @Synchronized
    fun clear() {
        flow.value = emptyList()
        try {
            AtomicFiles.write(file, encode(emptyList()))
        } catch (_: IOException) {
        }
    }

    private fun load(): List<AlarmHealth.Event> =
        if (!file.exists()) emptyList() else AtomicFiles.readOrQuarantine(file) { decode(it) } ?: emptyList()

    internal companion object {
        private const val RESTORE_DEDUP_MS = 10 * 60_000L

        fun encode(events: List<AlarmHealth.Event>): String = JSONArray().also { a ->
            events.forEach { e ->
                a.put(
                    JSONObject().put("at", e.at).put("kind", e.kind.id).put("alarmId", e.alarmId ?: JSONObject.NULL)
                        .put("expectedAt", e.expectedAt ?: JSONObject.NULL).put("note", e.note),
                )
            }
        }.toString()

        fun decode(text: String): List<AlarmHealth.Event> {
            val array = JSONArray(text)
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val kind = AlarmHealth.Kind.fromId(o.optString("kind")) ?: return@mapNotNull null
                AlarmHealth.Event(
                    at = o.optLong("at"),
                    kind = kind,
                    alarmId = if (o.isNull("alarmId")) null else o.optString("alarmId"),
                    expectedAt = if (o.isNull("expectedAt")) null else o.optLong("expectedAt"),
                    note = o.optString("note").take(120),
                )
            }.takeLast(AlarmHealth.MAX_EVENTS)
        }
    }
}
