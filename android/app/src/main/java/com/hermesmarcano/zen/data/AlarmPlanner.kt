package com.hermesmarcano.zen.data

import com.hermesmarcano.zen.core.NextFireCalculator
import java.time.ZoneId

/**
 * Decisiones de programación de alarmas, puras (el reloj y la zona se inyectan) para probarlas
 * sin teléfono. El código Android solo ejecuta el resultado.
 */
object AlarmPlanner {
    /** Tolerancia al recibir un disparo ligeramente antes de la hora programada (redondeos del sistema). */
    const val EARLY_TOLERANCE_MS = 60_000L

    /** Activa la alarma y fija su próximo disparo. */
    fun enable(alarm: AlarmEntity, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): AlarmEntity {
        val next = NextFireCalculator.nextFireMillis(nowMillis, alarm.hour, alarm.minute, alarm.weekdays, zone)
        return alarm.copy(enabled = next != null, nextFireAt = next)
    }

    fun disable(alarm: AlarmEntity): AlarmEntity = alarm.copy(enabled = false, nextFireAt = null)

    /**
     * Tras reinicio, cambio de hora/zona o actualización de la app. Las repetitivas se recalculan
     * desde ahora; una sola vez conserva su instante y, si ya pasó (teléfono apagado), se
     * desactiva: sonar al día siguiente a una hora que el usuario ya no esperaba sería peor.
     */
    fun restore(alarm: AlarmEntity, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): AlarmEntity {
        if (!alarm.enabled) return alarm.copy(nextFireAt = null)
        if (alarm.isRepeating) return enable(alarm, nowMillis, zone)
        val at = alarm.nextFireAt
        return if (at != null && at > nowMillis) alarm else disable(alarm)
    }

    /** ¿Debe sonar un disparo recibido ahora? Descarta PendingIntents obsoletos de ediciones previas. */
    fun shouldRing(alarm: AlarmEntity?, nowMillis: Long): Boolean {
        if (alarm == null || !alarm.enabled) return false
        val at = alarm.nextFireAt ?: return false
        return nowMillis >= at - EARLY_TOLERANCE_MS
    }

    /** Estado de la alarma tras haber sonado: repetitiva → siguiente ocurrencia; única → desactivada. */
    fun afterFiring(alarm: AlarmEntity, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): AlarmEntity =
        if (alarm.isRepeating) enable(alarm, maxOf(nowMillis, alarm.nextFireAt ?: nowMillis), zone) else disable(alarm)
}
