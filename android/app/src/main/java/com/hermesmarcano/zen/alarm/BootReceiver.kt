package com.hermesmarcano.zen.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reprograma las alarmas cuando el sistema las olvida: reinicio, cambio de hora/zona, actualización. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "reinicio del teléfono"
            Intent.ACTION_TIME_CHANGED -> "cambio de hora"
            Intent.ACTION_TIMEZONE_CHANGED -> "cambio de zona horaria"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "actualización de ZEN"
            else -> return
        }
        AlarmActions.restoreAll(context, reason)
    }
}
