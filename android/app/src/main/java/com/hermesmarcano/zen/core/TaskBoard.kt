package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.TaskItem

/**
 * Lista de tareas del día: hasta 10, ordenadas. La prioridad la da la **posición**:
 * la 1.ª es la prioritaria (MIT), las 3 siguientes de prioridad media y las 6 últimas de baja.
 * Así reordenar arrastrando ya cambia la prioridad, sin menús ni etiquetas que mantener.
 * Todo son funciones puras sobre la lista completa (de todos los días).
 */
object TaskBoard {
    const val MAX_PER_DAY = 10
    const val TITLE_MAX = 80

    enum class Tier(val title: String, val slots: Int) {
        HIGH("Prioritaria", 1),
        MEDIUM("Media", 3),
        LOW("Baja", 6),
    }

    fun tierAt(index: Int): Tier = when {
        index < Tier.HIGH.slots -> Tier.HIGH
        index < Tier.HIGH.slots + Tier.MEDIUM.slots -> Tier.MEDIUM
        else -> Tier.LOW
    }

    fun cleanTitle(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    sealed interface AddResult {
        data class Added(val tasks: List<TaskItem>, val item: TaskItem, val tier: Tier) : AddResult
        data object Blank : AddResult
        data object TooLong : AddResult
        data object Full : AddResult
    }

    fun forDay(all: List<TaskItem>, day: Long): List<TaskItem> = all.filter { it.day == day }.sortedBy { it.position }

    fun add(all: List<TaskItem>, day: Long, rawTitle: String, id: String): AddResult {
        val title = cleanTitle(rawTitle)
        if (title.isEmpty()) return AddResult.Blank
        if (title.length > TITLE_MAX) return AddResult.TooLong
        val current = forDay(all, day)
        if (current.size >= MAX_PER_DAY) return AddResult.Full
        val item = TaskItem(id, title, done = false, day = day, position = current.size)
        return AddResult.Added(all + item, item, tierAt(current.size))
    }

    /** Mueve la tarea de [from] a [to] dentro del día y renumera. Índices fuera de rango no hacen nada. */
    fun move(all: List<TaskItem>, day: Long, from: Int, to: Int): List<TaskItem> {
        val list = forDay(all, day).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return all
        list.add(to, list.removeAt(from))
        return replaceDay(all, day, list)
    }

    /** Cambia hecha/pendiente. Solo la tarea con ese id; el resto no cambia. */
    fun setDone(all: List<TaskItem>, id: String, done: Boolean): List<TaskItem> =
        all.map { if (it.id == id) it.copy(done = done) else it }

    fun markXpAwarded(all: List<TaskItem>, id: String): List<TaskItem> =
        all.map { if (it.id == id) it.copy(xpAwarded = true) else it }

    class Deleted(val item: TaskItem, val index: Int)

    fun delete(all: List<TaskItem>, id: String): Pair<List<TaskItem>, Deleted?> {
        val target = all.firstOrNull { it.id == id } ?: return all to null
        val list = forDay(all, target.day)
        val index = list.indexOfFirst { it.id == id }
        return replaceDay(all, target.day, list.filterNot { it.id == id }) to Deleted(target, index)
    }

    /** Deshace un borrado. `null` si el día se llenó mientras tanto o la tarea ya existe. */
    fun restore(all: List<TaskItem>, deleted: Deleted): List<TaskItem>? {
        val list = forDay(all, deleted.item.day).toMutableList()
        if (list.size >= MAX_PER_DAY || list.any { it.id == deleted.item.id }) return null
        list.add(deleted.index.coerceIn(0, list.size), deleted.item)
        return replaceDay(all, deleted.item.day, list)
    }

    fun rename(all: List<TaskItem>, id: String, rawTitle: String): List<TaskItem> {
        val title = cleanTitle(rawTitle)
        if (title.isEmpty() || title.length > TITLE_MAX) return all
        return all.map { if (it.id == id) it.copy(title = title) else it }
    }

    class CarryResult(val tasks: List<TaskItem>, val moved: Int, val left: Int)

    /** Pendientes de los últimos [lookbackDays] días anteriores a [today]. */
    fun pendingBefore(all: List<TaskItem>, today: Long, lookbackDays: Int = 7): List<TaskItem> =
        all.filter { it.day < today && it.day >= today - lookbackDays && !it.done }
            .sortedWith(compareBy({ it.day }, { it.position }))

    /** Trae a hoy las pendientes de días anteriores, hasta llenar los 10. Las que no caben se quedan atrás. */
    fun carryOver(all: List<TaskItem>, today: Long, lookbackDays: Int = 7): CarryResult {
        val pending = pendingBefore(all, today, lookbackDays)
        val room = MAX_PER_DAY - forDay(all, today).size
        val moving = pending.take(room.coerceAtLeast(0))
        if (moving.isEmpty()) return CarryResult(all, 0, pending.size)
        // Posiciones explícitas a continuación de las de hoy, en el orden en que estaban: desempatar por id
        // (aleatorio) mezclaba las tareas traídas.
        val base = forDay(all, today).size
        val newPosition = moving.mapIndexed { i, t -> t.id to base + i }.toMap()
        val updated = all.map { t -> newPosition[t.id]?.let { t.copy(day = today, position = it) } ?: t }
        val result = replaceDay(updated, today, forDay(updated, today))
        return CarryResult(result, moving.size, pending.size - moving.size)
    }

    /** Olvida lo de hace más de [keepDays] días (el historial son las estadísticas, no la lista). */
    fun prune(all: List<TaskItem>, today: Long, keepDays: Int = 7): List<TaskItem> =
        all.filter { it.day >= today - keepDays }

    /** Día redondo: al menos 3 tareas y todas hechas. */
    fun isPerfectDay(list: List<TaskItem>): Boolean = list.size >= 3 && list.all { it.done }

    /** Renumera 0..n-1 y escribe de vuelta la lista de un día sin tocar los demás. */
    private fun replaceDay(all: List<TaskItem>, day: Long, ordered: List<TaskItem>): List<TaskItem> {
        val renumbered = ordered.mapIndexed { index, item -> item.copy(position = index) }
        return all.filter { it.day != day } + renumbered
    }
}
