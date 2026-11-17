package com.hermesmarcano.zen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesmarcano.zen.alarm.AlarmActions
import com.hermesmarcano.zen.core.GameMessages
import com.hermesmarcano.zen.core.TripPolicy
import com.hermesmarcano.zen.data.Anchor
import com.hermesmarcano.zen.data.PhotoAnchor
import com.hermesmarcano.zen.zenApp
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class TripKind { PAUSE, ANCHOR }

/**
 * Modo viaje: lo que impide que la app estorbe cuando estás fuera de casa. O se pausan las alarmas unos
 * días (la racha no se rompe), o se siguen apagando pero en otro lugar (un QR de repuesto, una foto del hotel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripScreen(onClose: () -> Unit, announce: (String, String?, (() -> Unit)?) -> Unit) {
    val context = LocalContext.current
    val repository = context.zenApp.repository
    val state by repository.state.collectAsState()
    val today = LocalDate.now()
    val active = state.trip?.takeIf { TripPolicy.isActive(it, today.toEpochDay()) }
    // El viaje cuenta desde la próxima alarma (si lo preparas por la noche, "1 día" cubre la de mañana).
    val firstDay = TripPolicy.startDay(today.toEpochDay(), state.alarms.filter { it.enabled }.mapNotNull { it.nextFireAt })

    var newAnchor by remember { mutableStateOf<NewAnchorKind?>(null) }
    var kind by remember { mutableStateOf(TripKind.PAUSE) }
    var days by remember { mutableIntStateOf(3) }
    var anchorId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    newAnchor?.let { kind ->
        NewAnchorFlow(
            kind,
            onCreated = { anchor ->
                if (repository.addAnchor(anchor)) {
                    anchorId = anchor.id
                    newAnchor = null
                } else {
                    error = "No se pudo guardar el lugar. Inténtalo de nuevo."
                }
            },
            onCancel = { newAnchor = null },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Modo viaje", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Cerrar") }
        }

        if (active != null) {
            val until = LocalDate.ofEpochDay(active.untilEpochDay).format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es")))
            val left = TripPolicy.remainingDays(active, today.toEpochDay())
            Text(
                if (active.isPause) "Tus alarmas están en pausa hasta el $until." else "Te apagas en ${state.anchor(active.anchorId)?.label ?: "otro lugar"} hasta el $until.",
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                (if (left == 1) "Queda 1 día. " else "Quedan $left días. ") +
                    if (active.isPause) "Después, tus alarmas vuelven solas a su hora. Tu racha no se rompe por estos días." else "Después vuelves a tu lugar de siempre.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    try {
                        AlarmActions.endTrip(context)
                        announce("Modo viaje terminado", null, null)
                        onClose()
                    } catch (_: IOException) {
                        error = "No se pudo guardar el cambio."
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Terminar modo viaje ahora") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            return@Column
        }

        Text("¿Cómo quieres hacerlo mientras estás fuera?", fontWeight = FontWeight.SemiBold)
        TripKindRow("Pausar mis alarmas", "No suenan durante el viaje y tu racha no se rompe. Las de una sola vez que caigan dentro se desactivan.", kind == TripKind.PAUSE) { kind = TripKind.PAUSE }
        TripKindRow("Apagarlas en otro lugar", "Siguen sonando, pero se apagan con otro QR o foto: uno de repuesto que lleves, o el de tu hotel.", kind == TripKind.ANCHOR) { kind = TripKind.ANCHOR }

        Text("¿Cuántos días?", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in TripPolicy.DAY_OPTIONS.take(4)) FilterChip(selected = days == option, onClick = { days = option }, label = { Text("$option") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in TripPolicy.DAY_OPTIONS.drop(4)) FilterChip(selected = days == option, onClick = { days = option }, label = { Text("$option") })
        }
        val fmt = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es"))
        val first = LocalDate.ofEpochDay(firstDay).format(fmt)
        val last = LocalDate.ofEpochDay(firstDay + days - 1).format(fmt)
        Text(
            (if (kind == TripKind.PAUSE) "Pausa" else "Otro lugar") + (if (firstDay > today.toEpochDay()) " desde tu próxima alarma ($first)" else "") + " hasta el final del $last.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )

        if (kind == TripKind.ANCHOR) {
            Text("¿Dónde te apagarás?", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            state.anchors.forEach { anchor ->
                Row(
                    modifier = Modifier.fillMaxWidth().selectable(selected = anchor.id == anchorId, role = Role.RadioButton) { anchorId = anchor.id },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = anchor.id == anchorId, onClick = null)
                    Column(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                        Text(anchor.label)
                        Text(if (anchor is PhotoAnchor) "Foto del lugar" else "Código QR", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
            NewAnchorButtons { newAnchor = it }
            Text(
                "Consejo: antes de salir, crea un QR nuevo, compártelo para imprimirlo y llévalo en la maleta. O ya allí, haz una foto de un rincón del baño del hotel.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                val trip = TripPolicy.start(firstDay, days, if (kind == TripKind.ANCHOR) anchorId else null)
                try {
                    val events = AlarmActions.startTrip(context, trip)
                    announce((listOf("Modo viaje activado") + GameMessages.describe(events)).joinToString(" · "), null, null)
                    onClose()
                } catch (_: IOException) {
                    error = "No se pudo guardar el viaje."
                }
            },
            enabled = kind == TripKind.PAUSE || (anchorId != null && state.anchor(anchorId) != null),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Activar modo viaje", fontSize = 16.sp) }
    }
}

@Composable
private fun TripKindRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 10.dp))
        Column(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(title)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}
