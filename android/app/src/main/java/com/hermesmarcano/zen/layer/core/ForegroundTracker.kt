package com.hermesmarcano.zen.layer.core

import java.time.Instant
import java.time.ZoneId

/** Cuándo empieza y acaba el día local. Se inyecta para poder probar medianoche y cambios de hora. */
interface DayClock {
    fun dayOf(ms: Long): Long
    fun nextDayStart(ms: Long): Long
    fun minuteOfDay(ms: Long): Int
}

class SystemDayClock(private val zone: ZoneId = ZoneId.systemDefault()) : DayClock {
    override fun dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

    override fun nextDayStart(ms: Long): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    override fun minuteOfDay(ms: Long): Int {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return t.hour * 60 + t.minute
    }
}

/**
 * Cuenta cuánto tiempo pasa cada app en primer plano, por día local. Se alimenta con "esta app está
 * delante" (cambios de ventana) y "se apagó la pantalla".
 *
 * Una sesión abierta se acredita como mucho hasta `última señal + [maxIdleMs]`: si el servicio perdió
 * el aviso de pantalla apagada, el contador no se infla durante horas.
 */
class ForegroundTracker(
    private val clock: DayClock = SystemDayClock(),
    private val maxIdleMs: Long = 2 * 60 * 60 * 1000L,
) {
    private val totals = HashMap<Long, MutableMap<String, Long>>()
    private var openPkg: String? = null
    private var openStartMs = 0L
    private var lastSeenMs = 0L

    /** [pkg] está delante ahora (o `null` si no hay ninguna app: pantalla apagada, bloqueo). */
    @Synchronized
    fun onForeground(pkg: String?, nowMs: Long) {
        val current = openPkg
        if (current != null && current == pkg) {
            if (nowMs - lastSeenMs <= maxIdleMs) {
                if (nowMs > lastSeenMs) lastSeenMs = nowMs
                return
            }
            // Demasiado silencio: la sesión vieja se cierra donde caducó (no se acredita el hueco) y esta empieza ahora.
        }
        closeOpen(nowMs)
        if (pkg != null) {
            openPkg = pkg
            openStartMs = nowMs
            lastSeenMs = nowMs
        }
    }

    @Synchronized
    fun onScreenOff(nowMs: Long) = onForeground(null, nowMs)

    /** Milisegundos de hoy en [pkg], incluida la sesión en curso. */
    @Synchronized
    fun usedMs(pkg: String, nowMs: Long): Long = todayTotals(nowMs)[pkg] ?: 0L

    @Synchronized
    fun todayTotals(nowMs: Long): Map<String, Long> {
        val today = clock.dayOf(nowMs)
        val result = HashMap(totals[today] ?: emptyMap())
        val pkg = openPkg
        if (pkg != null) {
            val end = minOf(nowMs, lastSeenMs + maxIdleMs)
            val open = creditInDay(openStartMs, end, today)
            if (open > 0) result[pkg] = (result[pkg] ?: 0L) + open
        }
        return result
    }

    /** Recupera lo contado hoy tras un reinicio del proceso (Xiaomi lo mata a menudo). Ignora otros días. */
    @Synchronized
    fun restore(day: Long, saved: Map<String, Long>, nowMs: Long) {
        if (day != clock.dayOf(nowMs)) return
        val target = totals.getOrPut(day) { HashMap() }
        for ((pkg, ms) in saved) if (ms > 0) target[pkg] = maxOf(target[pkg] ?: 0L, ms)
    }

    fun dayOf(nowMs: Long): Long = clock.dayOf(nowMs)

    private fun closeOpen(nowMs: Long) {
        val pkg = openPkg ?: return
        val end = minOf(nowMs, lastSeenMs + maxIdleMs)
        credit(pkg, openStartMs, end)
        openPkg = null
    }

    /** Reparte [startMs, endMs) entre los días que cruza. Un reloj que retrocede no resta nada. */
    private fun credit(pkg: String, startMs: Long, endMs: Long) {
        var from = startMs
        while (from < endMs) {
            val dayEnd = clock.nextDayStart(from)
            val to = minOf(endMs, dayEnd)
            val map = totals.getOrPut(clock.dayOf(from)) { HashMap() }
            map[pkg] = (map[pkg] ?: 0L) + (to - from)
            from = to
        }
        // Solo se conservan hoy y los dos días anteriores.
        val newest = totals.keys.maxOrNull() ?: return
        totals.keys.removeAll { it < newest - 2 }
    }

    private fun creditInDay(startMs: Long, endMs: Long, day: Long): Long {
        var from = startMs
        var sum = 0L
        while (from < endMs) {
            val to = minOf(endMs, clock.nextDayStart(from))
            if (clock.dayOf(from) == day) sum += to - from
            from = to
        }
        return sum
    }
}
