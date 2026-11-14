package com.hermesmarcano.zen.core

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Calcula cuándo debe sonar la alarma a partir de su hora, minuto y días de repetición. Con
 * `ZoneId` inyectable para probar días, medianoche y horario de verano sin teléfono.
 */
object NextFireCalculator {
    /**
     * Próxima fecha **estrictamente posterior** a [after] en que suena la alarma. `weekdays`
     * vacío = una sola vez: la próxima ocurrencia de hh:mm. Si esa hora no existe un día (salto
     * de horario de verano) se desplaza hacia adelante.
     */
    fun nextFire(after: ZonedDateTime, hour: Int, minute: Int, weekdays: Set<Weekday>): ZonedDateTime? {
        if (hour !in 0..23 || minute !in 0..59) return null
        val time = LocalTime.of(hour, minute)
        val startDate = after.toLocalDate()
        // Con al menos un día elegido, el siguiente cae dentro de 7 días.
        for (offset in 0L..7L) {
            val candidate = ZonedDateTime.of(startDate.plusDays(offset), time, after.zone)
            if (!candidate.isAfter(after)) continue
            val day = Weekday.fromIso(candidate.dayOfWeek.value)
            if (weekdays.isEmpty() || day in weekdays) return candidate
        }
        return null
    }

    fun nextFireMillis(
        afterMillis: Long,
        hour: Int,
        minute: Int,
        weekdays: Set<Weekday>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val after = java.time.Instant.ofEpochMilli(afterMillis).atZone(zone)
        return nextFire(after, hour, minute, weekdays)?.toInstant()?.toEpochMilli()
    }
}
