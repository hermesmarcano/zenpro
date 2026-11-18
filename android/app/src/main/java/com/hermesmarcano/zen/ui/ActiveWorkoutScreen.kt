package com.hermesmarcano.zen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesmarcano.zen.alarm.RestTimer
import com.hermesmarcano.zen.core.PlateCalculator
import com.hermesmarcano.zen.core.WorkoutNumbers
import com.hermesmarcano.zen.core.WorkoutOps
import com.hermesmarcano.zen.core.WorkoutStats
import com.hermesmarcano.zen.data.ExerciseKind
import com.hermesmarcano.zen.data.RestState
import com.hermesmarcano.zen.data.SessionExercise
import com.hermesmarcano.zen.data.SetEntry
import com.hermesmarcano.zen.data.SetType
import com.hermesmarcano.zen.data.WorkoutActions
import com.hermesmarcano.zen.data.WorkoutFinish
import com.hermesmarcano.zen.data.WorkoutSession
import com.hermesmarcano.zen.zenApp
import java.io.IOException
import kotlinx.coroutines.delay

/** Sesión en curso: series con lo de la última vez ya puesto, cronómetro de descanso y botón de terminar. */
@Composable
fun ActiveWorkoutScreen(
    modifier: Modifier,
    onBack: () -> Unit,
    onFinished: (WorkoutFinish) -> Unit,
    onEnded: () -> Unit,
    announce: (text: String, action: String?, onAction: (() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val workouts = context.zenApp.workouts
    val data by workouts.data.collectAsState()
    val session = data.active
    val haptic = LocalHapticFeedback.current

    // Pantalla encendida mientras se entrena: no hay que desbloquear entre series.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // La sesión desapareció (terminada o descartada desde otro sitio): salir.
    LaunchedEffect(session == null) { if (session == null) { RestTimer.cancel(context); onEnded() } }
    // El cronómetro de fuera de la app (alarma + notificación) sigue al estado guardado.
    LaunchedEffect(session?.rest) { if (session != null) RestTimer.sync(context, session.rest) }
    if (session == null) return

    fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            announce("No se pudo guardar. Revisa el espacio del teléfono.", null, null)
        }
    }

    var picking by rememberSaveable { mutableStateOf(false) }
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var noteDialog by remember { mutableStateOf<Int?>(null) } // -1 = nota de la sesión
    var restDialog by remember { mutableStateOf<Int?>(null) }
    var setDialog by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var plateDialog by remember { mutableStateOf<Double?>(null) }
    var plateOpen by rememberSaveable { mutableStateOf(false) }

    val doneSets = WorkoutOps.doneSets(session)
    val totalSets = session.exercises.sumOf { it.sets.size }

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(session.routineName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                ElapsedLine(session.startedAt, "$doneSets de $totalSets series")
            }
            TextButton(onClick = onBack) { Text("Minimizar") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Más opciones") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Nota del entrenamiento") }, onClick = { menu = false; noteDialog = -1 })
                    DropdownMenuItem(text = { Text("Calculadora de discos") }, onClick = { menu = false; plateDialog = null; plateOpen = true })
                    DropdownMenuItem(text = { Text("Descartar entrenamiento") }, onClick = { menu = false; confirmDiscard = true })
                }
            }
            androidx.compose.material3.Button(onClick = { confirmFinish = true }) { Text("Terminar") }
        }

        session.rest?.let { rest ->
            RestBar(
                rest = rest,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                onAdjust = { delta -> safely { WorkoutActions.edit(workouts) { WorkoutOps.adjustRest(it, delta, System.currentTimeMillis()) } } },
                onSkip = { safely { WorkoutActions.edit(workouts) { WorkoutOps.skipRest(it) }; RestTimer.cancel(context) } },
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (session.exercises.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Entrenamiento libre: añade los ejercicios que vayas haciendo.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            itemsIndexed(session.exercises, key = { i, e -> "$i-${e.ref.id}" }) { exIndex, exercise ->
                ExerciseCard(
                    exercise = exercise,
                    exIndex = exIndex,
                    last = remember(data.history, exercise.ref.id) { WorkoutStats.lastSets(data.history, exercise.ref.id) },
                    onEdit = { setIndex, change -> safely { WorkoutActions.edit(workouts) { WorkoutOps.editSet(it, exIndex, setIndex, change) } } },
                    onToggle = { setIndex ->
                        safely {
                            when (val r = WorkoutActions.toggleSet(workouts, exIndex, setIndex, System.currentTimeMillis())) {
                                is WorkoutOps.CompleteResult.Done -> if (r.session.exercises[exIndex].sets[setIndex].done) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                WorkoutOps.CompleteResult.Invalid -> announce(
                                    when (exercise.ref.kind) {
                                        ExerciseKind.WEIGHT_REPS -> "Pon el peso y las repeticiones para darla por hecha."
                                        ExerciseKind.BODYWEIGHT_REPS -> "Pon las repeticiones para darla por hecha."
                                        ExerciseKind.DURATION -> "Pon los segundos para darla por hecha."
                                    },
                                    null, null,
                                )
                            }
                        }
                    },
                    onSetMenu = { setIndex -> setDialog = exIndex to setIndex },
                    onAddSet = { safely { WorkoutActions.edit(workouts) { WorkoutOps.addSet(it, exIndex) } } },
                    onNote = { noteDialog = exIndex },
                    onRest = { restDialog = exIndex },
                    onPlates = { plateDialog = exercise.sets.firstOrNull { it.weightKg != null }?.weightKg; plateOpen = true },
                    onRemove = { safely { WorkoutActions.edit(workouts) { WorkoutOps.removeExercise(it, exIndex) } } },
                )
            }
            item(key = "add") {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Añadir ejercicio", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }

    if (picking) {
        ExercisePickerDialog(
            custom = data.customExercises,
            onDismiss = { picking = false },
            onCreate = { n, m, k -> try { WorkoutActions.addCustomExercise(workouts, n, m, k) } catch (_: IOException) { com.hermesmarcano.zen.core.ExerciseLibrary.AddResult.Blank } },
            onPick = { ref ->
                picking = false
                safely { WorkoutActions.edit(workouts) { WorkoutOps.addExercise(it, ref, data.history, data.defaultRestSeconds) } }
            },
        )
    }

    if (confirmFinish) {
        val none = doneSets == 0
        val pending = totalSets - doneSets
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text(if (none) "Aún no hay series hechas" else "¿Terminar el entrenamiento?") },
            text = {
                Text(
                    if (none) "Marca con ✓ las series que hagas. Si no vas a entrenar, puedes descartarlo."
                    else if (pending > 0) "Se guardan las $doneSets series marcadas; las $pending sin marcar se descartan."
                    else "Se guardan las $doneSets series.",
                )
            },
            confirmButton = {
                if (none) TextButton(onClick = { confirmFinish = false; confirmDiscard = true }) { Text("Descartar") }
                else TextButton(onClick = {
                    confirmFinish = false
                    safely {
                        val result = WorkoutActions.finish(workouts, context.zenApp.repository, System.currentTimeMillis())
                        RestTimer.cancel(context)
                        if (result != null) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onFinished(result) }
                    }
                }) { Text("Terminar") }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Seguir entrenando") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("¿Descartar el entrenamiento?") },
            text = { Text("Se pierde lo que has anotado en esta sesión. No se puede deshacer.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; safely { WorkoutActions.discard(workouts); RestTimer.cancel(context) } }) { Text("Descartar") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancelar") } },
        )
    }

    noteDialog?.let { target ->
        var text by remember(target) { mutableStateOf(if (target < 0) session.note else session.exercises.getOrNull(target)?.note.orEmpty()) }
        AlertDialog(
            onDismissRequest = { noteDialog = null },
            title = { Text(if (target < 0) "Nota del entrenamiento" else "Nota del ejercicio") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(WorkoutOps.NOTE_MAX) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5) },
            confirmButton = {
                TextButton(onClick = {
                    safely { WorkoutActions.edit(workouts) { if (target < 0) WorkoutOps.setSessionNote(it, text) else WorkoutOps.setExerciseNote(it, target, text) } }
                    noteDialog = null
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { noteDialog = null }) { Text("Cancelar") } },
        )
    }

    restDialog?.let { target ->
        val seconds = session.exercises.getOrNull(target)?.restSeconds ?: 0
        AlertDialog(
            onDismissRequest = { restDialog = null },
            title = { Text("Descanso entre series") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Stepper(
                        label = "descanso",
                        value = if (seconds == 0) "Sin descanso" else WorkoutNumbers.clock(seconds),
                        onMinus = { safely { WorkoutActions.edit(workouts) { WorkoutOps.setExerciseRest(it, target, seconds - WorkoutOps.REST_STEP_SECONDS) } } },
                        onPlus = { safely { WorkoutActions.edit(workouts) { WorkoutOps.setExerciseRest(it, target, seconds + WorkoutOps.REST_STEP_SECONDS) } } },
                        minusEnabled = seconds > WorkoutOps.MIN_REST_SECONDS,
                        plusEnabled = seconds < WorkoutOps.MAX_REST_SECONDS,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { restDialog = null }) { Text("Listo") } },
        )
    }

    setDialog?.let { (e, s) ->
        val set = session.exercises.getOrNull(e)?.sets?.getOrNull(s)
        if (set == null) setDialog = null
        else SetOptionsDialog(
            set = set,
            onType = { t -> safely { WorkoutActions.edit(workouts) { WorkoutOps.setSetType(it, e, s, t) } } },
            onRpe = { r -> safely { WorkoutActions.edit(workouts) { WorkoutOps.editSet(it, e, s) { x -> x.copy(rpe = r) } } } },
            onDelete = { safely { WorkoutActions.edit(workouts) { WorkoutOps.removeSet(it, e, s) } }; setDialog = null },
            onDismiss = { setDialog = null },
        )
    }

    if (plateOpen) {
        PlateDialog(
            initialKg = plateDialog,
            barKg = data.barKg,
            onBar = { kg -> safely { WorkoutActions.setBar(workouts, kg) } },
            onDismiss = { plateOpen = false },
        )
    }
}

