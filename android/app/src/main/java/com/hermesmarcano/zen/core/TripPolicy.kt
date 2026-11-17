package com.hermesmarcano.zen.core

import com.hermesmarcano.zen.data.DayRange
import com.hermesmarcano.zen.data.Trip

/** Reglas del modo viaje (pausa o ancla alternativo durante unos días). */
object TripPolicy {
    val DAY_OPTIONS = listOf(1, 2, 3, 5, 7, 14)
    const val MAX_DAYS = 30

    /** Un viaje de [days] días cuyo primer día es [startDay]. */
    fun start(startDay: Long, days: Int, anchorId: String?): Trip =
        Trip(untilEpochDay = startDay + days.coerceIn(1, MAX_DAYS) - 1, anchorId = anchorId)

    /**
     * Primer día que cuenta: el de la próxima alarma programada. Si el viaje se prepara por la noche,
     * "1 día" tiene que cubrir la alarma de mañana, no lo que queda de hoy (donde ya no suena nada).
     * Sin alarmas activas, o si la próxima ya venció, es hoy.
     */
    fun startDay(today: Long, nextFireAts: List<Long>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long {
        val first = nextFireAts.minOrNull() ?: return today
        val day = java.time.Instant.ofEpochMilli(first).atZone(zone).toLocalDate().toEpochDay()
        return maxOf(today, day)
    }

    fun isActive(trip: Trip?, today: Long): Boolean = trip != null && today <= trip.untilEpochDay

    /** Último día de pausa si hay una pausa activa; `null` si no. */
    fun pauseUntilDay(trip: Trip?, today: Long): Long? =
        if (trip != null && trip.isPause && today <= trip.untilEpochDay) trip.untilEpochDay else null

    /**
     * Ancla que se exige a una alarma hoy: el del viaje si hay uno activo (y existe), el propio si no.
     * Si el ancla del viaje se borró, se vuelve al de la alarma: nunca queda una alarma sin ancla.
     */
    fun effectiveAnchorId(alarmAnchorId: String?, trip: Trip?, today: Long, anchorExists: (String) -> Boolean): String? {
        val travel = trip?.anchorId
        return if (isActive(trip, today) && travel != null && anchorExists(travel)) travel else alarmAnchorId
    }

    fun remainingDays(trip: Trip, today: Long): Int = (trip.untilEpochDay - today + 1).toInt().coerceAtLeast(0)

    /** Días de pausa registrados + el viaje actual si es una pausa. */
    fun excusedDays(ranges: List<DayRange>): Set<Long> =
        ranges.flatMap { r -> (r.from..r.to).take(MAX_DAYS + 1) }.toSet()

    /** Registra una pausa nueva (se conservan las últimas 12). */
    fun addPausedRange(ranges: List<DayRange>, from: Long, to: Long): List<DayRange> =
        (ranges + DayRange(from, to)).takeLast(12)

    /** Al terminar una pausa antes de tiempo, los días que no llegaron a pasar vuelven a contar. */
    fun truncatePausedRanges(ranges: List<DayRange>, today: Long): List<DayRange> =
        ranges.mapNotNull { r ->
            when {
                r.from >= today -> null // la pausa aún no había empezado a dejar días atrás
                r.to >= today -> DayRange(r.from, today - 1)
                else -> r
            }
        }
}
