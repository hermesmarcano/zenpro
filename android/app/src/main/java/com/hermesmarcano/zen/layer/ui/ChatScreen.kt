package com.hermesmarcano.zen.layer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import com.hermesmarcano.zen.core.GameMessages
import com.hermesmarcano.zen.layer.capture.CalendarIntent
import com.hermesmarcano.zen.layer.capture.layer
import com.hermesmarcano.zen.layer.core.ChatMessage
import com.hermesmarcano.zen.layer.core.ChatProposal
import com.hermesmarcano.zen.layer.core.ChatRole
import com.hermesmarcano.zen.layer.core.ChatRules
import com.hermesmarcano.zen.layer.link.ChatAvailability
import com.hermesmarcano.zen.layer.link.ChatController
import com.hermesmarcano.zen.layer.link.ChatPhase
import com.hermesmarcano.zen.layer.link.ChatSend
import com.hermesmarcano.zen.layer.link.ChatStart
import com.hermesmarcano.zen.zenApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

/**
 * Hablar con el cerebro: escribes o hablas (tocas «Hablar», dices lo que quieras y tocas «Enviar»). La respuesta llega aquí mismo;
 * si propone agendar algo, sale una tarjeta con «Agendar» y el evento lo guardas tú. Todo lo que dice el modelo se ve como texto plano.
 */
