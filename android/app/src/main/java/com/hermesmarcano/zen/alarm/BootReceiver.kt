package com.hermesmarcano.zen.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reprograma las alarmas cuando el sistema las olvida: reinicio, cambio de hora/zona, actualización. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> AlarmActions.restoreAll(context)
        }
    }
}
