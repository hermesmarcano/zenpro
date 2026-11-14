package com.hermesmarcano.zen.ui

import android.graphics.Bitmap
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hermesmarcano.zen.camera.CameraView
import com.hermesmarcano.zen.camera.capture
import com.hermesmarcano.zen.camera.newImageCapture
import com.hermesmarcano.zen.photo.PhotoStore

/** Cámara a pantalla completa con obturador, linterna y cancelar. Entrega la foto ya vertical y reducida. */
@Composable
fun PhotoCapture(
    prompt: String,
    onCaptured: (Bitmap) -> Unit,
    onCancel: () -> Unit,
    onUnavailable: () -> Unit,
    cancelLabel: String = "Cancelar",
) {
    val imageCapture = remember { newImageCapture() }
    var torchOn by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        CameraView(
            modifier = Modifier.fillMaxSize(),
            imageCapture = imageCapture,
            torchOn = torchOn,
            onUnavailable = onUnavailable,
        )
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                prompt,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background08()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (failed) Text("No se pudo tomar la foto. Inténtalo de nuevo.", color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    failed = false
                    imageCapture.capture(
                        PhotoStore.MAX_SIDE,
                        onBitmap = { busy = false; onCaptured(it) },
                        onFailure = { busy = false; failed = true },
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text(if (busy) "Procesando…" else "Tomar foto") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { torchOn = !torchOn }, modifier = Modifier.weight(1f)) {
                    Text(if (torchOn) "Apagar linterna" else "Linterna")
                }
                OutlinedButton(onClick = { torchOn = false; onCancel() }, modifier = Modifier.weight(1f)) { Text(cancelLabel) }
            }
        }
    }
}

private fun Modifier.background08(): Modifier =
    this.then(Modifier.background(Color(0xCC000000), RoundedCornerShape(10.dp)))
