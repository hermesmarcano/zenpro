package com.hermesmarcano.zen.core

/**
 * Filtra las lecturas repetidas del escáner. La cámara lee el mismo QR decenas de veces por
 * segundo: un código distinto se acepta al instante (para avisar de que no es el de ZEN), pero el
 * mismo código solo se vuelve a notificar pasado [repeatAfterMillis], para no parpadear el aviso.
 */
class ScanDebouncer(private val repeatAfterMillis: Long = 2_000) {
    private var lastPayload: String? = null
    private var lastAt = 0L

    fun shouldNotify(payload: String, nowMillis: Long): Boolean {
        val isNew = payload != lastPayload
        // Un reloj que retrocede se trata como "ya pasó el tiempo" para no bloquear lecturas.
        val elapsed = nowMillis - lastAt
        if (!isNew && elapsed in 0 until repeatAfterMillis) return false
        lastPayload = payload
        lastAt = nowMillis
        return true
    }

    fun reset() {
        lastPayload = null
        lastAt = 0L
    }
}
