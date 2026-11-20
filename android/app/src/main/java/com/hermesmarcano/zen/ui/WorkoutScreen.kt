package com.hermesmarcano.zen.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hermesmarcano.zen.core.GameMessages
import com.hermesmarcano.zen.core.Levels
import com.hermesmarcano.zen.core.Rotation
import com.hermesmarcano.zen.core.WorkoutCsv
import com.hermesmarcano.zen.core.WorkoutNumbers
import com.hermesmarcano.zen.core.WorkoutOps
import com.hermesmarcano.zen.core.WorkoutStats
import com.hermesmarcano.zen.core.WorkoutWeeks
import com.hermesmarcano.zen.core.XpRules
import com.hermesmarcano.zen.data.Routine
import com.hermesmarcano.zen.data.WorkoutActions
import com.hermesmarcano.zen.data.WorkoutData
import com.hermesmarcano.zen.data.WorkoutFinish
import com.hermesmarcano.zen.zenApp
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HOME = "home"
private const val ACTIVE = "active"
private const val EXERCISES = "exercises"
private const val HISTORY = "history"
private const val SUMMARY = "summary"
private const val ROUTINE = "routine:"
private const val EXERCISE = "exercise:"
private const val SESSION = "session:"
private const val NEW_ROUTINE = "routine:new"

/** Pestaña Entreno: la pantalla principal y sus subpantallas con una pila de navegación propia. */
@Composable
fun WorkoutScreen(
    modifier: Modifier,
    announce: (text: String, action: String?, onAction: (() -> Unit)?) -> Unit,
) {
    // La pila va en un texto ("home>history>session:ID") para sobrevivir a rotaciones y a que Android cierre la app.
    var stackText by rememberSaveable { mutableStateOf(HOME) }
    val stack = stackText.split(">")
    val route = stack.last()
    fun push(next: String) { stackText = "$stackText>$next" }
    fun pop() { stackText = if (stack.size <= 1) HOME else stack.dropLast(1).joinToString(">") }
    fun replaceTop(next: String) { stackText = (stack.dropLast(1) + next).joinToString(">") }
    var finish by remember { mutableStateOf<WorkoutFinish?>(null) }

    BackHandler(enabled = stack.size > 1) { pop() }

    when {
        route == ACTIVE -> ActiveWorkoutScreen(
            modifier = modifier,
            onBack = { pop() },
            onFinished = { finish = it; replaceTop(SUMMARY) },
            onEnded = { if (route == ACTIVE) pop() },
            announce = announce,
        )
        route == SUMMARY -> {
            val result = finish
            if (result == null) androidx.compose.runtime.LaunchedEffect(Unit) { stackText = HOME }
            else WorkoutSummaryScreen(result, modifier, onDone = { finish = null; stackText = HOME }, onOpenHistory = { stackText = "$HOME>$HISTORY" })
        }
        route == NEW_ROUTINE -> RoutineEditScreen(null, modifier, onClose = { pop() }, announce = announce)
        route.startsWith(ROUTINE) -> RoutineEditScreen(route.removePrefix(ROUTINE), modifier, onClose = { pop() }, announce = announce)
        route == EXERCISES -> ExercisesScreen(modifier, onBack = { pop() }, onOpen = { push(EXERCISE + it.id) }, announce = announce)
        route.startsWith(EXERCISE) -> ExerciseDetailScreen(route.removePrefix(EXERCISE), modifier, onBack = { pop() })
        route == HISTORY -> HistoryScreen(modifier, onBack = { pop() }, onOpen = { push(SESSION + it) })
        route.startsWith(SESSION) -> SessionDetailScreen(route.removePrefix(SESSION), modifier, onBack = { pop() }, announce = announce)
        else -> WorkoutHome(
            modifier = modifier,
            announce = announce,
            onOpenActive = { push(ACTIVE) },
            onNewRoutine = { push(NEW_ROUTINE) },
            onEditRoutine = { push(ROUTINE + it) },
            onOpenExercises = { push(EXERCISES) },
            onOpenHistory = { push(HISTORY) },
            onOpenSession = { push(SESSION + it) },
        )
    }
}

