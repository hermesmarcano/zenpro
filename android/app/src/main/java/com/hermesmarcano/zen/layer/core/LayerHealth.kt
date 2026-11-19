package com.hermesmarcano.zen.layer.core

enum class Severity { BLOCKER, WARNING, INFO }

data class HealthItem(val id: String, val ok: Boolean, val severity: Severity, val title: String, val hint: String)

/** Instantánea del estado del teléfono. Todo son hechos ya medidos: este objeto no toca Android. */
data class HealthInput(
    val settings: CaptureSettings,
    /** Hay límites o bloqueos configurados (también necesitan ver qué app está delante). */
    val hasLimits: Boolean,
    val accessibilityListed: Boolean,
    val accessibilityConnected: Boolean,
    val listenerEnabled: Boolean,
    val listenerConnected: Boolean,
    val micGranted: Boolean,
    val notificationsGranted: Boolean,
    val batteryUnrestricted: Boolean,
    val isXiaomi: Boolean,
    val autostartConfirmed: Boolean,
    val secureSettingsGranted: Boolean,
    val brainConfigured: Boolean,
    val lastSyncOkAtMs: Long?,
    val nowMs: Long,
)

/** Convierte esa instantánea en la lista de cosas por arreglar, solo de lo que el usuario encendió. */
object LayerHealth {
    const val STALE_SYNC_MS = 6 * 60 * 60 * 1000L

    fun evaluate(i: HealthInput): List<HealthItem> {
        val items = ArrayList<HealthItem>()
        val needsAccessibility = i.settings.screenText || i.hasLimits
        val anything = needsAccessibility || i.settings.notifications || i.settings.ambientAudio

        if (needsAccessibility) {
            when {
                !i.accessibilityListed -> items += HealthItem(
                    "accessibility", false, Severity.BLOCKER,
                    "Servicio de accesibilidad apagado",
                    "Actívalo en Ajustes → Accesibilidad → ZEN. Sin él no se lee la pantalla ni se aplican límites.",
                )
                !i.accessibilityConnected -> items += HealthItem(
                    "accessibility", false, Severity.BLOCKER,
                    "El servicio de accesibilidad no responde",
                    "El sistema lo detuvo (Xiaomi lo hace). Desactívalo y vuelve a activarlo; con el permiso de ajustes seguros se repara solo.",
                )
                else -> items += HealthItem("accessibility", true, Severity.BLOCKER, "Accesibilidad activa", "")
            }
        }
        if (i.settings.notifications) {
            when {
                !i.listenerEnabled -> items += HealthItem(
                    "listener", false, Severity.BLOCKER,
                    "Sin acceso a las notificaciones",
                    "Actívalo en Ajustes → Notificaciones → Acceso a notificaciones → ZEN.",
                )
                !i.listenerConnected -> items += HealthItem(
                    "listener", false, Severity.WARNING,
                    "El lector de notificaciones está desconectado",
                    "Se reconecta solo; si no, desactívalo y actívalo de nuevo.",
                )
                else -> items += HealthItem("listener", true, Severity.BLOCKER, "Notificaciones activas", "")
            }
        }
        if (i.settings.ambientAudio) {
            items += if (i.micGranted) {
                HealthItem("mic", true, Severity.BLOCKER, "Micrófono permitido", "")
            } else {
                HealthItem("mic", false, Severity.BLOCKER, "Falta el permiso del micrófono", "Concédelo para poder escuchar.")
            }
            if (!i.notificationsGranted) {
                items += HealthItem(
                    "post_notifications", false, Severity.WARNING,
                    "Avisos de ZEN bloqueados",
                    "Sin ellos no verías el aviso fijo que indica que el micrófono está escuchando.",
                )
            }
        }
        if (anything) {
            items += HealthItem(
                "battery", i.batteryUnrestricted, Severity.WARNING,
                if (i.batteryUnrestricted) "Batería sin restricciones" else "La batería puede detener la capa",
                if (i.batteryUnrestricted) "" else "Pon ZEN en «Sin restricciones» (Ajustes → Batería → ZEN).",
            )
            if (i.isXiaomi) {
                items += HealthItem(
                    "autostart", i.autostartConfirmed, Severity.WARNING,
                    if (i.autostartConfirmed) "Inicio automático confirmado" else "Falta el inicio automático (Xiaomi)",
                    if (i.autostartConfirmed) "" else "Actívalo para ZEN y bloquea la app en Recientes. Luego márcalo como hecho.",
                )
            }
            items += HealthItem(
                "secure_settings", i.secureSettingsGranted, Severity.INFO,
                if (i.secureSettingsGranted) "Reparación automática disponible" else "Sin reparación automática",
                if (i.secureSettingsGranted) "" else "Opcional: concede el permiso de ajustes seguros por ADB (ver la guía) para que la capa se reactive sola.",
            )
        }
        if (!i.brainConfigured) {
            items += HealthItem(
                "brain", false, Severity.INFO, "Sin cerebro configurado",
                "Todo queda en el teléfono. Añade la dirección de tu servidor para sincronizar.",
            )
        } else {
            val last = i.lastSyncOkAtMs
            val stale = last == null || i.nowMs - last > STALE_SYNC_MS
            items += HealthItem(
                "brain", !stale, Severity.WARNING,
                if (stale) "Sin sincronizar con el cerebro" else "Cerebro sincronizado",
                if (stale) "No hay una sincronización correcta reciente. Revisa la dirección, el token y la red." else "",
            )
        }
        return items
    }

    /** Hay algo que impide que la capa haga lo que se le pidió. */
    fun hasBlocker(items: List<HealthItem>): Boolean = items.any { !it.ok && it.severity == Severity.BLOCKER }
}
