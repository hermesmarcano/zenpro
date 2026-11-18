package com.hermesmarcano.zen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesmarcano.zen.core.Achievement
import com.hermesmarcano.zen.core.Levels
import com.hermesmarcano.zen.data.WakeRecorder
import com.hermesmarcano.zen.zenApp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Nivel, rachas y logros. Sirve para ver el avance, no para presionar: nada se pierde ni caduca. */
@Composable
fun ProgressScreen(modifier: Modifier, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val state by context.zenApp.repository.state.collectAsState()
    val today = LocalDate.now()
    val progress = state.progress
    val level = Levels.progress(progress.xp)
    val unlocked = Achievement.entries.count { it.id in progress.achievements }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Progreso", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Ajustes") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Nivel ${level.level}", fontSize = 40.sp, fontWeight = FontWeight.Light)
                    LevelBar(level, Modifier.fillMaxWidth())
                    Text(
                        "Ganas XP al despertar en el lugar, cumplir tu prioritaria, completar tareas y entrenar. Hay un tope diario para las tareas y solo el primer entreno de cada día da XP.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Racha de despertar", daysText(WakeRecorder.currentStreak(state, today)))
                    Stat("Mejor racha de despertar", daysText(state.stats.bestStreak))
                    Stat("Racha de prioritaria", daysText(WakeRecorder.currentMitStreak(state, today)))
                    Stat("Mejor racha de prioritaria", daysText(progress.mitStreak.bestStreak))
                    Stat("Despertares verificados", state.stats.totalWakeups.toString())
                    Stat("Tareas completadas", progress.tasksCompleted.toString())
                    Stat("Días redondos", progress.perfectDays.toString())
                    Stat("Entrenamientos", progress.workouts.toString())
                    Stat("Volumen levantado", com.hermesmarcano.zen.core.WorkoutNumbers.formatVolume(progress.workoutVolumeKg.toDouble()))
                    Stat("Marcas personales", progress.personalRecords.toString())
                    Stat("Semanas cumpliendo el objetivo", progress.goalWeeks.toString())
                    Stat("Mejor racha semanal", if (progress.bestGoalWeekStreak == 1) "1 semana" else "${progress.bestGoalWeekStreak} semanas")
                }
            }
        }
        item {
            Text("Logros · $unlocked de ${Achievement.entries.size}", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
        }
        items(Achievement.entries.toList(), key = { it.id }) { achievement ->
            val day = progress.achievements[achievement.id]
            AchievementRow(achievement, day)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AchievementRow(achievement: Achievement, unlockedDay: Long?) {
    val unlocked = unlockedDay != null
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (unlocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (unlocked) Icons.Filled.Star else Icons.Filled.Lock,
                contentDescription = if (unlocked) "Desbloqueado" else "Bloqueado",
                tint = if (unlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    achievement.title,
                    fontWeight = FontWeight.SemiBold,
                    color = if (unlocked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    achievement.description,
                    fontSize = 13.sp,
                    color = if (unlocked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (unlockedDay != null) {
                    Text(
                        "Desbloqueado el " + LocalDate.ofEpochDay(unlockedDay).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale("es"))),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
