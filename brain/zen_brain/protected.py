"""Apps que el adaptador nunca bloquea y de las que nunca reenvía contenido al modelo.

Es la segunda barrera: el teléfono ya filtra por paquete antes de capturar (CapturePolicy / NeverBlock en la
app). Aquí se repite a propósito: si una de las dos falla, la otra sigue en pie.
"""

from __future__ import annotations

# Sin ellas el usuario quedaría fuera de sus ajustes, de una llamada de emergencia o de la propia capa.
NEVER_BLOCK = frozenset({
    "com.android.settings", "com.miui.securitycenter", "com.android.systemui", "com.android.phone",
    "com.android.dialer", "com.google.android.dialer", "com.android.incallui", "com.android.emergency",
    "com.android.server.telecom", "com.android.contacts", "com.android.packageinstaller",
    "com.google.android.packageinstaller", "com.miui.packageinstaller", "com.android.permissioncontroller",
    "com.google.android.permissioncontroller", "com.miui.home", "com.mi.android.globallauncher",
    "com.android.launcher3", "com.google.android.apps.nexuslauncher", "android",
    # SMS y mensajería del sistema (códigos, avisos de emergencia).
    "com.android.mms", "com.google.android.apps.messaging", "com.miui.smsextra", "com.xiaomi.mms",
    # Tienda y servicios de Google: bloquearlos rompe actualizaciones y el propio teléfono.
    "com.android.vending", "com.google.android.gms",
})

# Contenido que no debe llegar al modelo: contraseñas, autenticadores, billeteras, bancos y salud.
SENSITIVE_EXACT = frozenset({
    "com.x8bit.bitwarden", "com.bitwarden.authenticator", "com.lastpass.lpandroid", "com.agilebits.onepassword",
    "keepass2android.keepass2android", "org.keepassdroid", "com.google.android.apps.authenticator2",
    "com.authy.authy", "com.azure.authenticator", "com.beemdevelopment.aegis", "org.shadowice.flocke.andotp",
    "com.proton.android.pass", "me.proton.android.pass", "com.duosecurity.duomobile",
    "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user", "com.paypal.android.p2pmobile",
    "com.venmo", "com.squareup.cash", "com.mercadopago.wallet", "com.binance.dev", "com.coinbase.android",
    "com.wallet.crypto.trustapp", "io.metamask", "com.revolut.revolut", "com.nu.production",
})

# Trozos del nombre del paquete que delatan una app financiera o de salud.
SENSITIVE_WORDS = (
    "bank", "banco", "banca", "bbva", "santander", "wallet", "paypal", "binance", "crypto", "fintech", "cartera",
    "health", "salud", "medic", "clinic", "hospital", "pharm", "farmacia", "telemed", "password", "authenticator",
)

# Paquetes propios (la app normal y la variante): no se bloquean ni se reenvían.
OWN_PREFIX = "com.hermesmarcano.zen"


def is_never_block(pkg: str) -> bool:
    return not pkg or pkg in NEVER_BLOCK or pkg == OWN_PREFIX or pkg.startswith(OWN_PREFIX + ".")


def is_sensitive(pkg: str) -> bool:
    if pkg in SENSITIVE_EXACT:
        return True
    lower = pkg.lower()
    return any(word in lower for word in SENSITIVE_WORDS)
