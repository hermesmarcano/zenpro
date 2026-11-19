package com.hermesmarcano.zen.layer.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesmarcano.zen.layer.core.BlockBook
import com.hermesmarcano.zen.layer.core.LayerState
import com.hermesmarcano.zen.layer.core.LimitRule
import com.hermesmarcano.zen.layer.core.RelaxPolicy
import com.hermesmarcano.zen.layer.core.RuleChange
import com.hermesmarcano.zen.layer.core.RuleForm
import com.hermesmarcano.zen.layer.core.SensitiveApps
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * Los editores de esta pantalla son paneles DENTRO de ella, no ventanas emergentes. Es una decisión de diseño y de
 * pruebas: un campo de texto dentro de un diálogo hace que Compose no llegue nunca a reposo en Robolectric, y estas
 * son las piezas que más importa poder probar (límites, bloqueos, soltar un bloqueo).
 */

/** Una app instalada con icono, para elegirla en las listas. */
data class AppEntry(val pkg: String, val label: String)

/** Las apps con icono de inicio (Android 11 oculta el resto; el manifiesto declara `<queries>` para ver estas). */
fun loadLauncherApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.pkg != context.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

/** El nombre de una app, o su paquete si no se ve (o ya no está instalada). */
internal fun labelOf(context: Context, pkg: String): String = try {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (_: PackageManager.NameNotFoundException) {
    pkg
}

internal fun formatTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

internal fun formatDateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

@Composable
internal fun AppPickerPanel(
    appLoader: (Context) -> List<AppEntry>,
    context: Context,
    exclude: (String) -> Boolean,
    onPick: (AppEntry) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Cargar las etiquetas de todas las apps es lento: se hace fuera del hilo principal.
    val apps by produceState(initialValue = emptyList<AppEntry>(), context) { value = withContext(Dispatchers.Default) { appLoader(context) } }
    val shown = apps.filter { !exclude(it.pkg) && (query.isBlank() || it.label.contains(query, ignoreCase = true)) }
    Panel {
        Text("Elige una app", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (shown.isEmpty()) Hint(if (apps.isEmpty()) "Cargando…" else "Ninguna app coincide.")
        Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            for (app in shown) {
                Text(app.label, modifier = Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 12.dp))
            }
        }
        TextButton(onClick = onClose) { Text("Cerrar") }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Apps que no se miran nunca
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun VetoSection(env: LayerEnv, state: LayerState, appLoader: (Context) -> List<AppEntry>) {
    var picking by remember { mutableStateOf(false) }
    SectionTitle("Apps que no se miran nunca")
    Hint("ZEN ya ignora por su cuenta bancos, billeteras, gestores de contraseñas y Ajustes. Aquí añades las tuyas.")
    for (pkg in state.capture.denyPackages.sorted()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(labelOf(env.context, pkg), modifier = Modifier.weight(1f))
            TextButton(onClick = {
                env.runtime.runIo { env.runtime.timeline.deletePackage(pkg) }
                env.say("Se olvidó todo lo guardado de ${labelOf(env.context, pkg)}.")
            }) { Text("Olvidar lo guardado") }
            TextButton(onClick = { env.saveCapture { it.copy(denyPackages = it.denyPackages - pkg) } }) { Text("Quitar") }
        }
    }
    if (!picking) OutlinedButton(onClick = { picking = true }) { Text("Añadir app") }
    if (picking) {
        AppPickerPanel(
            appLoader = appLoader,
            context = env.context,
            exclude = { it in state.capture.denyPackages || SensitiveApps.isSensitive(it) },
            onPick = { app ->
                picking = false
                // Lo ya guardado de esa app también se borra: vetarla significa que no se vea ni se recuerde.
                if (env.saveCapture { it.copy(denyPackages = it.denyPackages + app.pkg) }) {
                    env.runtime.runIo { env.runtime.timeline.deletePackage(app.pkg) }
                    env.say("${app.label} ya no se mira y se olvidó lo que había guardado.")
                }
            },
            onClose = { picking = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Límites y bloqueos
// ---------------------------------------------------------------------------------------------------------------------

@Composable
internal fun LimitsSection(env: LayerEnv, state: LayerState, appLoader: (Context) -> List<AppEntry>) {
    var editing by remember { mutableStateOf<LimitRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var relaxing by remember { mutableStateOf<String?>(null) }
    val now = env.now()
    fun labelFor(pkg: String) = state.limits.firstOrNull { it.pkg == pkg }?.label ?: labelOf(env.context, pkg)

    SectionTitle("Límites y bloqueos")
    Hint(
        "Tus compromisos. Endurecer un límite vale al instante; aflojarlo o quitarlo tarda 24 horas en aplicarse, para que no se pueda saltar en un mal momento. " +
            "Ajustes, el teléfono y esta app nunca se bloquean.",
    )
    for (rule in state.limits) {
        Panel {
            Text(rule.label, fontWeight = FontWeight.SemiBold)
            Hint(summary(rule))
            Row {
                TextButton(onClick = { adding = false; editing = rule }) { Text("Editar") }
                TextButton(onClick = { requestChange(env, rule.pkg, null) }) { Text("Quitar") }
            }
        }
    }
    for (pending in state.pendingRules) {
        Panel {
            Text("Cambio pendiente en ${labelFor(pending.pkg)}", fontWeight = FontWeight.Medium)
            Hint((if (pending.rule == null) "Se quitará el límite " else "Se aflojará el límite ") + "el ${formatDateTime(pending.effectiveAtMs)}.")
            TextButton(onClick = { env.save { RuleChange.cancelPending(it, pending.pkg) } }) { Text("Cancelar el cambio") }
        }
    }
    if (!adding && editing == null) OutlinedButton(onClick = { adding = true }) { Text("Añadir límite") }
    if (adding) RuleEditor(env, null, appLoader) { adding = false }
    editing?.let { rule -> RuleEditor(env, rule, appLoader) { editing = null } }

    val active = BlockBook.prune(state.blocks, now)
    if (active.isNotEmpty()) {
        SectionTitle("Bloqueadas ahora")
        for (block in active) {
            Panel {
                Text("${labelFor(block.pkg)} · hasta las ${formatTime(block.untilMs)}", fontWeight = FontWeight.Medium)
                val pass = state.passes[block.pkg]
                if (pass != null && pass > now) Hint("Pase activo hasta las ${formatTime(pass)}.")
                if (relaxing != block.pkg) TextButton(onClick = { relaxing = block.pkg }) { Text("Soltar…") }
            }
        }
    }
    relaxing?.let { pkg -> RelaxPanel(env, pkg, labelFor(pkg)) { relaxing = null } }
}

private fun summary(rule: LimitRule): String {
    val parts = ArrayList<String>()
    if (rule.dailyLimitMs > 0) parts += "${rule.dailyLimitMs / 60_000} min al día" + if (rule.blockAtLimit) " (bloquea al llegar)" else " (solo avisa)"
    rule.window?.takeIf { it.isValid }?.let { parts += "prohibida de ${hour(it.startMinute)} a ${hour(it.endMinute)}" }
    return parts.joinToString(" · ").ifEmpty { "Sin reglas" }
}

private fun hour(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** Aplica un cambio de regla respetando "endurecer ya, aflojar tarda 24 h" y se lo explica al usuario. */
private fun requestChange(env: LayerEnv, pkg: String, proposed: LimitRule?) {
    var applied: RuleChange.Applied = RuleChange.Applied.Now
    val now = env.now()
    val ok = env.save { s ->
        val (next, how) = RuleChange.request(s, pkg, proposed, now)
        applied = how
        next
    }
    if (!ok) return
    when (val how = applied) {
        RuleChange.Applied.Now -> env.say("Listo.")
        is RuleChange.Applied.Later -> env.say("Aflojar o quitar un límite tarda 24 horas: se aplicará el ${formatDateTime(how.effectiveAtMs)}.")
    }
}

@Composable
private fun RuleEditor(env: LayerEnv, existing: LimitRule?, appLoader: (Context) -> List<AppEntry>, onClose: () -> Unit) {
    var pkg by remember { mutableStateOf(existing?.pkg.orEmpty()) }
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var minutes by remember { mutableStateOf(existing?.dailyLimitMs?.takeIf { it > 0 }?.let { (it / 60_000).toString() }.orEmpty()) }
    var blockAtLimit by remember { mutableStateOf(existing?.blockAtLimit ?: false) }
    var windowOn by remember { mutableStateOf(existing?.window?.isValid == true) }
    var from by remember { mutableStateOf(existing?.window?.takeIf { it.isValid }?.let { (it.startMinute / 60).toString() } ?: "22") }
    var to by remember { mutableStateOf(existing?.window?.takeIf { it.isValid }?.let { (it.endMinute / 60).toString() } ?: "7") }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Panel {
        Text(if (existing == null) "Nuevo límite" else "Editar límite", fontWeight = FontWeight.SemiBold)
        if (existing == null) {
            OutlinedButton(onClick = { picking = !picking }) { Text(if (pkg.isEmpty()) "Elegir app" else "App: $label") }
        } else {
            Text(label)
        }
        if (picking) {
            AppPickerPanel(
                appLoader = appLoader, context = env.context,
                exclude = { it == env.context.packageName || it == com.hermesmarcano.zen.layer.capture.LayerStatus.homePackage(env.context) },
                onPick = { pkg = it.pkg; label = it.label; picking = false },
                onClose = { picking = false },
            )
        }
        OutlinedTextField(
            value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
            label = { Text("Minutos al día (vacío = sin límite)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bloquear al llegar al límite", modifier = Modifier.weight(1f))
            Switch(checked = blockAtLimit, onCheckedChange = { blockAtLimit = it })
        }
        Hint("Sin esto, al llegar al límite solo te avisa.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Franja prohibida cada día", modifier = Modifier.weight(1f))
            Switch(checked = windowOn, onCheckedChange = { windowOn = it })
        }
        if (windowOn) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = from, onValueChange = { from = it.filter(Char::isDigit).take(2) }, label = { Text("Desde (hora)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = to, onValueChange = { to = it.filter(Char::isDigit).take(2) }, label = { Text("Hasta (hora)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                when (val result = RuleForm.build(pkg, label, minutes, blockAtLimit, windowOn, from, to, env.context.packageName)) {
                    is RuleForm.Result.Error -> error = result.message
                    is RuleForm.Result.Ok -> {
                        error = null
                        requestChange(env, result.rule.pkg, result.rule)
                        onClose()
                    }
                }
            }) { Text("Guardar límite") }
            TextButton(onClick = onClose) { Text("Cancelar") }
        }
    }
}

/**
 * Soltar un bloqueo cuesta algo a propósito: se pide, se espera un minuto, se escribe el motivo y solo se suelta un
 * rato (un pase), con un tope diario. El motivo queda en la memoria junto a lo que pasó.
 */
@Composable
private fun RelaxPanel(env: LayerEnv, pkg: String, label: String, onClose: () -> Unit) {
    var requestedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(env.now()) }
    var reason by remember { mutableStateOf("") }
    LaunchedEffect(requestedAt) {
        while (requestedAt > 0L) {
            delay(1_000)
            now = env.now()
        }
    }
    val remainingMs = if (requestedAt == 0L) RelaxPolicy.COOLDOWN_MS else (RelaxPolicy.COOLDOWN_MS - (now - requestedAt)).coerceIn(0, RelaxPolicy.COOLDOWN_MS)
    val waiting = requestedAt > 0L && remainingMs > 0
    val close = {
        if (requestedAt > 0L) env.runtime.blockActions.cancelRelax(pkg)
        onClose()
    }
    Panel {
        Text("Soltar $label", fontWeight = FontWeight.SemiBold)
        Hint(
            "Hay que esperar un minuto y escribir por qué. Solo se suelta ${RelaxPolicy.EXTENSION_MS / 60_000} minutos, " +
                "y como máximo ${RelaxPolicy.MAX_PER_DAY} veces al día.",
        )
        when {
            requestedAt == 0L -> Unit
            waiting -> Text("Espera ${(remainingMs + 999) / 1000} s…", fontWeight = FontWeight.Medium)
            else -> OutlinedTextField(
                value = reason, onValueChange = { reason = it }, label = { Text("¿Por qué lo sueltas?") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = !waiting,
                onClick = {
                    val t = env.now()
                    if (requestedAt == 0L) {
                        env.runtime.blockActions.requestRelax(pkg, t)
                        requestedAt = t
                        now = t
                    } else {
                        when (val result = env.runtime.blockActions.confirmRelax(pkg, reason, t)) {
                            is RelaxPolicy.Result.Granted -> {
                                env.say("Pase de ${RelaxPolicy.EXTENSION_MS / 60_000} min para $label, hasta las ${formatTime(result.untilMs)}.")
                                onClose()
                            }
                            is RelaxPolicy.Result.TooEarly -> env.say("Todavía faltan ${(result.remainingMs + 999) / 1000} s.")
                            RelaxPolicy.Result.NeedReason -> env.say("Escribe un motivo (al menos ${RelaxPolicy.MIN_REASON_CHARS} letras).")
                            RelaxPolicy.Result.LimitReached -> {
                                env.say("Ya soltaste ${RelaxPolicy.MAX_PER_DAY} veces hoy. Mañana se puede de nuevo.")
                                close()
                            }
                        }
                    }
                },
            ) { Text(if (requestedAt == 0L) "Empezar la espera" else "Soltar") }
            TextButton(onClick = { close() }) { Text("Mantener el bloqueo") }
        }
    }
}