@Composable
fun ChatScreen(
    onClose: () -> Unit,
    chat: ChatController = LocalContext.current.layer.chat,
    clock: () -> Long = System::currentTimeMillis,
) {
    val context = LocalContext.current
    val runtime = context.layer
    var ui by remember(chat) { mutableStateOf(chat.ui()) }
    // La pausa y el cerebro pueden cambiar desde otra pantalla o desde una orden: se vuelve a leer cuando cambia el estado de la capa.
    val layerState by runtime.state.state.collectAsState()
    val game by context.zenApp.repository.state.collectAsState()
    // rememberSaveable: lo escrito sobrevive a que Android mate el proceso mientras la app está en segundo plano.
    var input by rememberSaveable { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmNew by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(clock()) }

    var loadTick by remember { mutableIntStateOf(0) }
    var triedLoad by remember { mutableStateOf(false) }

    // Solo se escucha mientras la pantalla está a la vista (de ON_START a ON_STOP): con la app en segundo plano `chat.watched` es falso y
    // una respuesta que llegue entonces se avisa con una notificación. Cada vez que vuelve a verse se reintenta abrir el historial.
    LifecycleStartEffect(chat) {
        // Se vuelve a pedir la foto actual en vez de usar la que llega: dos hilos pueden avisar en orden cambiado.
        val stop = chat.listen { ui = chat.ui() }
        ui = chat.ui()
        loadTick++
        onStopOrDispose { stop() }
    }
    // Salir de la pantalla nunca deja el micrófono abierto.
    DisposableEffect(chat) { onDispose { chat.cancelRecording() } }
    LaunchedEffect(chat, loadTick) {
        withContext(Dispatchers.IO) { chat.ensureLoaded() }
        triedLoad = true
        ui = chat.ui()
    }
    LaunchedEffect(layerState) { ui = chat.ui() }
    // Una pausa que termina por el reloj no cambia ningún estado: se despierta justo cuando acaba para volver a habilitar el chat.
    val pausedUntil = layerState.capture.pausedUntilMs
    LaunchedEffect(pausedUntil) {
        val wait = pausedUntil - clock()
        if (wait > 0) {
            delay(wait + 100)
            ui = chat.ui()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { chat.cancelRecording() }
    val recording = ui.phase == ChatPhase.RECORDING
    LaunchedEffect(recording) {
        while (recording) {
            now = clock()
            delay(250)
        }
    }

    fun startVoice() {
        // Si el micrófono no abre, el controlador deja su propio aviso; aquí solo falta el caso del permiso.
        if (chat.startRecording() == ChatStart.NO_PERMISSION) notice = "Falta el permiso del micrófono."
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else notice = "Sin permiso del micrófono no se puede hablar. Actívalo en Ajustes del teléfono → Apps → ZEN → Permisos."
    }

    val ready = ui.loaded && ui.availability == ChatAvailability.READY
    val idle = ready && ui.phase == ChatPhase.IDLE
    val shownNotice = notice ?: ui.notice

    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chat", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // «Nueva» también es la salida cuando el historial guardado no se pudo abrir (entonces no hay nada que confirmar).
                TextButton(onClick = { if (ui.messages.isEmpty()) chat.newConversation() else confirmNew = true }) { Text("Nueva") }
                TextButton(onClick = onClose) { Text("Listo") }
            }
            when (ui.availability) {
                ChatAvailability.NO_BRAIN -> Hint("Primero configura el cerebro en Capa → Cerebro (dirección y token).")
                ChatAvailability.PAUSED -> Hint("La capa está en pausa: el chat no envía ni graba nada hasta que termine.")
                ChatAvailability.READY -> Unit
            }

            val listState = rememberLazyListState()
            LaunchedEffect(ui.messages.size, ui.phase) {
                if (ui.messages.isNotEmpty()) listState.scrollToItem(ui.messages.lastIndex)
            }
            if (!ui.loaded) {
                // El historial guardado no se pudo abrir (el almacén de claves no respondió): no hay que dejar la pantalla en blanco.
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (triedLoad) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Hint("No se pudo abrir la conversación guardada.")
                            OutlinedButton(onClick = { loadTick++ }) { Text("Reintentar") }
                        }
                    }
                }
            } else if (ui.messages.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Hint(
                        "Escríbele o háblale. Puede responderte, proponerte agendar algo (el evento lo guardas tú), bloquear una app que ya tenga una " +
                            "regla en Límites o pausar la capa. Esta conversación se guarda cifrada en este teléfono.",
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.messages, key = { it.id }) { message ->
                        MessageRow(
                            message = message,
                            canRetry = idle,
                            onRetry = { chat.retry(message.id) },
                            onSchedule = { proposal -> schedule(context, proposal) { notice = it } },
                        )
                    }
                    if (ui.phase == ChatPhase.WAITING) {
                        item(key = "waiting") { Hint("El cerebro está pensando…") }
                    }
                }
            }

            if (ui.celebration.isNotEmpty()) {
                Text(ui.celebration.joinToString(" · "), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Hint(GameMessages.chatProgress(game.progress, LocalDate.now()))

            if (recording) {
                val seconds = ((now - (ui.recordingSinceMs ?: now)).coerceAtLeast(0L) / 1000L).toInt()
                val limit = (ChatRules.MAX_VOICE_MS / 1000L).toInt()
                Text("Grabando… ${clockText(seconds)} / ${clockText(limit)}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { chat.finishRecording() }) { Text("Enviar") }
                    OutlinedButton(onClick = { chat.cancelRecording() }) { Text("Cancelar") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.take(ChatRules.MAX_TEXT_CHARS) },
                        label = { Text("Mensaje") },
                        enabled = ready,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = idle && ChatRules.cleanInput(input) != null,
                        onClick = {
                            // Lo escrito solo se borra si el mensaje se aceptó: si no, no se pierde.
                            if (chat.sendText(input) == ChatSend.SENT) input = ""
                        },
                    ) { Text("Enviar") }
                }
                OutlinedButton(
                    enabled = idle,
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startVoice()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Hablar") }
            }
        }

        shownNotice?.let { text ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite } // los lectores de pantalla lo anuncian al aparecer
                    .clickable {
                        notice = null
                        chat.dismissNotice()
                    },
            ) {
                Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
            }
        }
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("¿Empezar una conversación nueva?") },
            text = { Text("Se borra esta conversación de este teléfono y el cerebro empieza sin recordarla.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmNew = false
                    chat.newConversation()
                }) { Text("Empezar") }
            },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun MessageRow(message: ChatMessage, canRetry: Boolean, onRetry: () -> Unit, onSchedule: (ChatProposal) -> Unit) {
    val proposal = message.proposal
    when {
        proposal != null -> ProposalCard(proposal, onSchedule)
        message.role == ChatRole.NOTE ->
            Text(message.text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontStyle = FontStyle.Italic, modifier = Modifier.fillMaxWidth())
        else -> {
            val mine = message.role == ChatRole.USER
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                // Quién habla, también para TalkBack (que si no solo lee el texto de la burbuja).
                Text(if (mine) "Tú" else "Cerebro", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(
                    color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    val shown = message.text.ifBlank { if (message.voice) "Mensaje de voz" else "" }
                    Text(
                        shown,
                        fontStyle = if (message.text.isBlank()) FontStyle.Italic else FontStyle.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                val failure = message.failure
                if (failure != null) {
                    Text(failure.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (failure.retryable && !message.voice) TextButton(onClick = onRetry, enabled = canRetry) { Text("Reintentar") }
                }
            }
        }
    }
}

@Composable
private fun ProposalCard(proposal: ChatProposal, onSchedule: (ChatProposal) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Propuesta para tu calendario", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(proposal.title, fontWeight = FontWeight.SemiBold)
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(proposal.startMs)), fontSize = 14.sp)
            OutlinedButton(onClick = { onSchedule(proposal) }) { Text("Agendar") }
        }
    }
}

/** Abre el calendario con el evento relleno; lo guarda la persona. Sin calendario instalado, lo dice. */
private fun schedule(context: Context, proposal: ChatProposal, say: (String) -> Unit) {
    try {
        context.startActivity(CalendarIntent.insert(proposal.title, proposal.startMs, proposal.endMs))
    } catch (_: ActivityNotFoundException) {
        say("No hay una app de calendario para abrir el evento.")
    }
}

private fun clockText(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
