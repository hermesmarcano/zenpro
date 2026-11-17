package com.hermesmarcano.zen.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import com.hermesmarcano.zen.core.GameMessages
import com.hermesmarcano.zen.core.Levels
import com.hermesmarcano.zen.data.GameEvents
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesmarcano.zen.core.StreakPolicy

enum class SolveMethod {
    /** Llegó al ancla (QR o foto): suma a la racha. */
    ANCHOR,

    /** Código de respaldo o alarma sin ancla: no suma ni rompe la racha. */
    EMERGENCY,
}

data class Completion(
    val method: SolveMethod,
    val solveSeconds: Int,
    val streak: Int,
    val bestStreak: Int,
    val totalWakeups: Int,
    /** La racha subió con este despertar (no si fue el segundo del día). */
    val streakGrew: Boolean,
    val newBest: Boolean,
    val milestone: String?,
    /** Lo que ganó con este despertar (XP, nivel, logros). */
    val events: GameEvents = GameEvents(),
    /** Nivel y progreso tras el despertar. */
    val level: Levels.LevelProgress? = null,
    /** Tarea prioritaria de hoy (la primera de la lista) si hay y no está hecha. */
    val mitTitle: String? = null,
    /** El usuario ya completó su prioritaria de hoy. */
    val mitDone: Boolean = false,
)

fun daysText(days: Int): String = if (days == 1) "1 día" else "$days días"

/** Pantalla tras apagar la alarma: sobria, con el dato que importa (la racha) y nada de adornos. */
@Composable
fun CompletionScreen(completion: Completion, onContinue: () -> Unit, onOpenTasks: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (completion.method == SolveMethod.ANCHOR) {
            Text("Despierto.", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Llegaste en ${StreakPolicy.solveTimeText(completion.solveSeconds)}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Text(daysText(completion.streak), fontSize = 56.sp, fontWeight = FontWeight.Light)
            Text(if (completion.streak == 1) "de racha" else "de racha seguidos", color = MaterialTheme.colorScheme.onSurfaceVariant)
            completion.milestone?.takeIf { completion.streakGrew }?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            val detail = if (completion.newBest && completion.streak > 1) {
                "Es tu mejor racha."
            } else {
                "Mejor racha: ${daysText(completion.bestStreak)} · Despertares: ${completion.totalWakeups}"
            }
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)

            // Lo ganado: una línea por cosa, sin fuegos artificiales.
            val gains = GameMessages.wakeSummary(completion.events)
            if (gains.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                gains.forEach { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
            }
            completion.level?.let { level ->
                Spacer(Modifier.height(12.dp))
                LevelBar(level, modifier = Modifier.fillMaxWidth())
            }

            // La tarea importante de hoy: lo siguiente que hacer, ya.
            Spacer(Modifier.height(24.dp))
            MitCard(completion, onOpenTasks)
        } else {
            Text("Alarma apagada.", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Sin verificar el lugar: no suma a tu racha, pero tampoco la rompe.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(40.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Empezar el día", fontSize = 18.sp) }
    }
}

@Composable
private fun MitCard(completion: Completion, onOpenTasks: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                completion.mitDone -> {
                    Text("Tu tarea prioritaria de hoy ya está hecha.", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onOpenTasks) { Text("Ver mis tareas") }
                }
                completion.mitTitle != null -> {
                    Text("Lo primero de hoy", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text(completion.mitTitle, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    TextButton(onClick = onOpenTasks) { Text("Ir a mis tareas") }
                }
                else -> {
                    Text("Aún no has elegido tu tarea prioritaria de hoy.", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onOpenTasks) { Text("Elegirla ahora") }
                }
            }
        }
    }
}

/** Barra de nivel: "Nivel 3 · Enfocado" y cuánto falta para el siguiente. */
@Composable
fun LevelBar(level: Levels.LevelProgress, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Nivel ${level.level} · ${level.title}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${level.xpIntoLevel} / ${level.xpForNext} XP", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        val fraction by animateFloatAsState(level.fraction, label = "nivel")
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
