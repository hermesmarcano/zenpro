package com.hermesmarcano.zen.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesmarcano.zen.core.ExerciseLibrary
import com.hermesmarcano.zen.core.WorkoutNumbers
import com.hermesmarcano.zen.core.WorkoutStats
import com.hermesmarcano.zen.data.ExerciseKind
import com.hermesmarcano.zen.data.ExerciseRef
import com.hermesmarcano.zen.data.MuscleGroup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Mancuerna: Material no trae un icono de gimnasio en el paquete básico, así que se dibuja. */
val DumbbellIcon: ImageVector by lazy {
    ImageVector.Builder("Dumbbell", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(2f, 9f); lineTo(4f, 9f); lineTo(4f, 15f); lineTo(2f, 15f); close()
            moveTo(5f, 6f); lineTo(7.5f, 6f); lineTo(7.5f, 18f); lineTo(5f, 18f); close()
            moveTo(7.5f, 11f); lineTo(16.5f, 11f); lineTo(16.5f, 13f); lineTo(7.5f, 13f); close()
            moveTo(16.5f, 6f); lineTo(19f, 6f); lineTo(19f, 18f); lineTo(16.5f, 18f); close()
            moveTo(20f, 9f); lineTo(22f, 9f); lineTo(22f, 15f); lineTo(20f, 15f); close()
        }
    }.build()
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM", Locale("es"))

fun dayLabel(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(DATE_FORMAT).replaceFirstChar { it.uppercase() }

/** Campo numérico compacto para una celda de serie (peso, repeticiones, segundos). */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    decimal: Boolean = false,
    enabled: Boolean = true,
    description: String,
) {
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = { raw ->
            // Solo dígitos (y una coma/punto si el campo es decimal); lo demás no entra.
            val cleaned = raw.filter { it.isDigit() || (decimal && (it == ',' || it == '.')) }.take(7)
            onValueChange(cleaned)
        },
        modifier = modifier.semantics { contentDescription = description },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        decorationBox = { inner ->
            Box(
                modifier = Modifier.clip(shape).background(colors.surfaceVariant).border(BorderStroke(1.dp, colors.outlineVariant), shape).padding(horizontal = 6.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (value.isEmpty()) Text(placeholder, color = colors.outline, fontSize = 16.sp)
                inner()
            }
        },
    )
}

/** Pequeño paso −/+ con el valor en medio (series, descanso). */
@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, minusEnabled: Boolean = true, plusEnabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onMinus, enabled = minusEnabled, modifier = Modifier.semantics { contentDescription = "Menos $label" }) { Text("−", fontSize = 20.sp) }
        Text(value, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 2.dp))
        TextButton(onClick = onPlus, enabled = plusEnabled, modifier = Modifier.semantics { contentDescription = "Más $label" }) { Text("+", fontSize = 20.sp) }
    }
}

/** Filtro por grupo muscular. */
@Composable
fun MuscleFilter(selected: MuscleGroup?, onSelect: (MuscleGroup?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Todos") }) }
        items(MuscleGroup.entries.toList(), key = { it.id }) { m ->
            FilterChip(selected = selected == m, onClick = { onSelect(if (selected == m) null else m) }, label = { Text(m.title) })
        }
    }
}

/**
 * Biblioteca de ejercicios con búsqueda, filtro y alta de ejercicios propios. [onPick] recibe el elegido.
 * [onDeleteCustom] (opcional) muestra una papelera en los ejercicios propios.
 */
@Composable
fun ExerciseList(
    custom: List<ExerciseRef>,
    modifier: Modifier = Modifier,
    onPick: (ExerciseRef) -> Unit,
    onCreate: (name: String, muscle: MuscleGroup, kind: ExerciseKind) -> ExerciseLibrary.AddResult,
    onDeleteCustom: ((ExerciseRef) -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val group = muscle?.let { MuscleGroup.fromId(it) }
    val results = remember(custom, query, muscle) { ExerciseLibrary.search(custom, query, group) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(ExerciseLibrary.NAME_MAX) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            label = { Text("Buscar ejercicio") },
        )
        MuscleFilter(group) { muscle = it?.id }
        LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "create") {
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Crear ejercicio propio", modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (results.isEmpty()) item(key = "none") { Text("Sin resultados. Puedes crearlo.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            items(results, key = { it.id }) { ref ->
                Card(onClick = { onPick(ref) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
                    Row(modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ref.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                ref.muscle.title + " · " + ref.kind.title + if (ExerciseLibrary.isCustom(ref)) " · propio" else "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (onDeleteCustom != null && ExerciseLibrary.isCustom(ref)) {
                            IconButton(onClick = { onDeleteCustom(ref) }) { Icon(Icons.Filled.Delete, contentDescription = "Eliminar ${ref.name}") }
                        }
                    }
                }
            }
        }
    }
    if (creating) CreateExerciseDialog(onDismiss = { creating = false }, onCreate = onCreate, onCreated = { creating = false; onPick(it) })
}

