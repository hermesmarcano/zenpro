package com.hermesmarcano.zen.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesmarcano.zen.alarm.AlarmActions
import com.hermesmarcano.zen.core.Weekday
import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.data.Anchor
import com.hermesmarcano.zen.data.PhotoAnchor
import com.hermesmarcano.zen.photo.PhotoStore
import com.hermesmarcano.zen.zenApp
import java.io.IOException

private enum class EditStep { FORM, NEW_QR, NEW_PHOTO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(alarmId: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val repository = context.zenApp.repository
    val state by repository.state.collectAsState()
    val existing = remember(alarmId) { alarmId?.let { repository.state.value.alarm(it) } }

    var step by remember { mutableStateOf(EditStep.FORM) }
    var days by remember { mutableStateOf(existing?.weekdays ?: emptySet()) }
    var anchorId by remember { mutableStateOf(existing?.anchorId) }
    val timeState = rememberTimePickerState(
        initialHour = existing?.hour ?: 7,
        initialMinute = existing?.minute ?: 0,
        is24Hour = true,
    )

    fun persistAnchor(anchor: Anchor) {
        try {
            repository.update { if (it.anchor(anchor.id) != null) it else it.copy(anchors = it.anchors + anchor) }
            anchorId = anchor.id
            step = EditStep.FORM
        } catch (_: IOException) {
            Toast.makeText(context, "No se pudo guardar el lugar. Inténtalo de nuevo.", Toast.LENGTH_LONG).show()
        }
    }

    when (step) {
        EditStep.NEW_QR -> {
            QrAnchorSetup(onDone = ::persistAnchor, onCancel = { step = EditStep.FORM })
            return
        }
        EditStep.NEW_PHOTO -> {
            PhotoAnchorSetup(onDone = ::persistAnchor, onCancel = { step = EditStep.FORM })
            return
        }
        EditStep.FORM -> Unit
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(if (existing == null) "Nueva alarma" else "Editar alarma", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = timeState) }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Repetir", fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (day in Weekday.mondayFirst) {
                    val selected = day in days
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .toggleable(value = selected, role = Role.Checkbox) {
                                days = if (selected) days - day else days + day
                            }
                            .semantics { contentDescription = day.fullName },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            day.shortLabel,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Text(Weekday.summary(days), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dónde se apaga", fontWeight = FontWeight.SemiBold)
            if (state.anchors.isEmpty()) {
                Text(
                    "Crea un lugar: un código QR pegado fuera de tu cuarto, o la foto de un punto de la casa.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
            state.anchors.forEach { anchor ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = anchor.id == anchorId, role = Role.RadioButton) { anchorId = anchor.id },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = anchor.id == anchorId, onClick = null)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                        Text(anchor.label)
                        Text(
                            if (anchor is PhotoAnchor) "Foto del lugar" else "Código QR",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                    val inUse = state.isAnchorInUse(anchor.id)
                    if (!inUse && anchor.id != anchorId) {
                        TextButton(onClick = {
                            try {
                                repository.update { it.copy(anchors = it.anchors.filterNot { a -> a.id == anchor.id }) }
                                if (anchor is PhotoAnchor) PhotoStore.deleteAnchor(context, anchor.id)
                            } catch (_: IOException) {
                                Toast.makeText(context, "No se pudo eliminar.", Toast.LENGTH_LONG).show()
                            }
                        }) { Text("Eliminar") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { step = EditStep.NEW_QR }, modifier = Modifier.weight(1f)) { Text("Nuevo QR") }
                OutlinedButton(onClick = { step = EditStep.NEW_PHOTO }, modifier = Modifier.weight(1f)) { Text("Nueva foto") }
            }
        }

        Button(
            onClick = {
                val chosen = anchorId
                if (chosen == null || repository.state.value.anchor(chosen) == null) return@Button
                val alarm = (existing ?: AlarmEntity.new(timeState.hour, timeState.minute, days, chosen))
                    .copy(hour = timeState.hour, minute = timeState.minute, weekdays = days, anchorId = chosen)
                try {
                    AlarmActions.save(context, alarm, enabled = true)
                    onClose()
                } catch (_: IOException) {
                    Toast.makeText(context, "No se pudo guardar la alarma.", Toast.LENGTH_LONG).show()
                }
            },
            enabled = anchorId != null && state.anchor(anchorId) != null,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Guardar y activar", fontSize = 16.sp) }

        if (existing != null) {
            TextButton(
                onClick = {
                    try {
                        AlarmActions.delete(context, existing.id)
                        onClose()
                    } catch (_: IOException) {
                        Toast.makeText(context, "No se pudo eliminar la alarma.", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Eliminar alarma", color = MaterialTheme.colorScheme.error) }
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
    }
}
