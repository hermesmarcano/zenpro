package com.hermesmarcano.zen.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesmarcano.zen.camera.CameraView
import com.hermesmarcano.zen.camera.QrAnalyzer
import com.hermesmarcano.zen.core.BackupCodePolicy
import com.hermesmarcano.zen.data.Anchor
import com.hermesmarcano.zen.data.QrAnchor
import com.hermesmarcano.zen.data.matchesBackupCode

private enum class QrStep { LABEL, SHOW, SCANNING, DENIED, BACKUP }

/** Alta de un ancla por QR: nombre -> mostrar/compartir el código -> escanearlo ya pegado -> listo. */
@Composable
fun QrAnchorSetup(onDone: (Anchor) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(QrStep.LABEL) }
    var label by remember { mutableStateOf("") }
    var anchor by remember { mutableStateOf<QrAnchor?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var backupInput by remember { mutableStateOf("") }
    var backupError by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        step = if (granted) QrStep.SCANNING else QrStep.DENIED
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (step) {
            QrStep.LABEL -> {
                Text("¿Dónde vas a pegar el código?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    label = { Text("Ej. Espejo del baño") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { anchor = QrAnchor.new(label.trim()); step = QrStep.SHOW },
                    enabled = label.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Generar código") }
            }

            QrStep.SHOW -> {
                val current = anchor ?: return@Column
                val qr = remember(current.id) { PrintableAnchor.qrBitmap(current.payload) }
                Text("Imprime este código y pégalo en un punto fijo, fuera de tu cuarto.", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Image(
                    bitmap = qr.asImageBitmap(),
                    filterQuality = FilterQuality.None,
                    contentDescription = "Código QR de ${current.label}",
                    modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
                )
                Text(
                    "Código de respaldo: ${current.backupCode}",
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Ya va impreso en la hoja, junto al QR. Es la salida si falla la cámara, y no se habilita hasta pasados unos minutos de alarma.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { PrintableAnchor.share(context, PrintableAnchor.sheet(current.label, current.backupCode, current.payload), current.label) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Compartir para imprimir") }
                Button(
                    onClick = { scanError = null; cameraLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ya lo pegué — confirmar ubicación") }
                TextButton(onClick = { step = QrStep.BACKUP }, modifier = Modifier.fillMaxWidth()) { Text("No puedo escanear ahora") }
            }

            QrStep.SCANNING -> {
                val current = anchor ?: return@Column
                val analyzer = remember(current.id) {
                    QrAnalyzer { payload ->
                        if (current.matches(payload)) {
                            torchOn = false
                            onDone(current)
                        } else {
                            scanError = "Ese no es el código de ZEN que generaste. Apunta al que imprimiste."
                        }
                    }
                }
                Text("Apunta al código pegado en ${current.label}.", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                CameraView(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
                    analyzer = analyzer,
                    torchOn = torchOn,
                    onUnavailable = { step = QrStep.DENIED },
                )
                scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { torchOn = !torchOn }, modifier = Modifier.weight(1f)) {
                        Text(if (torchOn) "Apagar linterna" else "Linterna")
                    }
                    OutlinedButton(onClick = { torchOn = false; step = QrStep.BACKUP }, modifier = Modifier.weight(1f)) {
                        Text("Código de respaldo")
                    }
                }
            }

            QrStep.DENIED -> {
                Text("No tenemos acceso a la cámara.", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Actívala en Ajustes, o confirma por ahora con el código de respaldo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { context.startActivity(Permissions.appSettings(context)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Abrir Ajustes")
                }
                Button(onClick = { step = QrStep.BACKUP }, modifier = Modifier.fillMaxWidth()) { Text("Usar código de respaldo") }
            }

            QrStep.BACKUP -> {
                val current = anchor ?: return@Column
                Text("Escribe el código de respaldo", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = backupInput,
                    onValueChange = { backupInput = BackupCodePolicy.sanitize(it); backupError = false },
                    label = { Text("000000") },
                    singleLine = true,
                    isError = backupError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (backupError) Text("Ese código no coincide.", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                Button(
                    onClick = { if (current.matchesBackupCode(backupInput)) onDone(current) else backupError = true },
                    enabled = backupInput.length == BackupCodePolicy.LENGTH,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Confirmar") }
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Cancelar") }
    }
}
