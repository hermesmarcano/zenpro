package com.hermesmarcano.zen.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hermesmarcano.zen.core.GameMessages
import com.hermesmarcano.zen.core.Levels
import com.hermesmarcano.zen.core.RingCountdownFormatter
import com.hermesmarcano.zen.core.TaskBoard
import com.hermesmarcano.zen.core.XpRules
import com.hermesmarcano.zen.data.TaskActions
import com.hermesmarcano.zen.data.TaskItem
import com.hermesmarcano.zen.data.WakeRecorder
import com.hermesmarcano.zen.zenApp
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Nombre y color de cada nivel de prioridad. El color acompaña, nunca es lo único que lo indica (también hay texto). */
@Composable
private fun TaskBoard.Tier.color(): Color = when (this) {
    TaskBoard.Tier.HIGH -> MaterialTheme.colorScheme.primary
    TaskBoard.Tier.MEDIUM -> MaterialTheme.colorScheme.secondary
    TaskBoard.Tier.LOW -> MaterialTheme.colorScheme.outline
}

private fun TaskBoard.Tier.xp(): Int = XpRules.forTier(this)

/**
 * "Hoy": resumen del día + lista de hasta 10 tareas. La posición decide la prioridad
 * (1 prioritaria · 3 medias · 6 bajas), así que se organiza arrastrando.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    modifier: Modifier,
    onOpenSettings: () -> Unit,
    announce: (text: String, action: String?, onAction: (() -> Unit)?) -> Unit,
    onOpenAlarms: () -> Unit = {},
) {
    val context = LocalContext.current
    val repository = context.zenApp.repository
    val state by repository.state.collectAsState()
    val haptic = LocalHapticFeedback.current

    // El día puede cambiar con la app abierta (medianoche): se relee al volver a la pantalla.
    var dayTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { dayTick++ }
    // También con la app abierta: cada medio minuto se mira si cambió el minuto (cuenta atrás) o el día (medianoche).
    val minute by produceState(System.currentTimeMillis() / 60_000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 60_000
        }
    }
    val today = remember(dayTick, minute) { LocalDate.now() }
    val nowMillis = remember(dayTick, minute) { System.currentTimeMillis() }
    var showTomorrow by rememberSaveable { mutableStateOf(false) }
    val viewDay = today.toEpochDay() + if (showTomorrow) 1 else 0
    val isToday = !showTomorrow

    val stored = TaskBoard.forDay(state.tasks, viewDay)
    // Copia local para que la lista se reordene al instante mientras se arrastra; se guarda al soltar.
    val local = remember(stored) { mutableStateListOf<TaskItem>().apply { addAll(stored) } }
    var dragFrom by remember { mutableIntStateOf(-1) }

    val listState = rememberLazyListState()
    val reorderable = rememberReorderableLazyListState(listState) { from, to ->
        val a = local.indexOfFirst { it.id == from.key }
        val b = local.indexOfFirst { it.id == to.key }
        if (a >= 0 && b >= 0 && a != b) {
            local.add(b, local.removeAt(a))
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    var newTitle by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<TaskItem?>(null) }

    fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            announce("No se pudo guardar. Revisa el espacio del teléfono.", null, null)
        }
    }

    fun toggle(task: TaskItem) = safely {
        val events = TaskActions.setDone(repository, task.id, !task.done, today)
        if (!task.done) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val messages = GameMessages.describe(events)
        if (messages.isNotEmpty()) announce(messages.joinToString(" · "), null, null)
    }

    fun add() = safely {
        when (TaskActions.add(repository, viewDay, newTitle)) {
            is TaskBoard.AddResult.Added -> { newTitle = ""; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            TaskBoard.AddResult.Full -> announce("La lista está llena (10). Completa o elimina una tarea.", null, null)
            TaskBoard.AddResult.Blank, TaskBoard.AddResult.TooLong -> Unit
        }
    }

    fun remove(task: TaskItem) = safely {
        val deleted = TaskActions.delete(repository, task.id) ?: return@safely
        announce("Tarea eliminada", "Deshacer") {
            safely {
                if (!TaskActions.undoDelete(repository, deleted)) announce("No cabe: la lista ya está llena.", null, null)
            }
        }
    }

    fun nudge(task: TaskItem, delta: Int) = safely {
        val index = stored.indexOfFirst { it.id == task.id }
        TaskActions.move(repository, viewDay, index, index + delta)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Sin cámara, notificaciones o pantalla completa la alarma suena pero no se puede apagar bien:
        // el aviso tiene que verse aquí, que es donde la gente se queda, no solo en Alarmas.
        if (!Permissions.hasCamera(context) || !Permissions.hasNotifications(context) || !Permissions.hasFullScreenIntent(context)) {
            item(key = "permissions") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(14.dp)) {
                    Row(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Faltan permisos para que la alarma funcione bien.", modifier = Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = onOpenAlarms) { Text("Revisar") }
                    }
                }
            }
        }
        item(key = "summary") {
            SummaryCard(isToday = isToday, today = today, nowMillis = nowMillis, tasks = stored, onOpenSettings = onOpenSettings, onOpenAlarms = onOpenAlarms)
        }
        item(key = "days") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = isToday, onClick = { showTomorrow = false }, label = { Text("Hoy") })
                FilterChip(selected = !isToday, onClick = { showTomorrow = true }, label = { Text("Mañana") })
            }
        }
        if (isToday) {
            val pending = TaskBoard.pendingBefore(state.tasks, today.toEpochDay())
            if (pending.isNotEmpty()) {
                item(key = "carry") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(14.dp)) {
                        Row(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (pending.size == 1) "Te quedó 1 tarea pendiente de un día anterior." else "Te quedaron ${pending.size} tareas pendientes de días anteriores.",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f),
                                fontSize = 14.sp,
                            )
                            TextButton(onClick = {
                                safely {
                                    val result = TaskActions.carryOver(repository, today)
                                    announce(if (result.left > 0) "Traje ${result.moved}; ${result.left} no caben en la lista." else "Traje ${result.moved} a hoy.", null, null)
                                }
                            }) { Text("Traer a hoy") }
                        }
                    }
                }
            }
        }
        item(key = "add") {
            val nextTier = TaskBoard.tierAt(stored.size)
            val full = stored.size >= TaskBoard.MAX_PER_DAY
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it.replace("\n", " ").take(TaskBoard.TITLE_MAX) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !full,
                singleLine = true,
                label = {
                    Text(
                        if (full) "Lista llena" else if (stored.isEmpty()) "¿Qué es lo más importante ${if (isToday) "hoy" else "mañana"}?" else "Nueva tarea · entra como ${nextTier.title.lowercase(Locale.ROOT)}",
                    )
                },
                supportingText = { Text("${stored.size}/${TaskBoard.MAX_PER_DAY}" + if (!full) " · +${nextTier.xp()} XP al completarla" else "") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                trailingIcon = {
                    IconButton(onClick = { add() }, enabled = !full && newTitle.isNotBlank()) { Icon(Icons.Filled.Add, contentDescription = "Añadir tarea") }
                },
            )
        }
        if (!state.settings.taskHintSeen && stored.size >= 2) {
            item(key = "hint") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Mantén pulsado ☰ y arrastra: la posición decide la prioridad (1.ª prioritaria, 3 medias, 6 bajas).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { safely { repository.update { it.copy(settings = it.settings.copy(taskHintSeen = true)) } } }) { Text("Entendido") }
                }
            }
        }
        if (local.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (isToday) "Tu día empieza aquí. Añade lo que quieres lograr hoy: la primera será tu tarea prioritaria."
                    else "Planifica mañana esta noche: elige primero lo más importante y despertarás con el día decidido.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(local.size, key = { local[it].id }) { index ->
            val task = local[index]
            ReorderableItem(reorderable, key = task.id) { dragging ->
                val tier = TaskBoard.tierAt(index)
                TaskRow(
                    task = task,
                    tier = tier,
                    canComplete = isToday,
                    dragging = dragging,
                    canMoveUp = index > 0,
                    canMoveDown = index < local.lastIndex,
                    handle = Modifier.draggableHandle(
                        onDragStarted = {
                            dragFrom = stored.indexOfFirst { it.id == task.id }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragStopped = {
                            val to = local.indexOfFirst { it.id == task.id }
                            if (dragFrom >= 0 && to >= 0 && dragFrom != to) safely { TaskActions.move(repository, viewDay, dragFrom, to) }
                            dragFrom = -1
                        },
                    ),
                    onToggle = { toggle(task) },
                    onEdit = { editing = task },
                    onMoveUp = { nudge(task, -1) },
                    onMoveDown = { nudge(task, +1) },
                    onDelete = { remove(task) },
                )
            }
        }
    }

    editing?.let { task ->
        var text by remember(task.id) { mutableStateOf(task.title) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Editar tarea") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it.replace("\n", " ").take(TaskBoard.TITLE_MAX) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { safely { TaskActions.rename(repository, task.id, text) }; editing = null }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SummaryCard(isToday: Boolean, today: LocalDate, nowMillis: Long, tasks: List<TaskItem>, onOpenSettings: () -> Unit, onOpenAlarms: () -> Unit) {
    val context = LocalContext.current
    val state by context.zenApp.repository.state.collectAsState()
    val done = tasks.count { it.done }
    val date = (if (isToday) today else today.plusDays(1)).format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es")))
    val nextAlarm = state.alarms.filter { it.enabled && it.nextFireAt != null }.minOfOrNull { it.nextFireAt!! }
    val streak = WakeRecorder.currentStreak(state, today)
    val mitStreak = WakeRecorder.currentMitStreak(state, today)
    val mitDone = isToday && tasks.firstOrNull()?.done == true

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isToday) "Hoy" else "Mañana", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(date.replaceFirstChar { it.titlecase(Locale("es")) }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Ajustes") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (isToday) ProgressRing(done = done, total = tasks.size)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (isToday) {
                        Text(
                            when {
                                tasks.isEmpty() -> "Sin tareas todavía"
                                done == tasks.size -> "Todo hecho"
                                else -> "$done de ${tasks.size} hechas"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (nextAlarm != null) {
                        Text(
                            RingCountdownFormatter.format(nextAlarm - nowMillis).replace("Suena en", "Próxima alarma:"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                    } else {
                        TextButton(onClick = onOpenAlarms) { Text(if (state.alarms.isEmpty()) "Crear mi primera alarma" else "Activar una alarma") }
                    }
                    if (streak > 0) Text("Racha de despertar: ${daysText(streak)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    if (mitStreak > 0) Text("Racha de prioritaria: ${daysText(mitStreak)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            AnimatedVisibility(visible = mitDone) {
                Text("Prioritaria cumplida. Lo importante ya está hecho.", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            LevelBar(Levels.progress(state.progress.xp), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Anillo del día: se completa al hacer todas las tareas. */
