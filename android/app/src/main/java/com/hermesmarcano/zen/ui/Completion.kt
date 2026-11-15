package com.hermesmarcano.zen.ui

import androidx.compose.foundation.layout.Arrangement
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
)

fun daysText(days: Int): String = if (days == 1) "1 día" else "$days días"

/** Pantalla tras apagar la alarma: sobria, con el dato que importa (la racha) y nada de adornos. */
@Composable
fun CompletionScreen(completion: Completion, onContinue: () -> Unit) {
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
