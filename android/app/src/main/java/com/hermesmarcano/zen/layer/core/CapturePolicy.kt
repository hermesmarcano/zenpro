package com.hermesmarcano.zen.layer.core

/** Las tres fuentes que la capa puede observar. Cada una se activa por separado y empieza apagada. */
enum class Source { SCREEN, NOTIFICATIONS, AUDIO }

/**
 * Qué observa la capa. Todo empieza apagado (opt-in) y hay una pausa global: es el interruptor que
 * el usuario debe tener siempre a mano.
 */
data class CaptureSettings(
    val screenText: Boolean = false,
    val notifications: Boolean = false,
    val ambientAudio: Boolean = false,
    /** Pausa global hasta esta marca (epoch ms). 0 = sin pausa. */
    val pausedUntilMs: Long = 0L,
    /** Apps que el usuario no quiere que se vean nunca (además de las sensibles integradas). */
    val denyPackages: Set<String> = emptySet(),
) {
    fun isOn(source: Source): Boolean = when (source) {
        Source.SCREEN -> screenText
        Source.NOTIFICATIONS -> notifications
        Source.AUDIO -> ambientAudio
    }

    fun isPaused(nowMs: Long): Boolean = nowMs < pausedUntilMs
}

enum class CaptureDecision(val allowed: Boolean) {
    ALLOWED(true),
    PAUSED(false),
    SOURCE_OFF(false),
    OWN_APP(false),
    NOISE(false),
    SENSITIVE(false),
    DENIED(false),
}

object CapturePolicy {
    /** Orden de comprobación: pausa, fuente apagada, app propia, ruido, sensible, lista negra del usuario. */
    fun decide(settings: CaptureSettings, source: Source, pkg: String, ownPackage: String, nowMs: Long): CaptureDecision = when {
        settings.isPaused(nowMs) -> CaptureDecision.PAUSED
        !settings.isOn(source) -> CaptureDecision.SOURCE_OFF
        // El audio ambiente no pertenece a ninguna app: solo importan la pausa y el interruptor.
        source == Source.AUDIO -> CaptureDecision.ALLOWED
        pkg == ownPackage -> CaptureDecision.OWN_APP
        // Las notificaciones sí llevan información de apps "ruidosas" (el propio sistema), el resto no.
        source == Source.SCREEN && SensitiveApps.isNoise(pkg) -> CaptureDecision.NOISE
        SensitiveApps.isSensitive(pkg) -> CaptureDecision.SENSITIVE
        pkg in settings.denyPackages -> CaptureDecision.DENIED
        else -> CaptureDecision.ALLOWED
    }
}

/**
 * Apps cuyo contenido no se debe leer: bancos, billeteras, gestores de contraseñas, autenticadores y
 * los ajustes del sistema. La lista es una red de seguridad, no una garantía: el usuario puede añadir
 * más en [CaptureSettings.denyPackages].
 */
object SensitiveApps {
    private val exact = setOf(
        "com.android.settings", "com.miui.securitycenter", "com.miui.securityadd", "com.android.vending",
        "com.google.android.gms", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.miui.packageinstaller",
        // Gestores de contraseñas y autenticadores.
        "com.x8bit.bitwarden", "com.bitwarden.authenticator", "com.lastpass.lpandroid", "com.agilebits.onepassword",
        "keepass2android.keepass2android", "org.keepassdroid", "com.google.android.apps.authenticator2",
        "com.authy.authy", "com.azure.authenticator", "com.beemdevelopment.aegis", "org.shadowice.flocke.andotp",
        "com.proton.android.pass", "me.proton.android.pass", "com.duosecurity.duomobile",
        // Billeteras y pagos.
        "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user", "com.paypal.android.p2pmobile",
        "com.venmo", "com.squareup.cash", "com.mercadopago.wallet", "com.binance.dev", "com.coinbase.android",
        "com.wallet.crypto.trustapp", "io.metamask", "com.revolut.revolut", "com.nu.production",
    )

    /** Segmentos del nombre del paquete que delatan una app financiera. */
    private val financialWords = listOf("bank", "banco", "banca", "bbva", "santander", "wallet", "paypal", "binance", "crypto", "fintech", "cartera")

    /** Teclados, pantalla de inicio y barra de estado: duplican texto o solo muestran teclas. */
    private val noise = setOf(
        "com.android.systemui", "com.miui.home", "com.mi.android.globallauncher", "com.android.launcher3",
        "com.google.android.apps.nexuslauncher", "com.miui.securityinputmethod", "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin", "com.touchtype.swiftkey", "com.sohu.inputmethod.sogou",
        "com.baidu.input_mi", "com.miui.aod", "com.miui.notification", "com.android.incallui",
    )

    fun isSensitive(pkg: String): Boolean {
        if (pkg in exact) return true
        val lower = pkg.lowercase()
        return financialWords.any { lower.contains(it) }
    }

    fun isNoise(pkg: String): Boolean = pkg in noise
}

/**
 * Apps que ningún bloqueo (del usuario o del cerebro) puede tocar: sin ellas el usuario quedaría
 * encerrado fuera de sus ajustes, de una llamada de emergencia o de la propia capa.
 */
object NeverBlock {
    private val protectedPackages = setOf(
        "com.android.settings", "com.miui.securitycenter", "com.android.systemui", "com.android.phone",
        "com.android.dialer", "com.google.android.dialer", "com.android.incallui", "com.android.emergency",
        "com.android.server.telecom", "com.android.contacts", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.miui.packageinstaller", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.miui.home", "com.mi.android.globallauncher",
        "com.android.launcher3", "com.google.android.apps.nexuslauncher", "android",
    )

    fun isProtected(pkg: String, ownPackage: String): Boolean = pkg == ownPackage || pkg in protectedPackages || pkg.isBlank()
}