@Composable
private fun ProgressRing(done: Int, total: Int) {
    val target = if (total == 0) 0f else done.toFloat() / total
    val fraction by animateFloatAsState(target, label = "anillo")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bar = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arc = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = arc, style = stroke)
            if (fraction > 0f) drawArc(bar, -90f, 360f * fraction, false, topLeft = Offset(inset, inset), size = arc, style = stroke)
        }
        Text("$done/$total", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@android.annotation.SuppressLint("ModifierParameter")
@Composable
private fun TaskRow(
    task: TaskItem,
    tier: TaskBoard.Tier,
    canComplete: Boolean,
    dragging: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    handle: Modifier,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val container = if (dragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    var menu by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 8.dp else 0.dp),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Franja de prioridad: el color refuerza lo que dice el texto de abajo.
            Box(modifier = Modifier.width(5.dp).fillMaxHeight().background(tier.color()))
            Icon(
                Icons.Filled.Menu,
                contentDescription = "Arrastrar para reordenar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = handle.padding(horizontal = 12.dp, vertical = 14.dp).size(24.dp),
            )
            CheckCircle(checked = task.done, enabled = canComplete, onChange = { onToggle() }, label = task.title)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    task.title,
                    fontSize = 16.sp,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        task.done -> "Hecha"
                        canComplete -> "${tier.title} · +${tier.xp()} XP"
                        else -> "${tier.title} · se completa mañana"
                    },
                    fontSize = 12.sp,
                    color = tier.color(),
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Más acciones de ${task.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Editar") }, onClick = { menu = false; onEdit() })
                    if (canMoveUp) DropdownMenuItem(text = { Text("Subir") }, onClick = { menu = false; onMoveUp() })
                    if (canMoveDown) DropdownMenuItem(text = { Text("Bajar") }, onClick = { menu = false; onMoveDown() })
                    DropdownMenuItem(text = { Text("Eliminar") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Casilla redonda con un pequeño rebote al marcarla. Mínimo 48 dp de zona táctil. */
@Composable
private fun CheckCircle(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, label: String) {
    val scale by animateFloatAsState(if (checked) 1f else 0.92f, label = "casilla")
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .semantics { contentDescription = if (checked) "Hecha: $label" else "Marcar como hecha: $label" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(if (checked) color else Color.Transparent)
                .then(if (checked) Modifier else Modifier.background(Color.Transparent))
                .border(2.dp, if (enabled) color else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
        }
    }
}
