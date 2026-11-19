package com.hermesmarcano.zen.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hermesmarcano.zen.alarm.AlarmActions
import com.hermesmarcano.zen.core.AlarmHealth
import com.hermesmarcano.zen.core.RingCountdownFormatter
import com.hermesmarcano.zen.core.TripPolicy
import com.hermesmarcano.zen.core.Weekday
import com.hermesmarcano.zen.data.AlarmEntity
import com.hermesmarcano.zen.data.WakeRecorder
import com.hermesmarcano.zen.data.ZenState
import com.hermesmarcano.zen.zenApp
import java.io.IOException

@Composable
fun HomeScreen(
    onNewAlarm: () -> Unit,
    onEditAlarm: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenTrip: () -> Unit = {},
    onOpenReliability: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by context.zenApp.repository.state.collectAsState()
    val log by context.zenApp.alarmLog.events.collectAsState()
    var permissionTick by remember { mutableIntStateOf(0) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Al volver a la app la hora se relee al instante: tras horas en segundo plano, la cuenta atrás no puede enseñar la de anoche.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionTick++
        nowMillis = System.currentTimeMillis()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    val problems = remember(log, nowMillis, state.settings.diagnosticsAckAt) { AlarmHealth.problems(log, nowMillis, state.settings.diagnosticsAckAt) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Alarmas", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenSettings) { Text("Ajustes") }
            }
            Text(
                "Una alarma que solo se apaga yendo al lugar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            val streak = WakeRecorder.currentStreak(state, java.time.LocalDate.now())
            if (streak > 0) {
                Text(
                    "Racha: ${daysText(streak)}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (problems.isNotEmpty()) {
            item(key = "problems") {
                ProblemsCard(
                    problem = problems.last(),
                    count = problems.size,
                    onOpen = onOpenReliability,
                    onDismiss = {
                        try {
                            context.zenApp.repository.update { it.copy(settings = it.settings.copy(diagnosticsAckAt = System.currentTimeMillis())) }
                        } catch (_: IOException) {
                        }
                    },
                )
            }
        }
        item { PermissionsCard(permissionTick) }
        item { TripCard(state, onOpenTrip) }
        if (state.alarms.isEmpty()) {
            item {
                Text(
                    "Aún no tienes alarmas. Crea una y elige el lugar donde tendrás que ir para apagarla.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(state.alarms.sortedWith(compareBy({ it.hour }, { it.minute })), key = { it.id }) { alarm ->
            AlarmCard(alarm, state, nowMillis, onEdit = { onEditAlarm(alarm.id) }, onToggle = { enabled ->
                if (enabled && state.anchor(alarm.anchorId) == null) {
                    onEditAlarm(alarm.id)
                } else {
                    try {
                        val result = AlarmActions.setEnabled(context, alarm.id, enabled)
                        if (enabled && result != null && !result.enabled) {
                            Toast.makeText(context, "Está en pausa por el modo viaje y no sonará.", Toast.LENGTH_LONG).show()
                        }
                    } catch (_: IOException) {
                        Toast.makeText(context, "No se pudo guardar el cambio.", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
        item {
            Button(
                onClick = onNewAlarm,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Nueva alarma", fontSize = 16.sp) }
        }
        item(key = "reliability") {
            TextButton(onClick = onOpenReliability, modifier = Modifier.fillMaxWidth()) { Text("¿Suena a su hora? Fiabilidad de la alarma") }
        }
    }
}

@Composable
private fun AlarmCard(alarm: AlarmEntity, state: ZenState, nowMillis: Long, onEdit: () -> Unit, onToggle: (Boolean) -> Unit) {
    val anchor = state.anchor(alarm.anchorId)
    val time = String.format(java.util.Locale.ROOT, "%02d:%02d", alarm.hour, alarm.minute)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    time,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                    color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(Weekday.summary(alarm.weekdays), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (anchor == null) "Falta elegir el lugar" else "Se apaga en: ${anchor.label}",
                    color = if (anchor == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
                val next = alarm.nextFireAt
                if (alarm.enabled && next != null) {
                    if (AlarmHealth.isOverdue(alarm, nowMillis)) {
                        // Pasó su hora y el aviso nunca se procesó: no se puede decir "suena en menos de 1 min".
                        Text("No sonó a su hora (${String.format(java.util.Locale.ROOT, "%02d:%02d", alarm.hour, alarm.minute)}).", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                    } else {
                        Text(
                            RingCountdownFormatter.format(next - nowMillis),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
            Switch(
                checked = alarm.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = "Alarma de las $time" },
            )
        }
    }
}

/** "Una alarma no sonó a su hora": lo que pasó y cómo evitarlo. */
@Composable
private fun ProblemsCard(problem: AlarmHealth.Event, count: Int, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val zone = java.time.ZoneId.systemDefault()
    val expected = problem.expectedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) }
    val late = AlarmHealth.latenessMs(problem)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (problem.kind == AlarmHealth.Kind.MISSED) "Una alarma no sonó a su hora" else "Una alarma llegó tarde",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                (if (problem.kind == AlarmHealth.Kind.MISSED) "La de las ${expected ?: "?"} no llegó a procesarse. Suele pasar cuando el teléfono frena a ZEN en segundo plano." else "La de las ${expected ?: "?"} llegó ${late?.let { AlarmHealth.latenessText(it) } ?: "tarde"}.") +
                    if (count > 1) " ($count avisos)" else "",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row {
                TextButton(onClick = onOpen) { Text("Cómo arreglarlo") }
                TextButton(onClick = onDismiss) { Text("Entendido") }
            }
        }
    }
}

@Composable
private fun PermissionsCard(tick: Int) {
    val context = LocalContext.current
    // `tick` fuerza a releer los permisos al volver de Ajustes.
    val hasCamera = remember(tick) { Permissions.hasCamera(context) }
    val hasNotifications = remember(tick) { Permissions.hasNotifications(context) }
    val hasFullScreen = remember(tick) { Permissions.hasFullScreenIntent(context) }
    var requested by remember { mutableIntStateOf(0) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requested++ }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requested++ }
    // `requested` solo fuerza la recomposición tras responder al diálogo del sistema.
    @Suppress("UNUSED_EXPRESSION") requested
    val cameraNow = hasCamera || Permissions.hasCamera(context)
    val notificationsNow = hasNotifications || Permissions.hasNotifications(context)

    if (cameraNow && notificationsNow && hasFullScreen) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Para que la alarma funcione bien", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (!cameraNow) {
                PermissionRow("Cámara: necesaria para escanear el QR o tomar la foto.", "Permitir") {
                    cameraLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            if (!notificationsNow) {
                PermissionRow("Notificaciones: sin ellas Android puede ocultar la alarma.", "Permitir") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            if (!hasFullScreen) {
                PermissionRow("Pantalla completa: para que la alarma aparezca con el teléfono bloqueado.", "Abrir ajustes") {
                    context.startActivity(Permissions.fullScreenIntentSettings(context))
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(text: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 14.sp)
        Spacer(Modifier.padding(4.dp))
        TextButton(onClick = onClick) { Text(action) }
    }
}


/** Acceso al modo viaje: qué está activo o qué se puede hacer. */
@Composable
private fun TripCard(state: ZenState, onOpen: () -> Unit) {
    val today = java.time.LocalDate.now().toEpochDay()
    val trip = state.trip?.takeIf { TripPolicy.isActive(it, today) }
    val until = trip?.let { java.time.LocalDate.ofEpochDay(it.untilEpochDay).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale("es"))) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (trip != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when {
                    trip == null -> "Modo viaje"
                    trip.isPause -> "Alarmas en pausa hasta el $until"
                    else -> "Viajando hasta el $until"
                },
                fontWeight = FontWeight.SemiBold,
                color = if (trip != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when {
                    trip == null -> "Pausa las alarmas o apágalas en otro lugar mientras estás fuera."
                    trip.isPause -> "No suenan y tu racha no se rompe. Toca para terminar la pausa."
                    else -> "Se apagan en ${state.anchor(trip.anchorId)?.label ?: "otro lugar"}. Toca para gestionarlo."
                },
                fontSize = 14.sp,
                color = if (trip != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
