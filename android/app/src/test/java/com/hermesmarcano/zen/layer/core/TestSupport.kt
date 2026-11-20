package com.hermesmarcano.zen.layer.core

import java.io.IOException

/** Caja de cifrado de software para pruebas: cada llamada, una clave nueva. */
internal fun softwareBox(): SecretBox = AesGcmSecretBox(SoftwareKeys.random().let { key -> { key } })

internal const val MIN = 60_000L
internal const val HOUR = 60 * MIN
internal const val DAY = 24 * HOUR

/** Días de 24 h en UTC: suficiente para probar el reparto en medianoche sin depender de la zona del servidor. */
internal object UtcDays : DayClock {
    override fun dayOf(ms: Long): Long = Math.floorDiv(ms, DAY)
    override fun nextDayStart(ms: Long): Long = (Math.floorDiv(ms, DAY) + 1) * DAY
    override fun minuteOfDay(ms: Long): Int = (Math.floorMod(ms, DAY) / 60_000L).toInt()
}

internal class MemoryState(initial: LayerState = LayerState()) : LayerStateStore {
    override var current: LayerState = initial
        private set
    var failWrites = false
    var writes = 0

    override fun update(transform: (LayerState) -> LayerState): LayerState {
        if (failWrites) throw IOException("disco lleno")
        val next = transform(current)
        if (next != current) writes++
        current = next
        return next
    }
}

internal class MemoryTimeline : TimelineStore {
    val events = ArrayList<LayerEvent>()
    var failInserts = false

    override fun insert(event: LayerEvent) {
        if (failInserts) throw IOException("sin espacio")
        events += event
    }

    override fun search(query: String, limit: Int) =
        events.filter { query.lowercase() in (it.title + " " + it.text).lowercase() }.take(limit)

    override fun recent(limit: Int) = events.sortedByDescending { it.atMs }.take(limit)
    override fun count() = events.size
    override fun deleteAll() = events.clear()
    override fun deletePackage(pkg: String) = events.count { it.pkg == pkg }.also { events.removeAll { e -> e.pkg == pkg } }
    override fun prune(nowMs: Long) = 0
}

internal class MemoryOutbox : OutboxStore {
    val items = ArrayList<OutboxItem>()
    var failEnqueue = false

    override fun enqueue(item: OutboxItem) {
        if (failEnqueue) throw IOException("cola rota")
        items += item
    }

    override fun due(kind: OutboxItem.Kind, nowMs: Long, limit: Int) = OutboxPolicy.due(items, kind, nowMs, limit)

    override fun markFailed(items: List<OutboxItem>, nowMs: Long, random: Double, rejected: Boolean): List<OutboxItem> {
        val ids = items.map { it.id }.toSet()
        this.items.replaceAll { if (it.id in ids) OutboxPolicy.afterFailure(it, nowMs, random, rejected) else it }
        val exhausted = this.items.filter(OutboxPolicy::isExhausted)
        this.items.removeAll(exhausted)
        return exhausted
    }

    override fun remove(ids: Collection<String>) {
        items.removeAll { it.id in ids }
    }

    override fun count() = items.size

    override fun prune(nowMs: Long): List<OutboxItem> {
        val (kept, dropped) = OutboxPolicy.prune(items, nowMs)
        items.clear()
        items += kept
        return dropped
    }

    override fun clear(): List<OutboxItem> = items.toList().also { items.clear() }
}

/** Nodo de pantalla de mentira que cuenta cuántas veces se liberó. */
internal class FakeNode(
    override val text: CharSequence? = null,
    override val isVisible: Boolean = true,
    override val isPassword: Boolean = false,
    private val kids: List<FakeNode> = emptyList(),
) : TextNode {
    var releases = 0
    override val childCount: Int get() = kids.size
    override fun child(index: Int): TextNode? = kids.getOrNull(index)
    override fun release() {
        releases++
    }

    fun all(): List<FakeNode> = listOf(this) + kids.flatMap { it.all() }
}

internal val BRAIN_STATE = LayerState(brainUrl = "https://brain.example.com", brainToken = "token")
