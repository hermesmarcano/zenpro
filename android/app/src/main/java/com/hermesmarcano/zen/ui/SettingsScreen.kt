package com.hermesmarcano.zen.ui

import android.widget.Toast
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesmarcano.zen.alarm.SoundPreview
import com.hermesmarcano.zen.core.AlarmSound
import com.hermesmarcano.zen.core.CompletionSound
import com.hermesmarcano.zen.data.SoundChoice
import com.hermesmarcano.zen.data.WakeRecorder
import com.hermesmarcano.zen.data.ZenSettings
import com.hermesmarcano.zen.sound.SoundStorage
import com.hermesmarcano.zen.zenApp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sonido de la alarma, volumen inicial, sonido de éxito y estadísticas de racha. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val repository = context.zenApp.repository
    val state by repository.state.collectAsState()
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val preview = remember { SoundPreview(context) }
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { preview.stop() } }

    fun save(transform: (ZenSettings) -> ZenSettings) {
        try {
            repository.update { it.copy(settings = transform(it.settings)) }
            // Un archivo que ningún ajuste usa ya no hace falta. No se limpia mientras se importa otro:
            // el recién copiado aún no está en los ajustes y se borraría como "huérfano".
            if (!importing) SoundStorage.deleteOrphans(context, repository.state.value)
        } catch (_: IOException) {
            Toast.makeText(context, "No se pudo guardar el ajuste.", Toast.LENGTH_LONG).show()
        }
    }

    fun import(uri: android.net.Uri, role: String) {
        importing = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { SoundStorage.import(context, uri, role) }
            importing = false
            when (result) {
                is SoundStorage.ImportResult.Failure -> message = result.reason
                is SoundStorage.ImportResult.Success ->
                    if (role == "alarm") save { it.copy(alarmSound = result.choice) } else save { it.copy(completionSound = result.choice) }
            }
        }
    }

    val alarmPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) import(uri, "alarm") }
    val completionPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) import(uri, "done") }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ajustes", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Listo") }
        }

        // --- Sonido de la alarma ---
        Text("Sonido de la alarma", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        for (sound in AlarmSound.entries) {
            SoundRow(
                title = sound.title,
                subtitle = sound.hint,
                selected = (settings.alarmSound as? SoundChoice.Preset)?.id == sound.id,
            ) { save { it.copy(alarmSound = SoundChoice.Preset(sound.id)) } }
        }
        val customAlarm = settings.alarmSound as? SoundChoice.Custom
        SoundRow(
            title = "Mi archivo",
            subtitle = customAlarm?.displayName ?: "Elige una canción o un audio de tu teléfono",
            selected = customAlarm != null,
        ) { if (customAlarm == null) alarmPicker.launch(arrayOf("audio/*")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { alarmPicker.launch(arrayOf("audio/*")) }, enabled = !importing, modifier = Modifier.weight(1f)) {
                Text(if (customAlarm == null) "Elegir archivo" else "Cambiar archivo")
            }
            OutlinedButton(
                onClick = { preview.playAlarm(settings.alarmSound, settings.startVolume) },
                modifier = Modifier.weight(1f),
            ) { Text("Probar") }
        }
        if (customAlarm != null) {
            Text("Si eliges otro sonido, ZEN descarta este archivo.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }

        // --- Volumen inicial ---
        Text("Volumen inicial", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
        Text(
            "La alarma empieza así de bajito y sube durante un minuto y medio. Al coger el teléfono baja un poco, y si lo dejas quieto sin apagarla vuelve a subir. Es volumen percibido: 15 % se oye como una séptima parte del máximo.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (percent in ZenSettings.START_VOLUME_OPTIONS) {
                FilterChip(
                    selected = settings.startVolumePercent == percent,
                    onClick = { save { it.copy(startVolumePercent = percent) } },
                    label = { Text("$percent %") },
                )
            }
        }
        Text(
            "\"Probar\" usa el volumen de alarmas que tiene ahora el teléfono. Al sonar de verdad, ZEN lo mantiene al menos al 60 % del máximo.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )

        // --- Sonido de éxito ---
        Text("Al despertar", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
        Text("Suena cuando verificas el lugar y apagas la alarma.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        for (sound in CompletionSound.entries) {
            SoundRow(
                title = sound.title,
                subtitle = null,
                selected = (settings.completionSound as? SoundChoice.Preset)?.id == sound.id,
            ) { save { it.copy(completionSound = SoundChoice.Preset(sound.id)) } }
        }
        val customDone = settings.completionSound as? SoundChoice.Custom
        SoundRow(
            title = "Mi archivo",
            subtitle = customDone?.displayName ?: "Se corta a los 10 segundos",
            selected = customDone != null,
        ) { if (customDone == null) completionPicker.launch(arrayOf("audio/*")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { completionPicker.launch(arrayOf("audio/*")) }, enabled = !importing, modifier = Modifier.weight(1f)) {
                Text(if (customDone == null) "Elegir archivo" else "Cambiar archivo")
            }
            OutlinedButton(onClick = { preview.playCompletion(settings.completionSound) }, modifier = Modifier.weight(1f)) { Text("Probar") }
        }
        if (customDone != null) {
            Text("Si eliges otro sonido, ZEN descarta este archivo.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }

        // --- Racha ---
        Text("Racha", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
        val streak = WakeRecorder.currentStreak(state, java.time.LocalDate.now())
        StatRow("Racha actual", daysText(streak))
        StatRow("Mejor racha", daysText(state.stats.bestStreak))
        StatRow("Despertares verificados", state.stats.totalWakeups.toString())
        Text(
            "Solo cuenta llegar al lugar (QR o foto). El código de respaldo no suma ni rompe la racha, y los días sin alarma programada no la cortan.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun SoundRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(title)
            if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
