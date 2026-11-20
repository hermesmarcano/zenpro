package com.hermesmarcano.zen.layer.core

/**
 * Como mucho [limit] usos dentro de cualquier ventana de [windowMs]. Sirve para que un cerebro engañado (o roto) no pueda
 * inundar de avisos ni bloquear apps en ráfaga. Vive en memoria: reiniciar la app lo vacía, pero el adaptador del
 * servidor aplica el mismo tipo de límite por su lado.
 */
class RateWindow(private val limit: Int, private val windowMs: Long) {
    private val uses = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        while (uses.isNotEmpty() && nowMs - uses.first() >= windowMs) uses.removeFirst()
        // Un reloj que retrocede no debe dejar la ventana abierta para siempre.
        if (uses.isNotEmpty() && uses.last() > nowMs) uses.clear()
        if (uses.size >= limit) return false
        uses.addLast(nowMs)
        return true
    }
}
