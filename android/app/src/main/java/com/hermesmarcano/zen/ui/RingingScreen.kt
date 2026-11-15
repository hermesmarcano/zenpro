package com.hermesmarcano.zen.ui

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hermesmarcano.zen.alarm.AlarmRuntime
import com.hermesmarcano.zen.alarm.Notifications
import com.hermesmarcano.zen.camera.CameraView
import com.hermesmarcano.zen.camera.QrAnalyzer
import com.hermesmarcano.zen.core.BackupCodePolicy
import com.hermesmarcano.zen.core.PhotoMatchPolicy
import com.hermesmarcano.zen.core.PhotoVerifier
import com.hermesmarcano.zen.data.PhotoAnchor
import com.hermesmarcano.zen.data.QrAnchor
import com.hermesmarcano.zen.data.matchesBackupCode
import com.hermesmarcano.zen.photo.PhotoStore
import com.hermesmarcano.zen.photo.toGrayImage
import com.hermesmarcano.zen.zenApp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RingingScreen(onSolved: () -> Unit, onNothingRinging: () -> Unit) {
    val context = LocalContext.current
    val ring by AlarmRuntime.ring.collectAsState()
    val state by context.zenApp.repository.state.collectAsState()
    val onNothing by rememberUpdatedState(onNothingRinging)

    val current = ring
    if (current == null) {
        // El servicio ya paró (o esta pantalla se abrió sin alarma): nada que mostrar.
        // Pequeño margen: la pantalla puede abrirse un instante antes de que el servicio publique el estado.
        LaunchedEffect(Unit) {
            delay(1_500)
            // Un aviso de emergencia (si el servicio no pudo arrancar) ya no tiene a quién avisar.
            Notifications.cancelAll(context)
            onNothing()
        }
        return
    }

    val anchor = state.anchor(current.anchorId)
    val scope = rememberCoroutineScope()

    var camera by remember { mutableStateOf(false) } // el usuario abrió el escáner / la cámara
    var torchOn by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var photoFailures by remember { mutableIntStateOf(0) }
    var analyzing by remember { mutableStateOf(false) }
    var backupInput by remember { mutableStateOf("") }
    var backupError by remember { mutableStateOf(false) }
    var hasCamera by remember { mutableStateOf(Permissions.hasCamera(context)) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        if (granted) camera = true else message = "Sin permiso de cámara. Usa el código de respaldo cuando se habilite."
    }

    // Al salir de la pantalla (Inicio, bloqueo) CameraX suelta la cámara: no seguir contando como "en mano".
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { camera = false; torchOn = false }

    // Con la cámara abierta el teléfono está "en mano" aunque esté quieto (ver SmartVolumeController).
    DisposableEffect(camera) {
        AlarmRuntime.userEngaged = camera
        onDispose { AlarmRuntime.userEngaged = false }
    }

    val now by produceState(SystemClock.elapsedRealtime() / 1000.0) {
        while (true) {
            value = SystemClock.elapsedRealtime() / 1000.0
            delay(500)
        }
    }
    val clock by produceState(LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(1_000)
        }
    }
    val backupRemaining = BackupCodePolicy.remaining(current.startedAtElapsed, now)
    val backupAvailable = backupRemaining == 0.0

    // Fase de cámara a pantalla completa para la foto.
    if (camera && anchor is PhotoAnchor) {
        PhotoCapture(
            prompt = "Toma la foto del lugar, como en la referencia. Sostén el teléfono en vertical.",
            cancelLabel = "Volver",
            onCaptured = { bitmap ->
                camera = false
                analyzing = true
                message = null
                scope.launch {
                    val outcome = withContext(Dispatchers.Default) {
                        val candidate = bitmap.toGrayImage()
                        val refs = anchor.references.mapNotNull { PhotoStore.loadGray(context, anchor.id, it) }
                        PhotoVerifier.verify(candidate, refs)
                    }
                    analyzing = false
                    when (outcome) {
                        is PhotoVerifier.Outcome.Compared -> if (outcome.isMatch) {
                            onSolved()
                        } else {
                            photoFailures++
                            message = "No coincide con ${anchor.label}. Colócate en el mismo punto, en vertical."
                        }
                        PhotoVerifier.Outcome.Unreadable -> {
                            photoFailures++
                            message = "No se pudo analizar la foto (muy oscura o tapada). Enciende la linterna e inténtalo de nuevo."
                        }
                    }
                }
            },
            onCancel = { camera = false },
            onUnavailable = { camera = false; message = "La cámara no está disponible. Usa el código de respaldo cuando se habilite." },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            clock.format(DateTimeFormatter.ofPattern("HH:mm")),
            fontSize = 72.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            when (anchor) {
                null -> "Es hora de levantarse."
                else -> "Ve a: ${anchor.label}"
            },
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )

        if (analyzing) {
            CircularProgressIndicator()
            Text("Analizando…")
        }

        when {
            anchor is QrAnchor && camera -> {
                val analyzer = remember(anchor.id) {
                    QrAnalyzer { payload ->
                        if (anchor.matches(payload)) {
                            torchOn = false
                            onSolved()
                        } else {
                            message = "Ese no es el código de ${anchor.label}. Busca el que pegaste."
                        }
                    }
                }
                CameraView(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
                    analyzer = analyzer,
                    torchOn = torchOn,
                    onUnavailable = { camera = false; message = "La cámara no está disponible. Usa el código de respaldo cuando se habilite." },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { torchOn = !torchOn }, modifier = Modifier.weight(1f)) {
                        Text(if (torchOn) "Apagar linterna" else "Linterna")
                    }
                    OutlinedButton(onClick = { torchOn = false; camera = false }, modifier = Modifier.weight(1f)) { Text("Cerrar cámara") }
                }
            }

            anchor != null && !camera && !analyzing -> {
                Button(
                    onClick = { message = null; if (hasCamera) camera = true else cameraLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(if (anchor is QrAnchor) "Escanear el código" else "Tomar la foto del lugar", fontSize = 18.sp) }
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        if (PhotoMatchPolicy.shouldShowHint(photoFailures) && anchor is PhotoAnchor) {
            Text(
                "Si sigue sin coincidir, ponte exactamente donde tomaste la referencia o usa el código de respaldo.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
            )
        }

        // Salida de emergencia, con espera para que no sea un atajo desde la cama.
        if (!camera) {
            if (anchor == null) {
                if (backupAvailable) {
                    Button(onClick = onSolved, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Apagar alarma") }
                } else {
                    Text(
                        "Podrás apagarla en ${BackupCodePolicy.countdownText(backupRemaining)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (backupAvailable) {
                OutlinedTextField(
                    value = backupInput,
                    onValueChange = { backupInput = BackupCodePolicy.sanitize(it); backupError = false },
                    label = { Text("Código de respaldo") },
                    singleLine = true,
                    isError = backupError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (backupError) Text("Ese código no coincide.", color = MaterialTheme.colorScheme.error)
                OutlinedButton(
                    onClick = { if (anchor.matchesBackupCode(backupInput)) onSolved() else backupError = true },
                    enabled = backupInput.length == BackupCodePolicy.LENGTH,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Apagar con el código") }
            } else {
                Text(
                    "Código de respaldo disponible en ${BackupCodePolicy.countdownText(backupRemaining)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