@Composable
private fun CreateExerciseDialog(
    onDismiss: () -> Unit,
    onCreate: (String, MuscleGroup, ExerciseKind) -> ExerciseLibrary.AddResult,
    onCreated: (ExerciseRef) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf(MuscleGroup.CHEST.id) }
    var kind by rememberSaveable { mutableStateOf(ExerciseKind.WEIGHT_REPS.id) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ejercicio propio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", " ").take(ExerciseLibrary.NAME_MAX); error = null },
                    singleLine = true,
                    label = { Text("Nombre") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Qué anotas", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ExerciseKind.entries.toList(), key = { it.id }) { k -> FilterChip(selected = kind == k.id, onClick = { kind = k.id }, label = { Text(k.title) }) }
                }
                Text("Grupo muscular", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MuscleGroup.entries.toList(), key = { it.id }) { m -> FilterChip(selected = muscle == m.id, onClick = { muscle = m.id }, label = { Text(m.title) }) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                when (val r = onCreate(name, MuscleGroup.fromId(muscle), ExerciseKind.fromId(kind))) {
                    is ExerciseLibrary.AddResult.Added -> onCreated(r.ref)
                    ExerciseLibrary.AddResult.Duplicate -> error = "Ya existe un ejercicio con ese nombre."
                    ExerciseLibrary.AddResult.TooLong -> error = "Máximo ${ExerciseLibrary.NAME_MAX} caracteres."
                    ExerciseLibrary.AddResult.Full -> error = "Llegaste al máximo de ejercicios propios (${ExerciseLibrary.MAX_CUSTOM})."
                    ExerciseLibrary.AddResult.Blank -> error = "Escribe un nombre."
                }
            }) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ---------- Gráficas (Canvas, sin dependencias) ----------

/** Línea de progreso de un ejercicio: un punto por sesión. */
@Composable
fun LineChart(points: List<WorkoutStats.Point>, unit: String, modifier: Modifier = Modifier, height: Dp = 140.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val summary = if (points.isEmpty()) "Sin datos" else "De ${WorkoutNumbers.formatKg(points.first().value)} a ${WorkoutNumbers.formatKg(points.last().value)} $unit en ${points.size} sesiones"
    Column(modifier = modifier.semantics { contentDescription = "Gráfica. $summary" }) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val w = size.width; val h = size.height
            drawLine(grid, Offset(0f, h), Offset(w, h), strokeWidth = 2f)
            drawLine(grid, Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1f)
            if (points.isEmpty()) return@Canvas
            val min = points.minOf { it.value }; val max = points.maxOf { it.value }
            val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
            val pad = 10f
            fun x(i: Int) = if (points.size == 1) w / 2 else pad + (w - 2 * pad) * i / (points.size - 1)
            fun y(v: Double) = if (max - min < 1e-9) h / 2 else (h - pad - ((v - min) / span * (h - 2 * pad))).toFloat()
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(i), y(p.value)) else path.lineTo(x(i), y(p.value)) }
            drawPath(path, primary, style = Stroke(width = 5f, cap = StrokeCap.Round))
            points.forEachIndexed { i, p -> drawCircle(primary, radius = 7f, center = Offset(x(i), y(p.value))) }
        }
        if (points.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(dayLabel(points.first().day), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(dayLabel(points.last().day), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Barras (volumen por semana). La última, la semana actual, va resaltada. */
@Composable
fun BarChart(values: List<Double>, modifier: Modifier = Modifier, height: Dp = 90.dp, description: String) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (values.isEmpty()) return@Canvas
        val max = values.max().takeIf { it > 0.0 } ?: 1.0
        val gap = 8f
        val barW = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val barH = if (v <= 0.0) 3f else (v / max * size.height).toFloat().coerceAtLeast(6f)
            drawRoundRect(
                color = if (i == values.lastIndex) primary else dim,
                topLeft = Offset(i * (barW + gap), size.height - barH),
                size = Size(barW, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
            )
        }
    }
}

/** Puntitos del objetivo semanal: llenos = días entrenados. */
@Composable
fun GoalDots(done: Int, goal: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { contentDescription = "$done de $goal días entrenados esta semana" }) {
        repeat(goal) { i ->
            Box(
                modifier = Modifier.size(14.dp).clip(CircleShape).then(
                    if (i < done) Modifier.background(MaterialTheme.colorScheme.primary)
                    else Modifier.border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline), CircleShape),
                ),
            )
        }
    }
}

/** Selector de ejercicio a pantalla completa (sobre lo que haya debajo). */
@Composable
fun ExercisePickerDialog(
    custom: List<ExerciseRef>,
    title: String = "Añadir ejercicio",
    onPick: (ExerciseRef) -> Unit,
    onCreate: (String, MuscleGroup, ExerciseKind) -> ExerciseLibrary.AddResult,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(modifier = Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(16.dp).height(560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
                ExerciseList(custom = custom, modifier = Modifier.weight(1f), onPick = onPick, onCreate = onCreate)
            }
        }
    }
}
