package com.hermesmarcano.zen.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hermesmarcano.zen.alarm.AlarmActions
import com.hermesmarcano.zen.core.AlarmHealth
import com.hermesmarcano.zen.zenApp
import java.time.ZoneId
import kotlinx.coroutines.delay

/**
 * Fiabilidad de la alarma: una prueba de 1 minuto por el mismo camino que las alarmas reales, los ajustes del teléfono
 * que más veces la retrasan (batería, inicio automático) y el registro de lo que ha pasado con cada alarma.
 */
@Composable
fun ReliabilityScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val log by context.zenApp.alarmLog.events.collectAsState()
    val zone = ZoneId.systemDefault()

    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(resumeTick) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val pending = AlarmHealth.pendingTest(log, now)
    val failed = AlarmHealth.testFailed(log, now)
    val last = AlarmHealth.lastTestResult(log)
    val ignoring = remember(resumeTick) { Permissions.isIgnoringBatteryOptimizations(context) }

    fun open(intent: Intent, fallback: Intent = Permissions.appSettings(context)) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(fallback)
            } catch (_: Exception) {
                Toast.makeText(context, "No se pudo abrir esa pantalla de ajustes.", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Atrás") }
            Text("Fiabilidad de la alarma", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Prueba de 1 minuto", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "ZEN programa un aviso por el mismo camino que las alarmas reales. Bloquea el teléfono y espera: " +
                                "debe llegar una notificación a tiempo. Si tarda o no llega, el teléfono está frenando a ZEN.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                        when {
                            pending != null -> {
                                val seconds = ((pending - now) / 1000).coerceAtLeast(0)
                                Text("Prueba en curso: llega a las ${java.time.Instant.ofEpochMilli(pending).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))} (en $seconds s). Bloquea el teléfono.", fontWeight = FontWeight.SemiBold)
                                OutlinedButton(onClick = { AlarmActions.cancelSelfTest(context) }, modifier = Modifier.fillMaxWidth()) { Text("Cancelar prueba") }
                            }
                            else -> {
                                if (failed) {
                                    Text("La última prueba no llegó. El teléfono bloquea o retrasa las alarmas de ZEN: sigue los pasos de abajo y repite la prueba.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                                } else if (last != null) {
                                    val late = AlarmHealth.isLate(last)
                                    Text(
                                        "Última prueba: " + (AlarmHealth.latenessMs(last)?.let { AlarmHealth.latenessText(it) } ?: "recibida"),
                                        color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Button(onClick = { AlarmActions.startSelfTest(context) }, modifier = Modifier.fillMaxWidth()) { Text("Empezar prueba") }
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Que el teléfono no la retrase", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Muchos teléfonos (sobre todo Xiaomi, Redmi y POCO) congelan las apps en segundo plano por la noche y entregan la alarma tarde o nunca. " +
                                "Estos ajustes lo evitan:",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                        Step(
                            done = ignoring,
                            text = if (ignoring) "Sin ahorro de batería: ya está permitido." else "Permitir que ZEN funcione siempre en segundo plano.",
                            action = if (ignoring) "Ver" else "Permitir",
                        ) { open(if (ignoring) Permissions.batterySettings() else Permissions.requestIgnoreBatteryOptimizations(context), Permissions.batterySettings()) }
                        if (Permissions.isXiaomiFamily()) {
                            Step(done = null, text = "Xiaomi: «Inicio automático» activado para ZEN.", action = "Abrir") { open(Permissions.xiaomiAutostart()) }
                            Step(done = null, text = "Xiaomi: ahorro de batería de ZEN en «Sin restricciones».", action = "Abrir") { open(Permissions.xiaomiBatterySaver(context)) }
                            Text("Xiaomi: en la lista de apps recientes, mantén pulsada la tarjeta de ZEN y toca el candado para fijarla.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(
                                "Si tu teléfono tiene un ahorro de batería propio (Samsung, Huawei, OPPO, Vivo…), pon a ZEN en «Sin restricciones» y no la cierres deslizándola desde las apps recientes.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Step(done = null, text = "Ajustes de la aplicación (permisos, batería, notificaciones).", action = "Abrir") { open(Permissions.appSettings(context)) }
                        Text(
                            "No cierres ZEN deslizándola desde las apps recientes: en algunos teléfonos eso cancela las alarmas.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Text("Registro", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Lo que ha pasado con tus alarmas (lo más reciente, primero).", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (log.isEmpty()) item { Text("Aún no hay nada registrado.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val shown = log.asReversed().take(30)
            items(shown.size) { i ->
                val e = shown[i]
                val bad = e.kind == AlarmHealth.Kind.MISSED || AlarmHealth.isLate(e)
                Text(
                    AlarmHealth.describe(e, zone),
                    fontSize = 13.sp,
                    color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Step(done: Boolean?, text: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (done == true) "✓ " else "") + text,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            color = if (done == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onClick = onClick) { Text(action) }
    }
}
