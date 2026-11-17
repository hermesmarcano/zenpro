package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.NextFireCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Decisiones de programación de alarmas, puras (el reloj y la zona se inyectan) para probarlas
 * sin teléfono. El código Android solo ejecuta el resultado.
 */
object AlarmPlanner {
    /** Tolerancia al recibir un disparo ligeramente antes de la hora programada (redondeos del sistema). */
    const val EARLY_TOLERANCE_MS = 60_000L

    /**
     * Una alarma que venció hace muy poco (el aviso del sistema aún no se procesó, o el teléfono
     * arrancó minutos tarde) se conserva para que suene ya, en vez de saltarla hasta mañana.
     * Pasado este margen se considera perdida.
     */
    const val LATE_GRACE_MS = 5 * 60_000L

    private fun dayOf(millis: Long, zone: ZoneId): Long = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /**
     * Activa la alarma y fija su próximo disparo. Con [pauseUntilDay] (modo viaje: pausa) las repetitivas
     * saltan al primer día posterior a la pausa y las de una sola vez que caerían dentro se omiten
     * (quedan desactivadas, no se aplazan: "mañana a las 6" no debe pasar a sonar dentro de una semana).
     */
    fun enable(alarm: AlarmEntity, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault(), pauseUntilDay: Long? = null): AlarmEntity {
        val natural = NextFireCalculator.nextFireMillis(nowMillis, alarm.hour, alarm.minute, alarm.weekdays, zone)
        if (pauseUntilDay == null || natural == null || dayOf(natural, zone) > pauseUntilDay) {
            return alarm.copy(enabled = natural != null, nextFireAt = natural)
        }
        if (!alarm.isRepeating) return disable(alarm)
        val endOfPause = LocalDate.ofEpochDay(pauseUntilDay + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val next = NextFireCalculator.nextFireMillis(maxOf(nowMillis, endOfPause), alarm.hour, alarm.minute, alarm.weekdays, zone)
        return alarm.copy(enabled = next != null, nextFireAt = next)
    }

    fun disable(alarm: AlarmEntity): AlarmEntity = alarm.copy(enabled = false, nextFireAt = null)

    /**
     * Tras reinicio, cambio de hora/zona o actualización de la app. Las repetitivas se recalculan
     * desde ahora; una sola vez conserva su instante y, si ya pasó (teléfono apagado), se
     * desactiva: sonar al día siguiente a una hora que el usuario ya no esperaba sería peor.
     * Excepción: vencidas hace menos de [LATE_GRACE_MS] se conservan para sonar de inmediato.
     */
    fun restore(alarm: AlarmEntity, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault(), pauseUntilDay: Long? = null): AlarmEntity {
        if (!alarm.enabled) return alarm.copy(nextFireAt = null)
        val at = alarm.nextFireAt
        // En pausa (modo viaje) nada vence "ya": todo se recalcula contra la pausa.
        val inPause = pauseUntilDay != null && at != null && dayOf(at, zone) <= pauseUntilDay
        if (pauseUntilDay != null && (inPause || alarm.isRepeating)) return enable(alarm, nowMillis, zone, pauseUntilDay)
        if (at != null && at <= nowMillis && nowMillis - at <= LATE_GRACE_MS) return alarm // vence ya
        if (alarm.isRepeating) return enable(alarm, nowMillis, zone)
        return if (at != null && at > nowMillis) alarm else disable(alarm)
    }

    /** ¿Debe sonar un disparo recibido ahora? Descarta PendingIntents obsoletos de ediciones previas. */
    fun shouldRing(alarm: AlarmEntity?, nowMillis: Long, pauseUntilDay: Long? = null, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (alarm == null || !alarm.enabled) return false
        // Un aviso que llega durante una pausa (programado antes de pausar) no debe sonar.
        if (pauseUntilDay != null && dayOf(nowMillis, zone) <= pauseUntilDay) return false
        val at = alarm.nextFireAt ?: return false
        return nowMillis >= at - EARLY_TOLERANCE_MS
    }

    /** Estado de la alarma tras haber sonado: repetitiva → siguiente ocurrencia; única → desactivada. */
    fun afterFiring(alarm: AlarmEntity, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault(), pauseUntilDay: Long? = null): AlarmEntity =
        if (alarm.isRepeating) enable(alarm, maxOf(nowMillis, alarm.nextFireAt ?: nowMillis), zone, pauseUntilDay) else disable(alarm)
}
