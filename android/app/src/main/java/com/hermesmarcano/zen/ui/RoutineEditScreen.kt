package com.hermesmarcano.zen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.hermesmarcano.zen.core.WorkoutNumbers
import com.hermesmarcano.zen.core.WorkoutOps
import com.hermesmarcano.zen.data.RoutineExercise
import com.hermesmarcano.zen.data.WorkoutActions
import com.hermesmarcano.zen.zenApp
import java.io.IOException

/** Crear o editar una rutina: nombre, ejercicios, series, descanso y supersets. Se guarda al pulsar "Guardar". */
@Composable
fun RoutineEditScreen(
    routineId: String?,
    modifier: Modifier,
    onClose: () -> Unit,
    announce: (text: String, action: String?, onAction: (() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val workouts = context.zenApp.workouts
    val data by workouts.data.collectAsState()
    val original = remember(routineId) { workouts.data.value.routine(routineId) }

    // Nombre y ejercicios son un borrador: ninguno se restaura si Android cierra el proceso (así nunca queda a medias).
    var name by remember(routineId) { mutableStateOf(original?.name.orEmpty()) }
    val items = remember(routineId) { mutableStateListOf<RoutineExercise>().apply { addAll(original?.items.orEmpty()) } }
    var picking by rememberSaveable { mutableStateOf(false) }
    var confirmExit by rememberSaveable { mutableStateOf(false) }

    val dirty = name != original?.name.orEmpty() || items.toList() != original?.items.orEmpty()

    fun save() {
        if (routineId != null && workouts.data.value.routine(routineId) == null) {
            announce("Esta rutina ya no existe.", null, null)
            onClose()
            return
        }
        try {
            val result = if (routineId == null) WorkoutActions.addRoutine(workouts, name, items.toList()) else WorkoutActions.updateRoutine(workouts, routineId, name, items.toList())
            when (result) {
                is WorkoutOps.RoutineResult.Ok -> onClose()
                WorkoutOps.RoutineResult.Blank -> announce("Ponle un nombre a la rutina.", null, null)
                WorkoutOps.RoutineResult.TooLong -> announce("El nombre es demasiado largo (máximo ${WorkoutOps.ROUTINE_NAME_MAX}).", null, null)
                WorkoutOps.RoutineResult.Full -> announce("Ya tienes ${WorkoutOps.MAX_ROUTINES} rutinas. Elimina alguna.", null, null)
            }
        } catch (_: IOException) {
            announce("No se pudo guardar. Revisa el espacio del teléfono.", null, null)
        }
    }

    androidx.activity.compose.BackHandler(enabled = dirty) { confirmExit = true }
    androidx.activity.compose.BackHandler(enabled = !dirty) { onClose() }

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (dirty) confirmExit = true else onClose() }) { Text("Cancelar") }
            Text(if (routineId == null) "Nueva rutina" else "Editar rutina", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = { save() }, enabled = name.isNotBlank()) { Text("Guardar") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "name") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", " ").take(WorkoutOps.ROUTINE_NAME_MAX) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Nombre (p. ej. Día 1 · Empuje)") },
                )
            }
            item(key = "hint") {
                Text(
                    "La rutina no depende del día de la semana: ZEN te propone la siguiente en el orden en que las tengas.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(items, key = { i, it -> "$i-${it.ref.id}" }) { index, item ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.ref.name, fontWeight = FontWeight.Bold)
                        Text(
                            item.ref.muscle.title + (item.superset?.let { " · Superserie $it" } ?: ""),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Series", modifier = Modifier.weight(1f))
                            Stepper(
                                label = "series",
                                value = item.sets.toString(),
                                onMinus = { items[index] = item.copy(sets = item.sets - 1) },
                                onPlus = { items[index] = item.copy(sets = item.sets + 1) },
                                minusEnabled = item.sets > 1,
                                plusEnabled = item.sets < WorkoutOps.MAX_SETS_PER_EXERCISE,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Descanso", modifier = Modifier.weight(1f))
                            Stepper(
                                label = "descanso",
                                value = if (item.restSeconds == 0) "Sin" else WorkoutNumbers.clock(item.restSeconds),
                                onMinus = { items[index] = item.copy(restSeconds = (item.restSeconds - WorkoutOps.REST_STEP_SECONDS).coerceAtLeast(0)) },
                                onPlus = { items[index] = item.copy(restSeconds = (item.restSeconds + WorkoutOps.REST_STEP_SECONDS).coerceAtMost(WorkoutOps.MAX_REST_SECONDS)) },
                                minusEnabled = item.restSeconds > 0,
                                plusEnabled = item.restSeconds < WorkoutOps.MAX_REST_SECONDS,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            TextButton(onClick = { move(items, index, index - 1) }, enabled = index > 0) { Text("Subir") }
                            TextButton(onClick = { move(items, index, index + 1) }, enabled = index < items.lastIndex) { Text("Bajar") }
                            if (index < items.lastIndex) {
                                val joined = item.superset != null && item.superset == items[index + 1].superset
                                TextButton(onClick = { replace(items, WorkoutOps.toggleSupersetWithNext(items.toList(), index)) }) { Text(if (joined) "Separar" else "Superserie ↓") }
                            }
                            TextButton(onClick = { replace(items, WorkoutOps.normalizeSupersets(items.toList().filterIndexed { i, _ -> i != index })) }) { Text("Quitar") }
                        }
                    }
                }
            }
            item(key = "add") {
                OutlinedButton(onClick = { picking = true }, enabled = items.size < WorkoutOps.MAX_EXERCISES_PER_ROUTINE, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(if (items.size < WorkoutOps.MAX_EXERCISES_PER_ROUTINE) "Añadir ejercicio" else "Máximo ${WorkoutOps.MAX_EXERCISES_PER_ROUTINE} ejercicios", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }

    if (picking) {
        ExercisePickerDialog(
            custom = data.customExercises,
            onDismiss = { picking = false },
            onCreate = { n, m, k -> try { WorkoutActions.addCustomExercise(workouts, n, m, k) } catch (_: IOException) { ExerciseLibrary.AddResult.Blank } },
            onPick = { ref ->
                picking = false
                if (items.size < WorkoutOps.MAX_EXERCISES_PER_ROUTINE) items.add(RoutineExercise(ref, sets = 3, restSeconds = data.defaultRestSeconds))
            },
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("¿Salir sin guardar?") },
            text = { Text("Perderás los cambios de esta rutina.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; onClose() }) { Text("Salir") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Seguir editando") } },
        )
    }
}

private fun move(items: androidx.compose.runtime.snapshots.SnapshotStateList<RoutineExercise>, from: Int, to: Int) =
    replace(items, WorkoutOps.normalizeSupersets(WorkoutOps.moveItem(items.toList(), from, to)))

private fun replace(items: androidx.compose.runtime.snapshots.SnapshotStateList<RoutineExercise>, next: List<RoutineExercise>) {
    items.clear()
    items.addAll(next)
}
