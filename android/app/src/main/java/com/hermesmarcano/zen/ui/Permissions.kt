package com.hermesmarcano.zen.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Qué permisos necesita ZEN y cuáles faltan. Sin ellos la alarma puede sonar, pero no se puede apagar bien. */
object Permissions {
    fun hasCamera(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Android 14+: sin este permiso la pantalla de apagado no se abre sola con el teléfono bloqueado. */
    fun hasFullScreenIntent(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun fullScreenIntentSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        } else {
            appSettings(context)
        }

    fun appSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun batterySettings(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** ¿Android ya deja a ZEN trabajar en segundo plano sin ahorro de batería? */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Diálogo del sistema "¿Permitir que ZEN funcione siempre en segundo plano?". */
    @android.annotation.SuppressLint("BatteryLife") // Una app de alarmas es el caso de uso que el sistema contempla; ZEN va por APK, no por Google Play.
    fun requestIgnoreBatteryOptimizations(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** Xiaomi, Redmi y POCO (MIUI/HyperOS) retrasan o bloquean las alarmas de apps que no estén exentas. */
    fun isXiaomiFamily(): Boolean = Build.MANUFACTURER.lowercase(java.util.Locale.ROOT) in setOf("xiaomi", "redmi", "poco") ||
        Build.BRAND.lowercase(java.util.Locale.ROOT) in setOf("xiaomi", "redmi", "poco")

    /** Pantalla "Inicio automático" de MIUI/HyperOS. Puede no existir en otras versiones: quien la use debe capturar el fallo. */
    fun xiaomiAutostart(): Intent =
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))

    /** Ahorro de batería por app de MIUI/HyperOS ("Sin restricciones"). */
    fun xiaomiBatterySaver(context: Context): Intent =
        Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", "ZEN")
}
