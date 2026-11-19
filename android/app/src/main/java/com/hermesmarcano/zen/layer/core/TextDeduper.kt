package com.hermesmarcano.zen.layer.core

/**
 * Convierte "todo el texto de la pantalla" (que el servicio de accesibilidad entrega en cada cambio)
 * en "solo lo nuevo": las líneas que no se habían visto hace poco en esa app. Sin esto la memoria se
 * llenaría con la misma pantalla repetida cientos de veces al desplazarse.
 *
 * Además limita la frecuencia por app. Si un evento llega demasiado pronto se descarta SIN marcar sus
 * líneas como vistas, así que se recogerán en el siguiente evento.
 */
class TextDeduper(
    private val minIntervalMs: Long = 1_500L,
    private val maxLinesPerPackage: Int = 400,
    private val maxPackages: Int = 32,
    private val minLineLength: Int = 3,
) {
    private class PerPackage {
        val seen = LinkedHashSet<Long>()
        var lastEmitAtMs: Long = Long.MIN_VALUE
    }

    private val packages = object : LinkedHashMap<String, PerPackage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PerPackage>?): Boolean = size > maxPackages
    }

    /** ¿Ya pasó el intervalo mínimo para esta app? Permite saltarse el recorrido del árbol de la pantalla si aún no. */
    @Synchronized
    fun ready(pkg: String, nowMs: Long): Boolean = !throttled(packages[pkg], nowMs)

    private fun throttled(state: PerPackage?, nowMs: Long): Boolean {
        if (state == null || state.lastEmitAtMs == Long.MIN_VALUE) return false
        // El reloj que retrocede no debe bloquear la captura para siempre.
        val elapsed = if (nowMs < state.lastEmitAtMs) Long.MAX_VALUE else nowMs - state.lastEmitAtMs
        return elapsed < minIntervalMs
    }

    @Synchronized
    fun fresh(pkg: String, lines: List<String>, nowMs: Long, force: Boolean = false): List<String> {
        val state = packages.getOrPut(pkg) { PerPackage() }
        if (!force && throttled(state, nowMs)) return emptyList()

        val novel = ArrayList<String>()
        for (raw in lines) {
            val line = normalize(raw)
            if (!worthKeeping(line)) continue
            if (state.seen.add(hash(line))) novel += line
        }
        if (novel.isNotEmpty()) state.lastEmitAtMs = nowMs
        trim(state)
        return novel
    }

    @Synchronized
    fun forget(pkg: String) {
        packages.remove(pkg)
    }

    @Synchronized
    fun clear() = packages.clear()

    private fun trim(state: PerPackage) {
        if (state.seen.size <= maxLinesPerPackage) return
        val iterator = state.seen.iterator()
        var excess = state.seen.size - maxLinesPerPackage
        while (excess > 0 && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
            excess--
        }
    }

    companion object {
        fun normalize(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

        /** Descarta basura de la interfaz: "12:45", "•••", una letra suelta. */
        fun worthKeeping(line: String): Boolean = line.length >= 3 && (line.any(Char::isLetter) || line.length >= 8)

        /** FNV-1a de 64 bits: barato y con colisiones despreciables para este uso. */
        fun hash(line: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in line) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
