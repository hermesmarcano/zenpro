package com.hermesmarcano.zen.layer.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hermesmarcano.zen.layer.capture.AccessibilityHealer
import com.hermesmarcano.zen.layer.capture.AmbientAudioService
import com.hermesmarcano.zen.layer.capture.LayerIntents
import com.hermesmarcano.zen.layer.capture.LayerRuntime
import com.hermesmarcano.zen.layer.capture.LayerScheduler
import com.hermesmarcano.zen.layer.capture.LayerStatus
import com.hermesmarcano.zen.layer.capture.layer
import com.hermesmarcano.zen.layer.core.CaptureSettings
import com.hermesmarcano.zen.layer.core.HealthItem
import com.hermesmarcano.zen.layer.core.LayerHealth
import com.hermesmarcano.zen.layer.core.LayerState
import com.hermesmarcano.zen.layer.core.Severity
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/** Lo que comparten las secciones de la pantalla: dónde guardar, cómo avisar al usuario. */
@Stable
internal class LayerEnv(val context: Context, val runtime: LayerRuntime, val now: () -> Long, val say: (String) -> Unit) {
    /** Guarda un cambio del estado. Si el disco falla, avisa y devuelve `false`. */
    fun save(transform: (LayerState) -> LayerState): Boolean {
        val ok = try {
            runtime.state.update(transform)
            true
        } catch (_: IOException) {
            say("No se pudo guardar el cambio.")
            false
        }
        try {
            LayerScheduler.sync(context)
        } catch (_: Exception) {
            // Sin trabajos periódicos la capa sigue funcionando; solo se retrasa el mantenimiento.
        }
        return ok
    }

    fun saveCapture(transform: (CaptureSettings) -> CaptureSettings): Boolean = save { it.copy(capture = transform(it.capture)) }
}

/**
 * La capa inteligente: qué observa ZEN, con qué límites, qué recuerda y con quién habla. Todo empieza apagado y cada
 * fuente se enciende por separado. El estado de arriba dice con franqueza qué falta para que funcione.
 */
@Composable
fun LayerScreen(
    onClose: () -> Unit,
    appLoader: (Context) -> List<AppEntry> = ::loadLauncherApps,
    clock: () -> Long = System::currentTimeMillis,
) {
    val context = LocalContext.current
    val runtime = context.layer
    val state by runtime.state.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val env = remember(context, clock) { LayerEnv(context, runtime, clock) { message = it } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val health = remember(state, refresh) { LayerHealth.evaluate(LayerStatus.healthInput(context)) }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Capa", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Listo") }
        }
        Hint(
            "ZEN puede observar tu teléfono para recordar lo que importa y cumplir los límites que tú pongas. " +
                "Todo empieza apagado, cada fuente se enciende por separado y nada sale del teléfono si no configuras un cerebro.",
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }

        HealthSection(env, health) { refresh++ }
        PauseSection(env, state)
        CaptureSection(env, state, health) { refresh++ }
        VetoSection(env, state, appLoader)
        LimitsSection(env, state, appLoader)
        DraftsSection(env, state)
        MemorySection(env, state)
        BrainSection(env, state)
        Hint("La guía de instalación y el protocolo con el cerebro están en docs/XIAOMI-LAYER.md.")
    }
}

@Composable
internal fun SectionTitle(text: String) = Text(text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))

@Composable
internal fun Hint(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

/** Un bloque con fondo propio para agrupar un elemento de una lista. */
@Composable
internal fun Panel(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Estado: qué falta para que la capa funcione
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun HealthSection(env: LayerEnv, health: List<HealthItem>, onChanged: () -> Unit) {
    val state by env.runtime.state.state.collectAsState()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    SectionTitle("Estado")
    val problems = health.filter { !it.ok }
    if (problems.isEmpty() && health.isNotEmpty()) Hint("Todo en orden con lo que tienes encendido.")
    if (health.isEmpty()) Hint("Enciende una fuente o añade un límite y aquí verás qué hace falta para que funcione.")
    for (item in health) {
        val color = when {
            item.ok -> MaterialTheme.colorScheme.onSurfaceVariant
            item.severity == Severity.BLOCKER -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text((if (item.ok) "✓ " else if (item.severity == Severity.INFO) "· " else "! ") + item.title, color = color, fontWeight = FontWeight.Medium)
            if (item.hint.isNotEmpty()) Hint(item.hint)
            if (!item.ok) {
                when (item.id) {
                    "accessibility" -> ActionButton("Abrir accesibilidad") { LayerIntents.openFirst(env.context, LayerIntents.accessibility()) }
                    "listener" -> ActionButton("Abrir acceso a notificaciones") { LayerIntents.openFirst(env.context, LayerIntents.notificationAccess()) }
                    "mic" -> ActionButton("Dar permiso del micrófono") { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
                    "battery" -> ActionButton("Quitar la restricción") {
                        LayerIntents.openFirst(env.context, LayerIntents.xiaomiBattery(env.context), LayerIntents.batteryExemption(env.context))
                    }
                    "autostart" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("Abrir inicio automático") { LayerIntents.openFirst(env.context, LayerIntents.xiaomiAutostart()) }
                        ActionButton("Ya lo hice") { env.save { it.copy(autostartConfirmed = true) } }
                    }
                    "secure_settings" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val command = adbGrantCommand(env.context.packageName)
                        Text(command, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ActionButton("Copiar el comando") { copy(env.context, command); env.say("Comando copiado.") }
                    }
                }
            }
        }
    }
    state.lastSyncError.takeIf { it.isNotBlank() }?.let { Hint("Último error del cerebro: $it") }
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit) = OutlinedButton(onClick = onClick) { Text(text) }

/** El comando depende del ID de la app (esta variante no usa el de la app normal): por eso se calcula. */
internal fun adbGrantCommand(packageName: String) = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("ZEN", text))
}

