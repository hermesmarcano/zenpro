package com.hermesmarcano.zen.alarm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Estado de la alarma que está sonando, compartido entre el servicio y la pantalla de apagado. */
data class RingState(
    val alarmId: String,
    /** `SystemClock.elapsedRealtime()` en segundos al empezar a sonar: reloj monotónico, inmune a cambios de hora. */
    val startedAtElapsed: Double,
    /**
     * Ancla que se exige, fijada al empezar a sonar. Si se leyera del estado en vivo, borrar o
     * editar la alarma mientras suena dejaría la pantalla "sin ancla" y bastaría esperar 2 minutos.
     */
    val anchorId: String?,
)

object AlarmRuntime {
    private val mutableRing = MutableStateFlow<RingState?>(null)
    val ring: StateFlow<RingState?> get() = mutableRing

    /** La pantalla de apagado tiene la cámara abierta: se trata como "en mano" (ver SmartVolumeController). */
    @Volatile
    var userEngaged: Boolean = false

    fun begin(state: RingState) {
        userEngaged = false
        mutableRing.value = state
    }

    fun end() {
        userEngaged = false
        mutableRing.value = null
    }
}
