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
import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.hermesmarcano.zen.alarm.AlarmRuntime
import com.hermesmarcano.zen.alarm.CompletionPlayer
import com.hermesmarcano.zen.core.StreakPolicy
import com.hermesmarcano.zen.core.Levels
import com.hermesmarcano.zen.core.TaskBoard
import com.hermesmarcano.zen.data.GameEngine
import com.hermesmarcano.zen.data.GameEvents
import com.hermesmarcano.zen.zenApp
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import com.hermesmarcano.zen.alarm.AlarmService

/**
 * Pantalla que aparece al sonar la alarma, también con el teléfono bloqueado. No hay botón de
 * apagar ni "atrás": solo verificar el ancla (o el código de respaldo tras la espera) detiene el sonido.
 */
class RingingActivity : ComponentActivity() {
    private val completion = MutableStateFlow<Completion?>(null)
    private var completionPlayer: CompletionPlayer? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

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
                BackHandler(enabled = true) {
                    // Con la alarma ya apagada y la pantalla de éxito abierta, atrás cierra; mientras suena, no.
                    if (completion.value != null) finish()
                }
                val done by completion.collectAsState()
                Surface(color = MaterialTheme.colorScheme.background) {
                    val result = done
                    if (result != null) {
                        CompletionScreen(result, onContinue = ::finish, onOpenTasks = ::openTasks)
                    } else {
                        RingingScreen(onSolved = ::stopAlarm, onNothingRinging = ::finish)
                    }
                }
            }
        }
    }

    /** Del éxito a la lista de tareas: lo siguiente después de levantarse. */
    private fun openTasks() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_TODAY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        completionPlayer?.stop()
        super.onDestroy()
    }

    private fun stopAlarm(method: SolveMethod) {
        val ring = AlarmRuntime.ring.value
        try {
            startService(AlarmService.stopIntent(this))
        } catch (e: IllegalStateException) {
            // Si el sistema rechaza la orden, el sonido sigue: no se da por apagada ni se cierra la pantalla.
            android.util.Log.e("RingingActivity", "No se pudo detener el servicio", e)
            return
        }
        val solveSeconds = ring?.let { (SystemClock.elapsedRealtime() / 1000.0 - it.startedAtElapsed).toInt() } ?: 0
        var result = Completion(method, solveSeconds, 0, 0, 0, streakGrew = false, newBest = false, milestone = null)

        if (method == SolveMethod.ANCHOR) {
            val repository = zenApp.repository
            val before = repository.state.value.stats
            val today = LocalDate.now()
            try {
                var events = GameEvents()
                val updated = repository.update { state ->
                    val outcome = GameEngine.recordWake(state, today, solveSeconds)
                    events = outcome.events
                    outcome.state
                }
                val stats = updated.stats
                val todays = TaskBoard.forDay(updated.tasks, today.toEpochDay())
                result = Completion(
                    method = method,
                    solveSeconds = solveSeconds,
                    streak = stats.currentStreak,
                    bestStreak = stats.bestStreak,
                    totalWakeups = stats.totalWakeups,
                    streakGrew = stats.lastWakeDay != before.lastWakeDay, // el segundo despertar del día no cuenta dos veces
                    newBest = stats.bestStreak > before.bestStreak,
                    milestone = StreakPolicy.milestone(stats.currentStreak),
                    events = events,
                    level = Levels.progress(updated.progress.xp),
                    mitTitle = todays.firstOrNull()?.takeIf { !it.done }?.title,
                    mitDone = todays.firstOrNull()?.done == true,
                )
            } catch (e: IOException) {
                // El despertar ya ocurrió; no se pierde la pantalla por no poder guardar la racha.
                android.util.Log.e("RingingActivity", "No se pudo guardar la racha", e)
            }
            // Unos instantes después: al parar, el servicio devuelve el volumen de alarmas que tenía el usuario, y
            // si el sonido ya estuviera sonando se notaría el salto de volumen en mitad del "tirín".
            val player = CompletionPlayer(this).also { completionPlayer = it }
            val sound = repository.state.value.settings.completionSound
            handler.postDelayed({ if (!isFinishing && !isDestroyed) player.play(sound) }, COMPLETION_DELAY_MS)
        }

        // La pantalla de éxito se fija ANTES de soltar el estado compartido: si no, RingingScreen vería
        // "nada sonando" y se cerraría sola. Soltarlo aquí evita además que MainActivity, al
        // reanudarse, vuelva a abrir esta pantalla.
        completion.value = result
        AlarmRuntime.end()
        // Ya no hace falta tener la pantalla encendida por la fuerza (el usuario puede dejarla ahí).
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private companion object {
        const val COMPLETION_DELAY_MS = 350L
    }
}
