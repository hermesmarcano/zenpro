package com.hermesmarcano.zen.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.hermesmarcano.zen.alarm.AlarmRuntime
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Volver a ZEN (icono, notificación) con una alarma sonando lleva a la pantalla de apagado,
        // no a la lista de alarmas.
        if (AlarmRuntime.ring.value != null) {
            startActivity(Intent(this, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZenTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ZenRoot()
                }
            }
        }
    }
}

private const val HOME = "home"
private const val NEW_ALARM = "edit:new"
private const val SETTINGS = "settings"

@Composable
private fun ZenRoot() {
    val context = LocalContext.current
    val ring by AlarmRuntime.ring.collectAsState()
    // Red de seguridad: sin permiso de notificaciones o de pantalla completa, la pantalla de
    // apagado no se abre sola; al abrir ZEN con una alarma sonando se muestra directamente.
    LaunchedEffect(ring?.alarmId) {
        if (ring != null) {
            context.startActivity(Intent(context, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    var route by rememberSaveable { mutableStateOf(HOME) }
    BackHandler(enabled = route != HOME) { route = HOME }
    when {
        route == HOME -> HomeScreen(
            onNewAlarm = { route = NEW_ALARM },
            onEditAlarm = { route = "edit:$it" },
            onOpenSettings = { route = SETTINGS },
        )
        route == SETTINGS -> SettingsScreen(onClose = { route = HOME })
        route.startsWith("edit:") -> AlarmEditScreen(
            alarmId = route.removePrefix("edit:").takeIf { it != "new" },
            onClose = { route = HOME },
        )
        else -> route = HOME
    }
}