@Composable
private fun WorkoutHome(
    modifier: Modifier,
    announce: (text: String, action: String?, onAction: (() -> Unit)?) -> Unit,
    onOpenActive: () -> Unit,
    onNewRoutine: () -> Unit,
    onEditRoutine: (String) -> Unit,
    onOpenExercises: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val workouts = context.zenApp.workouts
    val data by workouts.data.collectAsState()
    val zen by context.zenApp.repository.state.collectAsState()

    // El día puede cambiar con la app abierta: se relee al volver y cada medio minuto.
    var dayTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { dayTick++ }
    val minute by produceState(System.currentTimeMillis() / 60_000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 60_000
        }
    }
    val zone = ZoneId.systemDefault()
    val today = remember(dayTick, minute) { LocalDate.now() }
    val week = WorkoutWeeks.weekIndex(today.toEpochDay())
    val thisWeek = remember(data.history, week) { WorkoutStats.weekSummary(data.history, week, zone) }
    val lastWeek = remember(data.history, week) { WorkoutStats.weekSummary(data.history, week - 1, zone) }
    val volumes = remember(data.history, week) { WorkoutStats.weeklyVolumes(data.history, week, 8, zone) }
    val next = remember(data.routines, data.history) { Rotation.next(data.routines, data.history) }
    val streak = WorkoutWeeks.displayGoalStreak(zen.progress, week)
    val active = data.active

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Routine?>(null) }

    fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            announce("No se pudo guardar. Revisa el espacio del teléfono.", null, null)
        }
    }

    fun start(routineId: String?) = safely {
        WorkoutActions.start(workouts, routineId, System.currentTimeMillis())
        onOpenActive()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Entreno", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, contentDescription = "Ajustes de entreno") }
            }
        }
        if (active != null) {
            item(key = "active") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(18.dp)) {
                    Row(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Entrenamiento en curso", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            val minutes = WorkoutOps.durationMinutes(active, System.currentTimeMillis())
                            Text(
                                active.routineName + " · " + WorkoutOps.doneSets(active) + " series hechas" + if (minutes >= STALE_SESSION_MINUTES) " · empezó hace ${WorkoutNumbers.durationText(minutes)}" else "",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Button(onClick = onOpenActive) { Text("Continuar") }
                    }
                }
            }
        }
        item(key = "week") {
            WeekCard(thisWeek, lastWeek, data.weeklyGoal, streak, volumes.map { it.second })
        }
        if (data.routines.isEmpty()) {
            item(key = "empty") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Tus rutinas", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Crea una rutina por día (Día 1, Día 2…). No tienen que coincidir con el día de la semana: ZEN te propone la que sigue.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                        Button(onClick = { safely { WorkoutActions.addStarterRoutines(workouts) } }, modifier = Modifier.fillMaxWidth()) { Text("Usar plantilla Empuje · Tirón · Piernas") }
                        OutlinedButton(onClick = onNewRoutine, modifier = Modifier.fillMaxWidth()) { Text("Crear mi rutina") }
                    }
                }
            }
        } else if (next != null) {
            item(key = "next") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Te toca", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(next.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(routineSummary(next), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "+${XpRules.WORKOUT} XP el primer entreno del día (mínimo ${WorkoutOps.MIN_QUALIFYING_SETS} series y ${WorkoutOps.MIN_QUALIFYING_MINUTES} min)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Button(onClick = { if (active != null) onOpenActive() else start(next.id) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Text(if (active != null) "Continuar el entrenamiento" else "Empezar")
                        }
                    }
                }
            }
        }
        if (data.routines.isNotEmpty()) {
            item(key = "routines-title") { Text("Rutinas", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp)) }
            val ordered = data.routines.sortedBy { it.position }
            ordered.forEachIndexed { index, routine ->
                item(key = "routine-${routine.id}") {
                    RoutineRow(
                        routine = routine,
                        isNext = routine.id == next?.id,
                        canStart = active == null,
                        canUp = index > 0,
                        canDown = index < ordered.lastIndex,
                        onStart = { start(routine.id) },
                        onEdit = { onEditRoutine(routine.id) },
                        onUp = { safely { WorkoutActions.moveRoutine(workouts, index, index - 1) } },
                        onDown = { safely { WorkoutActions.moveRoutine(workouts, index, index + 1) } },
                        onDelete = { deleting = routine },
                    )
                }
            }
        }
        item(key = "buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onNewRoutine, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Rutina", modifier = Modifier.padding(start = 4.dp))
                }
                OutlinedButton(onClick = { if (active != null) onOpenActive() else start(null) }, modifier = Modifier.weight(1f)) { Text("Entreno libre") }
            }
        }
        item(key = "history-title") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("Últimos entrenos", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                if (data.history.isNotEmpty()) TextButton(onClick = onOpenHistory) { Text("Ver todo") }
            }
        }
        val recent = data.history.sortedByDescending { it.finishedAt ?: it.startedAt }.take(3)
        if (recent.isEmpty()) item(key = "no-history") { Text("Cuando termines un entrenamiento aparecerá aquí.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        recent.forEach { s -> item(key = "recent-${s.id}") { SessionCard(s) { onOpenSession(s.id) } } }
        item(key = "exercises") {
            OutlinedButton(onClick = onOpenExercises, modifier = Modifier.fillMaxWidth()) { Text("Ejercicios y marcas personales") }
        }
    }

    if (showSettings) WorkoutSettingsDialog(data, announce) { showSettings = false }

    deleting?.let { routine ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Eliminar ${routine.name}?") },
            text = { Text("Tu historial no se toca.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    safely {
                        val removed = WorkoutActions.deleteRoutine(workouts, routine.id)
                        if (removed != null) announce("Rutina eliminada", "Deshacer") { safely { WorkoutActions.restoreRoutine(workouts, removed) } }
                    }
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

/** Si una sesión lleva tantas horas abierta, se avisa: probablemente se olvidó cerrarla. */
private const val STALE_SESSION_MINUTES = 180

private fun routineSummary(r: Routine): String {
    val sets = r.items.sumOf { it.sets }
    return if (r.items.isEmpty()) "Sin ejercicios todavía" else "${r.items.size} ejercicios · $sets series · " + r.items.take(3).joinToString(", ") { it.ref.name } + if (r.items.size > 3) "…" else ""
}

@Composable
private fun WeekCard(week: WorkoutStats.WeekSummary, previous: WorkoutStats.WeekSummary, goal: Int, streak: Int, volumes: List<Double>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Esta semana", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                GoalDots(week.daysTrained.coerceAtMost(goal), goal)
            }
            val met = week.daysTrained >= goal
            Text(
                "${week.daysTrained} de $goal días entrenados" + if (met) " · objetivo cumplido" else " · +${XpRules.WEEKLY_GOAL} XP al cumplirlo",
                color = if (met) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            if (streak > 0) Text(if (streak == 1) "Racha semanal: 1 semana cumpliendo el objetivo" else "Racha semanal: $streak semanas seguidas cumpliendo el objetivo", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Mini("Series", week.sets.toString())
                Mini("Volumen", WorkoutNumbers.formatVolume(week.volumeKg))
                Mini("Tiempo", WorkoutNumbers.durationText(week.minutes))
                if (week.records > 0) Mini("Marcas", week.records.toString())
            }
            if (previous.volumeKg > 0 && week.volumeKg > 0) {
                val delta = ((week.volumeKg / previous.volumeKg - 1.0) * 100.0).roundToInt()
                Text(
                    if (delta >= 0) "Volumen +$delta % respecto a la semana pasada" else "Volumen $delta % respecto a la semana pasada",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (volumes.any { it > 0 }) {
                BarChart(volumes, description = "Volumen de las últimas ${volumes.size} semanas; la última es la actual")
                Text("Volumen por semana · últimas ${volumes.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun Mini(label: String, value: String) {
    Column {
        Text(value, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RoutineRow(
    routine: Routine,
    isNext: Boolean,
    canStart: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isNext) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(routine.name + if (isNext) " · siguiente" else "", fontWeight = FontWeight.Bold)
                Text(routineSummary(routine), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            TextButton(onClick = onStart, enabled = canStart) { Text("Empezar") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Opciones de ${routine.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Editar") }, onClick = { menu = false; onEdit() })
                    if (canUp) DropdownMenuItem(text = { Text("Subir") }, onClick = { menu = false; onUp() })
                    if (canDown) DropdownMenuItem(text = { Text("Bajar") }, onClick = { menu = false; onDown() })
                    DropdownMenuItem(text = { Text("Eliminar") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Resumen al terminar: duración, volumen, marcas y todo lo que dio el juego (XP, nivel, logros, objetivo). */
@Composable
fun WorkoutSummaryScreen(result: WorkoutFinish, modifier: Modifier, onDone: () -> Unit, onOpenHistory: () -> Unit) {
    val zen by LocalContext.current.zenApp.repository.state.collectAsState()
    val s = result.session
    val messages = GameMessages.describe(result.events)
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(if (result.qualified) "Entrenamiento completado" else "Entrenamiento guardado", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(s.routineName, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Mini("Duración", WorkoutNumbers.durationText(WorkoutOps.durationMinutes(s, 0)))
                    Mini("Series", WorkoutOps.doneSets(s).toString())
                    WorkoutOps.volumeKg(s).takeIf { it > 0 }?.let { Mini("Volumen", WorkoutNumbers.formatVolume(it)) }
                }
            }
        }
        if (result.records.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Marcas personales", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        result.records.forEach { r ->
                            Text("${r.ref.name} · ${r.metrics.joinToString(", ") { it.title.lowercase() }}", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!result.qualified) {
                        Text(
                            "No suma XP ni objetivo semanal: hacen falta ${WorkoutOps.MIN_QUALIFYING_SETS} series de trabajo y ${WorkoutOps.MIN_QUALIFYING_MINUTES} minutos. Queda guardado en tu historial.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                    } else {
                        messages.forEach { Text(it, fontWeight = FontWeight.SemiBold) }
                        if (result.xpNotSaved) Text("No se pudo guardar el XP de este entrenamiento (revisa el espacio del teléfono). El entrenamiento sí está en tu historial.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        else if (result.events.xpGained == 0) Text("Hoy ya sumaste XP por entrenar; este cuenta para tu historial y tu semana.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${result.daysThisWeek} de ${result.weeklyGoal} días esta semana", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            GoalDots(result.daysThisWeek.coerceAtMost(result.weeklyGoal), result.weeklyGoal)
                        }
                        LevelBar(Levels.progress(zen.progress.xp), Modifier.fillMaxWidth())
                    }
                }
            }
        }
        item {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Listo") }
            TextButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) { Text("Ver historial") }
        }
    }
}

@Composable
private fun WorkoutSettingsDialog(data: WorkoutData, announce: (String, String?, (() -> Unit)?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val workouts = context.zenApp.workouts
    val scope = rememberCoroutineScope()

    fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            announce("No se pudo guardar. Revisa el espacio del teléfono.", null, null)
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val csv = WorkoutCsv.export(workouts.data.value.history, ZoneId.systemDefault())
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } != null
                } catch (_: Exception) {
                    false
                }
            }
            announce(if (ok) "Historial exportado" else "No se pudo exportar el archivo.", null, null)
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { readText(context, uri) }
            if (text == null) { announce("No se pudo leer el archivo (o es demasiado grande).", null, null); return@launch }
            try {
                val (added, skipped, problem) = WorkoutActions.importCsv(workouts, text)
                announce(
                    problem ?: (if (added == 0) "No había entrenamientos nuevos" else if (added == 1) "Importado 1 entrenamiento" else "Importados $added entrenamientos") +
                        if (skipped > 0) " · $skipped filas descartadas" else "",
                    null, null,
                )
            } catch (_: IOException) {
                announce("No se pudo guardar. Revisa el espacio del teléfono.", null, null)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustes de entreno") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Objetivo semanal", modifier = Modifier.weight(1f))
                    Stepper(
                        "días", "${data.weeklyGoal} días",
                        onMinus = { safely { WorkoutActions.setWeeklyGoal(workouts, data.weeklyGoal - 1) } },
                        onPlus = { safely { WorkoutActions.setWeeklyGoal(workouts, data.weeklyGoal + 1) } },
                        minusEnabled = data.weeklyGoal > WorkoutData.MIN_WEEKLY_GOAL, plusEnabled = data.weeklyGoal < WorkoutData.MAX_WEEKLY_GOAL,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Descanso al añadir ejercicios", modifier = Modifier.weight(1f))
                    Stepper(
                        "descanso", if (data.defaultRestSeconds == 0) "Sin" else WorkoutNumbers.clock(data.defaultRestSeconds),
                        onMinus = { safely { WorkoutActions.setDefaultRest(workouts, data.defaultRestSeconds - WorkoutOps.REST_STEP_SECONDS) } },
                        onPlus = { safely { WorkoutActions.setDefaultRest(workouts, data.defaultRestSeconds + WorkoutOps.REST_STEP_SECONDS) } },
                        minusEnabled = data.defaultRestSeconds > 0, plusEnabled = data.defaultRestSeconds < WorkoutOps.MAX_REST_SECONDS,
                    )
                }
                Text("Las rutinas guardan su propio descanso; esto solo es para ejercicios nuevos. Unidades: kg.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Copia de seguridad", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text(
                    "ZEN no usa la copia automática de Android. Exporta tu historial a un archivo CSV (abre en cualquier hoja de cálculo) y guárdalo donde quieras.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { export.launch("zen-entrenos.csv") }, enabled = data.history.isNotEmpty()) { Text("Exportar") }
                    OutlinedButton(onClick = { import.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }) { Text("Importar") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}

/** Lee un archivo de texto de hasta 20 MB; `null` si no se puede leer o es mayor. */
private fun readText(context: Context, uri: Uri): String? = try {
    val limit = 20 * 1024 * 1024
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) return@use null
            out.write(buffer, 0, n)
        }
        out.toString(Charsets.UTF_8.name())
    }
} catch (_: Exception) {
    null
}