/** Reloj que avanza cada segundo. Va aislado en su propio composable: así solo se redibuja esto y no toda la lista de series. */
@Composable
private fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun ElapsedLine(startedAt: Long, suffix: String) {
    val now = rememberNow()
    val elapsed = ((now - startedAt).coerceAtLeast(0L) / 1000L).toInt()
    Text("${WorkoutNumbers.clock(elapsed)} · $suffix", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

@Composable
private fun RestBar(rest: RestState, modifier: Modifier, onAdjust: (Int) -> Unit, onSkip: () -> Unit) {
    val now = rememberNow()
    val remaining = rest.remainingMillis(now)
    val over = remaining <= 0L
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (over) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (over) "Descanso terminado" else "Descanso · " + WorkoutNumbers.clock(((remaining + 999) / 1000).toInt()),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f).semantics { contentDescription = if (over) "Descanso terminado" else "Descanso, quedan ${(remaining + 999) / 1000} segundos" },
                )
                if (!over) {
                    TextButton(onClick = { onAdjust(-WorkoutOps.REST_STEP_SECONDS) }) { Text("−15") }
                    TextButton(onClick = { onAdjust(WorkoutOps.REST_STEP_SECONDS) }) { Text("+15") }
                }
                TextButton(onClick = onSkip) { Text(if (over) "Cerrar" else "Saltar") }
            }
            if (!over) {
                LinearProgressIndicator(
                    progress = { (remaining.toFloat() / (rest.totalSeconds * 1000f)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface,
                )
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    exercise: SessionExercise,
    exIndex: Int,
    last: List<SetEntry>,
    onEdit: (Int, (SetEntry) -> SetEntry) -> Unit,
    onToggle: (Int) -> Unit,
    onSetMenu: (Int) -> Unit,
    onAddSet: () -> Unit,
    onNote: () -> Unit,
    onRest: () -> Unit,
    onPlates: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val kind = exercise.ref.kind
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(exercise.ref.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    val tags = buildList {
                        add(exercise.ref.muscle.title)
                        exercise.superset?.let { add("Superserie $it") }
                        add(if (exercise.restSeconds == 0) "sin descanso" else "descanso ${WorkoutNumbers.clock(exercise.restSeconds)}")
                    }
                    Text(tags.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Opciones de ${exercise.ref.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Nota") }, onClick = { menu = false; onNote() })
                        DropdownMenuItem(text = { Text("Descanso entre series") }, onClick = { menu = false; onRest() })
                        if (kind == ExerciseKind.WEIGHT_REPS) DropdownMenuItem(text = { Text("Calculadora de discos") }, onClick = { menu = false; onPlates() })
                        DropdownMenuItem(text = { Text("Quitar ejercicio") }, onClick = { menu = false; onRemove() })
                    }
                }
            }
            if (exercise.note.isNotBlank()) Text(exercise.note, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (last.isNotEmpty()) {
                Text(
                    "Última vez: " + last.take(5).joinToString(" · ") { WorkoutNumbers.describe(kind, it) } + if (last.size > 5) " …" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SERIE", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(48.dp))
                if (kind == ExerciseKind.WEIGHT_REPS) Text("KG", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text(if (kind == ExerciseKind.DURATION) "SEGUNDOS" else "REPS", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Box(modifier = Modifier.width(52.dp))
            }
            var normalNumber = 0
            exercise.sets.forEachIndexed { setIndex, set ->
                val label = if (set.type == SetType.WARMUP) "C" else { normalNumber++; normalNumber.toString() }
                SetRow(
                    label = label,
                    set = set,
                    kind = kind,
                    exName = exercise.ref.name,
                    onWeight = { w -> onEdit(setIndex) { it.copy(weightKg = w) } },
                    onReps = { r -> onEdit(setIndex) { it.copy(reps = r) } },
                    onSeconds = { s -> onEdit(setIndex) { it.copy(seconds = s) } },
                    onToggle = { onToggle(setIndex) },
                    onMenu = { onSetMenu(setIndex) },
                )
            }
            TextButton(onClick = onAddSet) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Serie", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun SetRow(
    label: String,
    set: SetEntry,
    kind: ExerciseKind,
    exName: String,
    onWeight: (Double?) -> Unit,
    onReps: (Int?) -> Unit,
    onSeconds: (Int?) -> Unit,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
) {
    val tint = if (set.done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextButton(onClick = onMenu, modifier = Modifier.width(48.dp).semantics { contentDescription = "Serie $label de $exName: tipo, esfuerzo, eliminar" }) {
            Text(label + if (set.rpe != null) "·${WorkoutNumbers.formatRpe(set.rpe)}" else "", fontWeight = FontWeight.Bold, color = if (set.type == SetType.WARMUP) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, maxLines = 1)
        }
        if (kind == ExerciseKind.WEIGHT_REPS) {
            SyncedNumber(stored = set.weightKg, toText = { WorkoutNumbers.formatKg(it) }, parse = { WorkoutNumbers.parseWeight(it) }, onChange = onWeight, decimal = true, placeholder = "kg", description = "Peso en kilos, serie $label de $exName", modifier = Modifier.weight(1f))
        }
        if (kind == ExerciseKind.DURATION) {
            SyncedNumber(stored = set.seconds, toText = { it.toString() }, parse = { WorkoutNumbers.parseSeconds(it) }, onChange = onSeconds, decimal = false, placeholder = "s", description = "Segundos, serie $label de $exName", modifier = Modifier.weight(1f))
        } else {
            SyncedNumber(stored = set.reps, toText = { it.toString() }, parse = { WorkoutNumbers.parseReps(it) }, onChange = onReps, decimal = false, placeholder = "reps", description = "Repeticiones, serie $label de $exName", modifier = Modifier.weight(1f))
        }
        IconButton(
            onClick = onToggle,
            modifier = Modifier.size(46.dp).clip(CircleShape).background(if (set.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = if (set.done) "Desmarcar serie $label de $exName" else "Marcar serie $label de $exName como hecha" },
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = if (set.done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * Campo que muestra el valor guardado pero deja escribir sin que lo reformatee a mitad ("62," se queda como "62,"):
 * solo se reescribe el texto si el valor guardado cambia por otro lado (p. ej. rellenado al empezar).
 */
@Composable
private fun <T : Any> SyncedNumber(
    stored: T?,
    toText: (T) -> String,
    parse: (String) -> T?,
    onChange: (T?) -> Unit,
    decimal: Boolean,
    placeholder: String,
    description: String,
    modifier: Modifier,
) {
    var text by remember { mutableStateOf(stored?.let(toText).orEmpty()) }
    LaunchedEffect(stored) { if (parse(text) != stored) text = stored?.let(toText).orEmpty() }
    NumberField(
        value = text,
        onValueChange = { t -> text = t; onChange(parse(t)) },
        modifier = modifier,
        placeholder = placeholder,
        decimal = decimal,
        description = description,
    )
}

@Composable
private fun SetOptionsDialog(set: SetEntry, onType: (SetType) -> Unit, onRpe: (Double?) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Serie") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = set.type == SetType.NORMAL, onClick = { onType(SetType.NORMAL) }, label = { Text("De trabajo") })
                    FilterChip(selected = set.type == SetType.WARMUP, onClick = { onType(SetType.WARMUP) }, label = { Text("Calentamiento") })
                }
                Text("Esfuerzo (RPE)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = set.rpe == null, onClick = { onRpe(null) }, label = { Text("—") }) }
                    items((12..20).map { it / 2.0 }) { v -> FilterChip(selected = set.rpe == v, onClick = { onRpe(v) }, label = { Text(WorkoutNumbers.formatRpe(v)) }) }
                }
                Text("Los calentamientos no cuentan para volumen ni récords.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
        dismissButton = { TextButton(onClick = onDelete) { Text("Eliminar serie") } },
    )
}

/** Qué discos poner a cada lado para un peso dado. Solo kg. */
@Composable
fun PlateDialog(initialKg: Double?, barKg: Double, onBar: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initialKg?.let { WorkoutNumbers.formatKg(it) }.orEmpty()) }
    val target = WorkoutNumbers.parseWeight(text)
    val result = target?.let { PlateCalculator.perSide(it, barKg) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calculadora de discos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), placeholder = "Peso total (kg)", decimal = true, description = "Peso total en kilos")
                Text("Barra", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlateCalculator.BAR_OPTIONS.forEach { b -> FilterChip(selected = barKg == b, onClick = { onBar(b) }, label = { Text("${WorkoutNumbers.formatKg(b)} kg") }) }
                }
                when {
                    target == null -> Text("Escribe el peso total que quieres levantar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result == null -> Text("Es menos que la barra (${WorkoutNumbers.formatKg(barKg)} kg).", color = MaterialTheme.colorScheme.error)
                    else -> {
                        Text("Por lado: " + PlateCalculator.describe(result), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Total cargado: ${WorkoutNumbers.formatKg(result.loadedKg)} kg", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (result.remainderKg > 0.001) Text("No se puede formar exacto: faltan ${WorkoutNumbers.formatKg(result.remainderKg)} kg con estos discos.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
