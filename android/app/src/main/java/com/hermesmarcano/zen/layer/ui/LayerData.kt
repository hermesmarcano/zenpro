package com.hermesmarcano.zen.layer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesmarcano.zen.layer.capture.AmbientAudioService
import com.hermesmarcano.zen.layer.core.BrainUrl
import com.hermesmarcano.zen.layer.core.DraftBoard
import com.hermesmarcano.zen.layer.core.DraftController
import com.hermesmarcano.zen.layer.core.LayerEvent
import com.hermesmarcano.zen.layer.core.LayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------------------------------
// Respuestas por aprobar
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun DraftsSection(env: LayerEnv, state: LayerState) {
    val pending = DraftBoard.pending(state.drafts, env.now())
    if (pending.isEmpty()) return
    val scope = rememberCoroutineScope()
    SectionTitle("Respuestas por aprobar")
    Hint("El cerebro propone; tú decides. Nada se envía sin tu toque en «Enviar». Puedes cambiar el texto antes.")
    for (draft in pending) {
        var text by remember(draft.id) { mutableStateOf(draft.text) }
        Panel {
            Text("Para ${draft.contact} · ${labelOf(env.context, draft.pkg)}", fontWeight = FontWeight.Medium)
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { env.runtime.drafts.approve(draft.id, text) }
                        env.runtime.notifier.cancelDraft(draft.id)
                        env.say(
                            when (result) {
                                DraftController.Result.SENT -> "Enviado a ${draft.contact}."
                                DraftController.Result.EXPIRED -> "La respuesta caducó antes de enviarse."
                                DraftController.Result.GONE -> "No se envió: esa conversación ya no está disponible."
                                DraftController.Result.FAILED -> "No se pudo enviar la respuesta."
                                DraftController.Result.NOT_FOUND -> "Esa respuesta ya no está pendiente."
                            },
                        )
                    }
                }) { Text("Enviar") }
                TextButton(onClick = {
                    env.runtime.drafts.discard(draft.id)
                    env.runtime.notifier.cancelDraft(draft.id)
                }) { Text("Descartar") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Memoria
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun MemorySection(env: LayerEnv, state: LayerState) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LayerEvent>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmWipe by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Se vuelve a leer al cambiar la búsqueda, al pedirlo o cuando cambia lo configurado (p. ej. se vetó una app).
    LaunchedEffect(query, reload, state.capture.denyPackages) {
        if (query.isNotBlank()) delay(250) // no buscar en cada tecla
        val found = withContext(Dispatchers.IO) {
            try {
                env.runtime.timeline.search(query, RESULTS) to env.runtime.timeline.count()
            } catch (_: Exception) {
                emptyList<LayerEvent>() to 0
            }
        }
        results = found.first
        total = found.second
    }

    SectionTitle("Memoria")
    Hint("Lo que ZEN recuerda, solo en tu teléfono (y en tu cerebro si lo configuras). Se guarda 30 días como máximo. Hay $total elementos.")
    OutlinedTextField(
        value = query, onValueChange = { query = it }, label = { Text("Buscar en lo recordado") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (results.isEmpty()) Hint(if (query.isBlank()) "Todavía no hay nada guardado." else "Nada coincide.")
    for (event in results) {
        Panel {
            val source = event.pkg.takeIf { it.isNotBlank() }?.let { labelOf(env.context, it) } ?: event.kind.name.lowercase()
            Hint("${formatDateTime(event.atMs)} · $source")
            if (event.title.isNotBlank()) Text(event.title, fontWeight = FontWeight.Medium)
            Text(event.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { reload++ }) { Text("Actualizar") }
        OutlinedButton(onClick = { confirmWipe = true }) { Text("Borrar todo…") }
    }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("¿Borrar toda la memoria?") },
            text = { Text("Se borra lo recordado, lo que estaba en cola para el cerebro y el audio guardado en este teléfono. No se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                env.runtime.timeline.deleteAll()
                                env.runtime.outbox.clear()
                                env.runtime.audioFiles.deleteAll()
                            } catch (_: Exception) {
                            }
                        }
                        env.say("Memoria borrada.")
                        reload++
                    }
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancelar") } },
        )
    }
}

private const val RESULTS = 30

