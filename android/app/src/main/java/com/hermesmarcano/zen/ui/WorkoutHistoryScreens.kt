package com.hermesmarcano.zen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesmarcano.zen.core.ExerciseLibrary
import com.hermesmarcano.zen.core.Metric
import com.hermesmarcano.zen.core.WorkoutNumbers
import com.hermesmarcano.zen.core.WorkoutOps
import com.hermesmarcano.zen.core.WorkoutStats
import com.hermesmarcano.zen.core.WorkoutWeeks
import com.hermesmarcano.zen.data.ExerciseRef
import com.hermesmarcano.zen.data.SetType
import com.hermesmarcano.zen.data.WorkoutActions
import com.hermesmarcano.zen.data.WorkoutSession
import com.hermesmarcano.zen.zenApp
import java.io.IOException
import java.time.ZoneId

private fun sessionDay(s: WorkoutSession): Long = WorkoutWeeks.dayOf(s.finishedAt ?: s.startedAt, ZoneId.systemDefault())

/** "Press de banca · 60 kg × 8 · 60 kg × 8" para una sesión. */
private fun setsText(ex: com.hermesmarcano.zen.data.SessionExercise): String =
    ex.sets.joinToString(" · ") { (if (it.type == SetType.WARMUP) "C " else "") + WorkoutNumbers.describe(ex.ref.kind, it) + (it.rpe?.let { r -> " @${WorkoutNumbers.formatRpe(r)}" } ?: "") }

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("Atrás") }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
    }
}

