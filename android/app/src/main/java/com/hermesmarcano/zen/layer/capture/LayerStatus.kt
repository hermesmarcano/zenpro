package com.hermesmarcano.zen.layer.capture

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hermesmarcano.zen.layer.core.HealthInput
import com.hermesmarcano.zen.ui.Permissions

/** Lee del teléfono los hechos que alimentan el chequeo de salud ([com.hermesmarcano.zen.layer.core.LayerHealth]). */
object LayerStatus {
    fun isXiaomi(): Boolean =
        Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) || Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** El paquete del lanzador por defecto (pantalla de inicio): nunca debe limitarse ni bloquearse. */
    fun homePackage(context: Context): String? = try {
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    } catch (_: Exception) {
        null
    }

    fun isListenerEnabled(context: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun hasMicrophone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isBatteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun healthInput(context: Context, nowMs: Long = System.currentTimeMillis()): HealthInput {
        val s = context.layer.state.current
        return HealthInput(
            settings = s.capture,
            hasLimits = s.limits.isNotEmpty() || s.blocks.isNotEmpty(),
            accessibilityListed = AccessibilityHealer.isListed(context),
            accessibilityConnected = LayerLive.accessibilityConnected,
            listenerEnabled = isListenerEnabled(context),
            listenerConnected = LayerLive.listenerConnected,
            micGranted = hasMicrophone(context),
            notificationsGranted = Permissions.hasNotifications(context),
            batteryUnrestricted = isBatteryUnrestricted(context),
            isXiaomi = isXiaomi(),
            autostartConfirmed = s.autostartConfirmed,
            secureSettingsGranted = AccessibilityHealer.canWriteSecureSettings(context),
            brainConfigured = s.brainConfigured,
            lastSyncOkAtMs = s.lastSyncOkAtMs,
            nowMs = nowMs,
        )
    }
}

/** Accesos directos a las pantallas de Ajustes. Las de Xiaomi son privadas y cambian entre versiones de MIUI: siempre hay un plan B. */
object LayerIntents {
    fun accessibility() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun notificationAccess() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /** Pide excluir a ZEN del ahorro de batería con el diálogo del sistema (el permiso está declarado en el manifiesto). */
    fun batteryExemption(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:${context.packageName}"))

    /** Inicio automático de Xiaomi: MIUI 12.5 lo tiene en la app Seguridad. */
    fun xiaomiAutostart() = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))

    /** Ahorro de batería de la app en MIUI ("Sin restricciones"). */
    fun xiaomiBattery(context: Context) = Intent()
        .setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
        .putExtra("package_name", context.packageName)
        .putExtra("package_label", "ZEN")

    /** Prueba cada intento en orden y se queda con el primero que existe. Devuelve `false` si ninguno abrió nada. */
    fun openFirst(context: Context, vararg candidates: Intent): Boolean {
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}