// ---------------------------------------------------------------------------------------------------------------------
// Cerebro
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun BrainSection(env: LayerEnv, state: LayerState) {
    var url by remember(state.brainUrl) { mutableStateOf(state.brainUrl) }
    var token by remember(state.brainToken) { mutableStateOf(state.brainToken) }
    var syncing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SectionTitle("Cerebro")
    Hint(
        "Tu propio servidor (por ejemplo Hermes). Recibe lo que la capa guarda y puede proponerte respuestas, avisos y bloqueos; " +
            "no puede quitarte un bloqueo ni enviar nada sin tu aprobación, y solo puede bloquear apps que tengan una regla en Límites. " +
            "https:// funciona desde cualquier sitio; http:// solo por Tailscale (100.x.x.x, *.ts.net) o en este mismo teléfono (localhost).",
    )
    OutlinedTextField(
        value = url, onValueChange = { url = it }, label = { Text("Dirección del cerebro") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token, onValueChange = { token = it }, label = { Text("Token") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        // Teclado de contraseña: sin sugerencias ni autocorrección que aprendan o envíen el token a un teclado en la nube.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.brainUrl.isNotBlank() && state.brainToken.isBlank()) {
        Hint("Hay una dirección guardada pero no se puede usar el token (falta o no se pudo leer): vuelve a escribirlo y pulsa Guardar.")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            when (val result = BrainUrl.validate(url)) {
                is BrainUrl.Result.Invalid -> env.say(urlError(result.error))
                is BrainUrl.Result.Ok -> if (token.isBlank()) {
                    env.say("Falta el token.")
                } else if (env.save { it.copy(brainUrl = result.baseUrl, brainToken = token.trim(), lastSyncError = "") }) {
                    env.runtime.ensureDeviceId()
                    env.say("Cerebro guardado. Usa «Sincronizar ahora» para probar la conexión.")
                }
            }
        }) { Text("Guardar") }
        OutlinedButton(
            enabled = state.brainConfigured && !syncing,
            onClick = {
                syncing = true
                scope.launch {
                    val report = withContext(Dispatchers.IO) { env.runtime.sync.run() }
                    syncing = false
                    env.say(
                        when {
                            report.paused -> "La capa está en pausa: no se envía ni se recibe nada hasta que termine."
                            report.skipped -> "No hay un cerebro válido configurado."
                            report.error != null -> "Sincronizado con problemas: ${report.error}"
                            else -> "Enviado: ${report.eventsSent} eventos, ${report.audioSent} audios. Órdenes aplicadas: ${report.commandsApplied}."
                        },
                    )
                }
            },
        ) { Text(if (syncing) "Sincronizando…" else "Sincronizar ahora") }
        OutlinedButton(
            enabled = state.brainUrl.isNotBlank() || state.brainToken.isNotBlank(),
            onClick = {
                // El micrófono necesita cerebro (sin él nadie transcribe): al quitarlo, se apaga.
                AmbientAudioService.stop(env.context)
                env.save {
                    it.copy(
                        brainUrl = "", brainToken = "", commandCursor = "", lastSyncError = "", lastSyncOkAtMs = null,
                        capture = it.capture.copy(ambientAudio = false),
                    )
                }
                // Lo encolado era para ESE cerebro: no debe acabar en otro servidor que se configure después.
                env.runtime.runIo {
                    env.runtime.outbox.clear()
                    env.runtime.audioFiles.deleteAll()
                }
                env.say("Cerebro quitado y su cola de envío borrada.")
            },
        ) { Text("Quitar cerebro") }
    }
    Hint("ID de este teléfono: ${state.deviceId.ifBlank { "se crea al guardar el cerebro" }}")
    Hint(state.lastSyncOkAtMs?.let { "Última sincronización correcta: ${formatDateTime(it)}." } ?: "Aún no se ha sincronizado.")
}

private fun urlError(error: BrainUrl.Error): String = when (error) {
    BrainUrl.Error.EMPTY -> "Escribe la dirección del cerebro."
    BrainUrl.Error.MALFORMED -> "La dirección no es válida: debe ser como https://mi-equipo.mi-red.ts.net (empieza por https:// o http://)."
    BrainUrl.Error.ILLEGAL_CHARACTERS ->
        "La dirección tiene caracteres que no valen (como < > o espacios). ¿Dejaste el texto de ejemplo de la guía, como <tu-tailnet>? Debe ser el nombre real, por ejemplo https://mi-equipo.mi-red.ts.net"
    BrainUrl.Error.NO_HOST -> "Falta el nombre del servidor en la dirección."
    BrainUrl.Error.CREDENTIALS_IN_URL -> "No pongas usuario ni contraseña en la dirección: usa el campo Token."
    BrainUrl.Error.CLEARTEXT_NOT_ALLOWED -> "http:// solo se acepta por Tailscale (100.x.x.x, *.ts.net) o en este teléfono (localhost): en una red local cualquiera podría leerlo. Usa https://."
}