/** Resumen de una sesión en una tarjeta (historial y pantalla principal). */
@Composable
fun SessionCard(session: WorkoutSession, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(session.routineName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                Text(dayLabel(sessionDay(session)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val volume = WorkoutOps.volumeKg(session)
            Text(
                buildList {
                    add(WorkoutNumbers.durationText(WorkoutOps.durationMinutes(session, 0)))
                    add("${WorkoutOps.doneSets(session)} series")
                    if (volume > 0) add(WorkoutNumbers.formatVolume(volume))
                    if (session.prs > 0) add(if (session.prs == 1) "1 marca" else "${session.prs} marcas")
                }.joinToString(" · "),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(session.exercises.joinToString(", ") { it.ref.name }, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 2)
        }
    }
}

@Composable
fun HistoryScreen(modifier: Modifier, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val data by LocalContext.current.zenApp.workouts.data.collectAsState()
    val sessions = remember(data.history) { data.history.sortedByDescending { it.finishedAt ?: it.startedAt } }
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader("Historial · ${sessions.size}", onBack)
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sessions.isEmpty()) item { Text("Aún no has terminado ningún entrenamiento.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(sessions, key = { it.id }) { SessionCard(it) { onOpen(it.id) } }
        }
    }
}

@Composable
fun SessionDetailScreen(
    sessionId: String,
    modifier: Modifier,
    onBack: () -> Unit,
    announce: (text: String, action: String?, onAction: (() -> Unit)?) -> Unit,
) {
    val workouts = LocalContext.current.zenApp.workouts
    val data by workouts.data.collectAsState()
    val session = data.history.firstOrNull { it.id == sessionId }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (session == null) {
        Column(modifier = modifier.fillMaxSize()) { ScreenHeader("Entrenamiento", onBack); Text("Este entrenamiento ya no existe.", modifier = Modifier.padding(16.dp)) }
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(session.routineName, onBack)
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    dayLabel(sessionDay(session)) + " · " + WorkoutNumbers.durationText(WorkoutOps.durationMinutes(session, 0)) + " · " + WorkoutOps.doneSets(session) + " series" +
                        (WorkoutOps.volumeKg(session).takeIf { it > 0 }?.let { " · " + WorkoutNumbers.formatVolume(it) } ?: "") +
                        if (session.prs > 0) " · ${session.prs} marcas personales" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.note.isNotBlank()) item { Text(session.note, fontSize = 14.sp) }
            itemsIndexed(session.exercises, key = { i, ex -> "$i-${ex.ref.id}" }) { _, ex ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(ex.ref.name, fontWeight = FontWeight.Bold)
                        Text(setsText(ex), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (ex.note.isNotBlank()) Text(ex.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            item { TextButton(onClick = { confirmDelete = true }) { Text("Eliminar este entrenamiento", color = MaterialTheme.colorScheme.error) } }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar el entrenamiento?") },
            text = { Text("Dejará de contar en tus marcas y gráficas. El XP ya ganado se conserva.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    try {
                        val removed = WorkoutActions.deleteSession(workouts, session.id)
                        onBack()
                        if (removed != null) announce("Entrenamiento eliminado", "Deshacer") { try { WorkoutActions.restoreSession(workouts, removed) } catch (_: IOException) { } }
                    } catch (_: IOException) {
                        announce("No se pudo guardar. Revisa el espacio del teléfono.", null, null)
                    }
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

/** Biblioteca: buscar, crear ejercicios propios y abrir las marcas y la gráfica de cada uno. */
@Composable
fun ExercisesScreen(modifier: Modifier, onBack: () -> Unit, onOpen: (ExerciseRef) -> Unit, announce: (String, String?, (() -> Unit)?) -> Unit) {
    val workouts = LocalContext.current.zenApp.workouts
    val data by workouts.data.collectAsState()
    var deleting by remember { mutableStateOf<ExerciseRef?>(null) }
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader("Ejercicios y marcas", onBack)
        ExerciseList(
            custom = data.customExercises,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
            onPick = onOpen,
            onCreate = { n, m, k -> try { WorkoutActions.addCustomExercise(workouts, n, m, k) } catch (_: IOException) { ExerciseLibrary.AddResult.Blank } },
            onDeleteCustom = { deleting = it },
        )
    }
    deleting?.let { ref ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Eliminar ${ref.name}?") },
            text = { Text("Se quita de la biblioteca. Tus rutinas y tu historial lo conservan.") },
            confirmButton = { TextButton(onClick = { deleting = null; try { WorkoutActions.deleteCustomExercise(workouts, ref.id) } catch (_: IOException) { announce("No se pudo guardar.", null, null) } }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun ExerciseDetailScreen(exerciseId: String, modifier: Modifier, onBack: () -> Unit) {
    val data by LocalContext.current.zenApp.workouts.data.collectAsState()
    val ref = remember(exerciseId, data.customExercises, data.history) {
        ExerciseLibrary.preset(exerciseId) ?: data.customExercises.firstOrNull { it.id == exerciseId }
            ?: data.history.asReversed().flatMap { s -> s.exercises.map { it.ref } }.firstOrNull { it.id == exerciseId }
    }
    if (ref == null) {
        Column(modifier = modifier.fillMaxSize()) { ScreenHeader("Ejercicio", onBack); Text("Este ejercicio ya no existe.", modifier = Modifier.padding(16.dp)) }
        return
    }
    val metrics = remember(ref) { Metric.forKind(ref.kind) }
    var metricId by rememberSaveable(exerciseId) { mutableStateOf(metrics.first().name) }
    val metric = metrics.firstOrNull { it.name == metricId } ?: metrics.first()
    val zone = ZoneId.systemDefault()
    val points = remember(data.history, exerciseId, metric) { WorkoutStats.series(data.history, exerciseId, metric, zone) }
    val sessions = remember(data.history, exerciseId) {
        data.history.filter { s -> s.exercises.any { it.ref.id == exerciseId } }.sortedByDescending { it.finishedAt ?: it.startedAt }.take(15)
    }
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(ref.name, onBack)
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(ref.muscle.title + " · " + ref.kind.title, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Mejores marcas", fontWeight = FontWeight.SemiBold)
                        val records = Metric.recordsFor(ref.kind).mapNotNull { m -> WorkoutStats.best(data.history, exerciseId, m)?.let { m to it } }
                        if (records.isEmpty()) Text("Aún sin marcas: haz este ejercicio en un entrenamiento.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        records.forEach { (m, v) ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(m.title, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${WorkoutNumbers.formatKg(v)} ${m.unit}", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (metrics.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                metrics.forEach { m -> FilterChip(selected = m == metric, onClick = { metricId = m.name }, label = { Text(m.title) }) }
                            }
                        } else Text(metric.title, fontWeight = FontWeight.SemiBold)
                        if (points.size < 2) Text("La gráfica aparece con 2 o más entrenamientos.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        else LineChart(points, metric.unit, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item { Text("Últimas veces", fontWeight = FontWeight.SemiBold) }
            if (sessions.isEmpty()) item { Text("Todavía no lo has hecho.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(sessions, key = { it.id }) { s ->
                // Un ejercicio puede aparecer varias veces en la misma sesión (p. ej. pesado y ligero): se muestran todas.
                Column {
                    Text(dayLabel(sessionDay(s)) + " · " + s.routineName, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    s.exercises.filter { it.ref.id == exerciseId }.forEach { Text(setsText(it), fontSize = 14.sp) }
                }
            }
        }
    }
}
