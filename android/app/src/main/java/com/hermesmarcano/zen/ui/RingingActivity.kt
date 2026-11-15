package com.hermesmarcano.zen.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.hermesmarcano.zen.alarm.AlarmRuntime
import com.hermesmarcano.zen.alarm.AlarmService

/**
 * Pantalla que aparece al sonar la alarma, también con el teléfono bloqueado. No hay botón de
 * apagar ni "atrás": solo verificar el ancla (o el código de respaldo tras la espera) detiene el sonido.
 */
class RingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            ZenTheme {
                // Tragarse "atrás": salir de la pantalla no debe ser una forma de acallar la alarma.
                BackHandler(enabled = true) {}
                Surface(color = MaterialTheme.colorScheme.background) {
                    RingingScreen(onSolved = ::stopAlarm, onNothingRinging = ::finish)
                }
            }
        }
    }

    private fun stopAlarm() {
        try {
            startService(AlarmService.stopIntent(this))
        } catch (e: IllegalStateException) {
            // Si el sistema rechaza la orden, el sonido sigue: no se da por apagada ni se cierra la pantalla.
            android.util.Log.e("RingingActivity", "No se pudo detener el servicio", e)
            return
        }
        // Ya verificado: soltar el estado compartido aquí evita que MainActivity, al reanudarse, vuelva a abrir esta pantalla.
        AlarmRuntime.end()
        finish()
    }
}
