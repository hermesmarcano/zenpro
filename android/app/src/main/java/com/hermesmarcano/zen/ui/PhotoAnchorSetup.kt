package com.hermesmarcano.zen.ui

import android.Manifest
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesmarcano.zen.core.PhotoMatchPolicy
import com.hermesmarcano.zen.core.PhotoVerifier
import com.hermesmarcano.zen.data.Anchor
import com.hermesmarcano.zen.data.PhotoAnchor
import com.hermesmarcano.zen.data.Secrets
import com.hermesmarcano.zen.photo.PhotoStore
import com.hermesmarcano.zen.photo.toGrayImage
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PhotoStep { LABEL, REFERENCES, CAPTURE_REFERENCE, VERIFY, CAPTURE_VERIFY, DENIED, DONE }

/**
 * Alta de un ancla por foto: nombre -> 1–3 fotos de referencia -> foto de verificación. La
 * verificación es obligatoria: si el umbral no funciona para ese lugar, el usuario lo descubre
 * aquí y no a las 6 a. m.
 */
@Composable
fun PhotoAnchorSetup(onDone: (Anchor) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val anchorId = remember { PhotoAnchor.newId() }
    val backupCode = remember { Secrets.backupCode() }
    val references = remember { mutableStateListOf<String>() }

    var step by remember { mutableStateOf(PhotoStep.LABEL) }
    var label by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }
    var verifyMessage by remember { mutableStateOf<String?>(null) }
    var storageError by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    // Si se abandona el alta, las fotos guardadas no deben quedar huérfanas.
    DisposableEffect(Unit) { onDispose { if (!finished) PhotoStore.deleteAnchor(context, anchorId) } }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        step = if (granted) PhotoStep.REFERENCES else PhotoStep.DENIED
    }

    if (step == PhotoStep.CAPTURE_REFERENCE) {
        PhotoCapture(
            prompt = "Encuadra el lugar desde donde vas a apagar la alarma. Sostén el teléfono en vertical.",
            onCaptured = { bitmap ->
                try {
                    references += PhotoStore.saveReference(context, anchorId, bitmap)
                    storageError = false
                } catch (_: IOException) {
                    storageError = true
                }
                step = PhotoStep.REFERENCES
            },
            onCancel = { step = PhotoStep.REFERENCES },
            onUnavailable = { step = PhotoStep.DENIED },
        )
        return
    }
    if (step == PhotoStep.CAPTURE_VERIFY) {
        PhotoCapture(
            prompt = "Toma la foto como lo harás al apagar la alarma.",
            onCaptured = { bitmap ->
                step = PhotoStep.VERIFY
                analyzing = true
                verifyMessage = null
                scope.launch {
                    val outcome = withContext(Dispatchers.Default) {
                        val candidate = bitmap.toGrayImage()
                        val refs = references.mapNotNull { PhotoStore.loadGray(context, anchorId, it) }
                        PhotoVerifier.verify(candidate, refs)
                    }
                    analyzing = false
                    when (outcome) {
                        is PhotoVerifier.Outcome.Unreadable ->
                            verifyMessage = "No se pudo analizar la foto (muy oscura o tapada). Inténtalo con más luz."
                        is PhotoVerifier.Outcome.Compared ->
                            if (outcome.isMatch) {
                                step = PhotoStep.DONE
                            } else {
                                verifyMessage = "No coincide con tus referencias. Prueba desde el mismo punto y en vertical, o añade otra referencia con la luz de esa hora.\n" +
                                    "distancia %.2f · umbral %.2f".format(outcome.bestDistance, PhotoMatchPolicy.DEFAULT_THRESHOLD)
                            }
                    }
                }
            },
            onCancel = { step = PhotoStep.VERIFY },
            onUnavailable = { step = PhotoStep.DENIED },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (step) {
            PhotoStep.LABEL -> {
                Text("¿Qué lugar vas a fotografiar?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Tendrás que volver ahí y tomar otra foto para apagar la alarma.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    label = { Text("Ej. Patio") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                    enabled = label.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continuar") }
            }

            PhotoStep.REFERENCES -> {
                Text("Fotos de referencia", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Toma una desde donde vas a estar al apagar la alarma. Añade otra con poca luz, como a la hora en que sonará (recomendado).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    references.forEach { name ->
                        val bitmap = remember(name) {
                            PhotoStore.file(context, anchorId, name)?.let { BitmapFactory.decodeFile(it.path) }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Foto de referencia",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(10.dp)),
                            )
                        }
                    }
                }
                if (storageError) Text("No se pudo guardar la foto. Revisa el espacio del teléfono.", color = MaterialTheme.colorScheme.error)
                if (references.size < PhotoAnchor.MAX_REFERENCES) {
                    OutlinedButton(onClick = { step = PhotoStep.CAPTURE_REFERENCE }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (references.isEmpty()) "Tomar foto" else "Añadir otra foto")
                    }
                }
                if (references.isNotEmpty()) {
                    TextButton(
                        onClick = { PhotoStore.delete(context, anchorId, references.last()); references.removeAt(references.lastIndex) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Quitar la última") }
                }
                Button(
                    onClick = { verifyMessage = null; step = PhotoStep.VERIFY },
                    enabled = references.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continuar") }
            }

            PhotoStep.VERIFY -> {
                Text("Comprueba que funciona", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Aléjate un momento y vuelve a tomar una foto del lugar, como lo harás cuando suene la alarma.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (analyzing) {
                    CircularProgressIndicator()
                    Text("Analizando…")
                } else {
                    Button(onClick = { step = PhotoStep.CAPTURE_VERIFY }, modifier = Modifier.fillMaxWidth()) {
                        Text("Tomar foto de verificación")
                    }
                }
                verifyMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                if (verifyMessage != null && !analyzing && references.size < PhotoAnchor.MAX_REFERENCES) {
                    OutlinedButton(onClick = { verifyMessage = null; step = PhotoStep.REFERENCES }, modifier = Modifier.fillMaxWidth()) {
                        Text("Añadir otra referencia")
                    }
                }
            }

            PhotoStep.DENIED -> {
                Text("No tenemos acceso a la cámara.", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("El ancla por foto necesita la cámara. Actívala en Ajustes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { context.startActivity(Permissions.appSettings(context)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Abrir Ajustes")
                }
            }

            PhotoStep.DONE -> {
                Text("Listo. Lugar confirmado.", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Código de respaldo: $backupCode", fontFamily = FontFamily.Monospace)
                Text(
                    "Anótalo en un papel y déjalo en ese lugar: así solo lo tendrás a mano estando allí. Es la salida si la cámara falla o la foto no coincide, y no se habilita hasta pasados unos minutos de alarma.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        finished = true
                        onDone(PhotoAnchor(anchorId, label.trim(), backupCode, references.toList()))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continuar") }
            }

            PhotoStep.CAPTURE_REFERENCE, PhotoStep.CAPTURE_VERIFY -> Unit
        }
        if (step != PhotoStep.DONE) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Cancelar") }
        }
    }
}
