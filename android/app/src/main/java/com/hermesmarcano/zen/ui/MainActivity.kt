package com.hermesmarcano.zen.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hermesmarcano.zen.alarm.AlarmActions
import com.hermesmarcano.zen.alarm.AlarmRuntime
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Ruta pedida desde fuera (p. ej. "ver mis tareas" en la pantalla de éxito). Se consume una vez. */
    private val requestedRoute = mutableStateOf<String?>(null)

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val healOverdue = Runnable { AlarmActions.healOverdue(this) }

    override fun onPause() {
        handler.removeCallbacks(healOverdue)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Una alarma que ya pasó su hora sin procesarse se anota como perdida y se reprograma. Se espera unos
        // segundos: si el sistema entrega justo ahora un aviso retrasado, debe poder sonar antes de reprogramar.
        handler.postDelayed(healOverdue, HEAL_DELAY_MS)
        // Volver a ZEN (icono, notificación) con una alarma sonando lleva a la pantalla de apagado,
        // no a la lista de alarmas.
        if (AlarmRuntime.ring.value != null) {
            startActivity(Intent(this, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedRoute.value = intent.getStringExtra(EXTRA_ROUTE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            ZenTheme {
                ZenRoot(requestedRoute = requestedRoute.value, onRouteConsumed = { requestedRoute.value = null })
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ROUTE_TODAY = "today"
        const val ROUTE_WORKOUT = "workout"
        const val ROUTE_RELIABILITY = "reliability"
        private const val HEAL_DELAY_MS = 15_000L
    }
}

private const val TODAY = "today"
private const val ALARMS = "alarms"
private const val PROGRESS = "progress"
private const val WORKOUT = MainActivity.ROUTE_WORKOUT
private const val SETTINGS = "settings"
private const val TRIP = "trip"
private const val RELIABILITY = MainActivity.ROUTE_RELIABILITY
private const val NEW_ALARM = "edit:new"
private val TABS = listOf(TODAY, WORKOUT, ALARMS, PROGRESS)

@Composable
private fun ZenRoot(requestedRoute: String?, onRouteConsumed: () -> Unit) {
    val context = LocalContext.current
    val ring by AlarmRuntime.ring.collectAsState()
    // Red de seguridad: sin permiso de notificaciones o de pantalla completa, la pantalla de
    // apagado no se abre sola; al abrir ZEN con una alarma sonando se muestra directamente.
    LaunchedEffect(ring?.alarmId) {
        if (ring != null) {
            context.startActivity(Intent(context, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    var route by rememberSaveable { mutableStateOf(TODAY) }
    // Pestaña desde la que se abrió una pantalla secundaria (Ajustes): "Listo" vuelve ahí, no siempre a Hoy.
    var lastTab by rememberSaveable { mutableStateOf(TODAY) }
    LaunchedEffect(route) { if (route in TABS) lastTab = route }
    LaunchedEffect(requestedRoute) {
        if (requestedRoute != null) {
            route = if (requestedRoute in TABS || requestedRoute == RELIABILITY) requestedRoute else TODAY
            onRouteConsumed()
        }
    }
    BackHandler(enabled = route != TODAY) { route = if (route in TABS) TODAY else previousTab(route, lastTab) }

    // Avisos breves (XP, logros, "tarea eliminada · Deshacer"): uno a la vez, el nuevo sustituye al anterior.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val announce: (String, String?, (() -> Unit)?) -> Unit = { text, action, onAction ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }

    val onTab = route in TABS
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (onTab) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(selected = route == TODAY, onClick = { route = TODAY }, icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Hoy") })
                    NavigationBarItem(selected = route == WORKOUT, onClick = { route = WORKOUT }, icon = { Icon(DumbbellIcon, null) }, label = { Text("Entreno") })
                    NavigationBarItem(selected = route == ALARMS, onClick = { route = ALARMS }, icon = { Icon(Icons.Filled.Notifications, null) }, label = { Text("Alarmas") })
                    NavigationBarItem(selected = route == PROGRESS, onClick = { route = PROGRESS }, icon = { Icon(Icons.Filled.Star, null) }, label = { Text("Progreso") })
                }
            }
        },
    ) { inner ->
        // El hueco de la barra inferior y de las barras del sistema ya viene en `inner`; el teclado se suma aparte.
        val content = Modifier.padding(inner).consumeWindowInsets(inner).imePadding()
        when {
            route == TODAY -> TodayScreen(content, onOpenSettings = { route = SETTINGS }, announce = announce, onOpenAlarms = { route = ALARMS }, onOpenWorkout = { route = WORKOUT })
            route == WORKOUT -> WorkoutScreen(content, announce = announce)
            route == ALARMS -> HomeScreen(
                onNewAlarm = { route = NEW_ALARM },
                onEditAlarm = { route = "edit:$it" },
                onOpenSettings = { route = SETTINGS },
                onOpenTrip = { route = TRIP },
                onOpenReliability = { route = RELIABILITY },
                modifier = content,
            )
            route == PROGRESS -> ProgressScreen(content, onOpenSettings = { route = SETTINGS })
            route == SETTINGS -> SettingsScreen(onClose = { route = lastTab }, onOpenReliability = { route = RELIABILITY })
            route == RELIABILITY -> ReliabilityScreen(content, onBack = { route = ALARMS })
            route == TRIP -> TripScreen(onClose = { route = ALARMS }, announce = announce)
            route.startsWith("edit:") -> AlarmEditScreen(
                alarmId = route.removePrefix("edit:").takeIf { it != "new" },
                onClose = { route = ALARMS },
            )
            else -> LaunchedEffect(route) { route = TODAY } // ruta desconocida (estado viejo): volver al inicio
        }
    }
}

private fun previousTab(route: String, lastTab: String): String = when {
    route == TRIP || route == RELIABILITY || route.startsWith("edit:") -> ALARMS
    route == SETTINGS -> lastTab
    else -> TODAY
}
