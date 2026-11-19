package com.hermesmarcano.zen.layer.capture

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.hermesmarcano.zen.layer.core.AccessibilitySetting
import com.hermesmarcano.zen.layer.core.HealDecision
import com.hermesmarcano.zen.layer.core.SelfHealPolicy
import java.io.IOException

/**
 * Mantiene vivo el servicio de accesibilidad. Xiaomi puede detenerlo dejando el interruptor de Ajustes en "activado";
 * si el usuario concedió una sola vez el permiso `WRITE_SECURE_SETTINGS` por ADB, la capa lo apaga y lo enciende sola.
 * Sin ese permiso solo puede avisar.
 *
 * Las decisiones las toma [SelfHealPolicy] (con espera de gracia y tope de reparaciones, para no entrar en bucle);
 * aquí solo se leen y escriben los ajustes.
 */
object AccessibilityHealer {
    private const val NOTICE_EVERY_MS = 6 * 60 * 60_000L
    private const val RESTART_GAP_MS = 1_500L

    fun component(context: Context): String = ComponentName(context, LayerAccessibilityService::class.java).flattenToString()

    fun isListed(context: Context): Boolean =
        AccessibilitySetting.contains(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), component(context))

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Corre en el mantenimiento periódico (en el hilo de E/S: puede dormir un instante al reiniciar). */
    fun run(context: Context, nowMs: Long = System.currentTimeMillis()): HealDecision {
        val runtime = context.layer
        val s = runtime.state.current
        val wanted = s.capture.screenText || s.limits.isNotEmpty() || s.blocks.isNotEmpty()
        val canWrite = canWriteSecureSettings(context)
        val decision = SelfHealPolicy.decide(
            wanted = wanted, listed = isListed(context), connected = LayerLive.accessibilityConnected, canWriteSecure = canWrite,
            nowMs = nowMs, downSinceMs = s.accessibilityDownSinceMs, recentRepairsMs = s.repairsMs,
        )
        try {
            when (decision) {
                HealDecision.Healthy -> if (s.accessibilityDownSinceMs != null) runtime.state.update { it.copy(accessibilityDownSinceMs = null) }
                is HealDecision.Wait -> if (decision.markDownAtMs != s.accessibilityDownSinceMs) {
                    runtime.state.update { it.copy(accessibilityDownSinceMs = decision.markDownAtMs) }
                }
                HealDecision.Enable -> {
                    write(context, AccessibilitySetting.withEnabled(current(context), component(context)))
                    recordRepair(runtime, nowMs)
                }
                HealDecision.Restart -> {
                    val component = component(context)
                    write(context, AccessibilitySetting.without(current(context), component))
                    // Un instante apagado para que el sistema suelte el servicio colgado antes de volver a enlazarlo.
                    Thread.sleep(RESTART_GAP_MS)
                    write(context, AccessibilitySetting.withEnabled(current(context), component))
                    recordRepair(runtime, nowMs)
                }
                is HealDecision.NeedUser -> tellUser(runtime, decision.reason, nowMs)
            }
        } catch (_: IOException) {
        } catch (_: SecurityException) {
            // El permiso se retiró entre la comprobación y la escritura.
        }
        return decision
    }

    private fun current(context: Context): String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

    private fun write(context: Context, value: String) {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    private fun recordRepair(runtime: LayerRuntime, nowMs: Long) {
        runtime.state.update { it.copy(repairsMs = SelfHealPolicy.recordRepair(it.repairsMs, nowMs), accessibilityDownSinceMs = null) }
        runtime.runIo { runtime.pipeline.onSystem(com.hermesmarcano.zen.layer.core.LayerEvent.Kind.SYSTEM, "Servicio de accesibilidad reparado", "") }
    }

    /** Un solo aviso cada 6 horas: no hay que insistir con algo que el usuario ya sabe. */
    private fun tellUser(runtime: LayerRuntime, reason: HealDecision.Reason, nowMs: Long) {
        val s = runtime.state.current
        if (nowMs - s.lastRepairNoticeAtMs in 0 until NOTICE_EVERY_MS) return
        runtime.state.update { it.copy(lastRepairNoticeAtMs = nowMs) }
        val (title, text) = when (reason) {
            HealDecision.Reason.NOT_ENABLED ->
                "Accesibilidad apagada" to "Sin el servicio de accesibilidad no se leen la pantalla ni se aplican los límites. Actívalo en Ajustes → Accesibilidad → ZEN."
            HealDecision.Reason.NOT_RESPONDING ->
                "La accesibilidad no responde" to "El sistema detuvo el servicio. Desactívalo y vuelve a activarlo en Ajustes → Accesibilidad → ZEN."
            HealDecision.Reason.REPAIR_LIMIT ->
                "La accesibilidad sigue cayéndose" to "Ya se intentó reparar varias veces. Revisa el ahorro de batería e inicio automático de ZEN."
        }
        runtime.notifier.needsAttention(title, text)
    }
}