// ---------------------------------------------------------------------------------------------------------------------
// Pausa
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun PauseSection(env: LayerEnv, state: LayerState) {
    val now = env.now()
    val paused = state.capture.isPaused(now)
    SectionTitle("Pausa")
    Hint("Mientras está en pausa no se lee ni se escucha nada. Los límites y bloqueos que pusiste siguen valiendo.")
    if (paused) {
        val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.capture.pausedUntilMs))
        Text("En pausa hasta las $until.", fontWeight = FontWeight.Medium)
        OutlinedButton(onClick = { env.saveCapture { it.copy(pausedUntilMs = 0L) } }) { Text("Reanudar") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, minutes) in listOf("15 min" to 15, "1 hora" to 60, "Hasta mañana" to -1)) {
                OutlinedButton(
                    onClick = {
                        val until = if (minutes > 0) now + minutes * 60_000L else env.runtime.dayClock.nextDayStart(now)
                        env.saveCapture { it.copy(pausedUntilMs = until) }
                    },
                ) { Text(label) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Qué observa
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun CaptureSection(env: LayerEnv, state: LayerState, health: List<HealthItem>, onChanged: () -> Unit) {
    val capture = state.capture
    var startAudioAfterPermission by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onChanged()
        if (granted && startAudioAfterPermission) startAudio(env)
        startAudioAfterPermission = false
    }
    SectionTitle("Qué observa")

    SourceRow(
        "Texto de la pantalla",
        "Lee lo que ves en las apps permitidas para poder recordarlo y aplicar tus límites. Nunca lee contraseñas, bancos, gestores de contraseñas ni Ajustes. Necesita el servicio de accesibilidad.",
        capture.screenText,
    ) { on ->
        env.saveCapture { it.copy(screenText = on) }
        if (on && !AccessibilityHealer.isListed(env.context)) LayerIntents.openFirst(env.context, LayerIntents.accessibility())
    }
    SourceRow(
        "Notificaciones",
        "Guarda los mensajes y avisos de las apps permitidas (sin códigos de un solo uso) y permite proponerte respuestas que solo se envían si las apruebas.",
        capture.notifications,
    ) { on ->
        env.saveCapture { it.copy(notifications = on) }
        if (on && !LayerStatus.isListenerEnabled(env.context)) LayerIntents.openFirst(env.context, LayerIntents.notificationAccess())
    }
    SourceRow(
        "Micrófono",
        "Escucha en segundo plano y guarda solo los tramos en que se habla, para que el cerebro los transcriba. Hay un aviso fijo mientras escucha. Necesita un cerebro configurado. Mira antes la ley de tu país sobre grabar conversaciones de otras personas.",
        capture.ambientAudio,
    ) { on ->
        when {
            !on -> {
                env.saveCapture { it.copy(ambientAudio = false) }
                AmbientAudioService.stop(env.context)
            }
            !state.brainConfigured -> env.say("Primero configura el cerebro: sin él no hay quien transcriba el audio.")
            !LayerStatus.hasMicrophone(env.context) -> {
                startAudioAfterPermission = true
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> startAudio(env)
        }
    }
    if (health.any { it.id == "mic" && !it.ok } && capture.ambientAudio) Hint("Falta el permiso del micrófono (arriba, en Estado).")
}

private fun startAudio(env: LayerEnv) {
    // Primero se guarda (las precondiciones del servicio leen el estado) y después se arranca.
    if (!env.saveCapture { it.copy(ambientAudio = true) }) return
    when (AmbientAudioService.start(env.context)) {
        AmbientAudioService.StartCheck.OK -> env.say("Escuchando. Verás un aviso fijo mientras el micrófono esté activo.")
        AmbientAudioService.StartCheck.NO_BRAIN -> env.say("Primero configura el cerebro.")
        AmbientAudioService.StartCheck.NO_PERMISSION -> env.say("Falta el permiso del micrófono.")
        AmbientAudioService.StartCheck.SOURCE_OFF -> Unit
    }
}

@Composable
private fun SourceRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Hint(description)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

