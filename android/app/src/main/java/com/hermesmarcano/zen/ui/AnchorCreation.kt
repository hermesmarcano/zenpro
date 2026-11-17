package com.hermesmarcano.zen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hermesmarcano.zen.data.Anchor
import com.hermesmarcano.zen.data.StateRepository
import java.io.IOException

enum class NewAnchorKind { QR, PHOTO }

/** Alta de un ancla nuevo a pantalla completa; compartida por el editor de alarmas y el modo viaje. */
@Composable
fun NewAnchorFlow(kind: NewAnchorKind, onCreated: (Anchor) -> Unit, onCancel: () -> Unit) {
    when (kind) {
        NewAnchorKind.QR -> QrAnchorSetup(onDone = onCreated, onCancel = onCancel)
        NewAnchorKind.PHOTO -> PhotoAnchorSetup(onDone = onCreated, onCancel = onCancel)
    }
}

/** Los dos botones "Nuevo QR" / "Nueva foto". */
@Composable
fun NewAnchorButtons(onPick: (NewAnchorKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onPick(NewAnchorKind.QR) }, modifier = Modifier.weight(1f)) { Text("Nuevo QR") }
        OutlinedButton(onClick = { onPick(NewAnchorKind.PHOTO) }, modifier = Modifier.weight(1f)) { Text("Nueva foto") }
    }
}

/** Guarda el ancla (si ya existe no la duplica). `false` si no se pudo escribir. */
fun StateRepository.addAnchor(anchor: Anchor): Boolean = try {
    update { if (it.anchor(anchor.id) != null) it else it.copy(anchors = it.anchors + anchor) }
    true
} catch (_: IOException) {
    false
}
