package com.hermesmarcano.zen.layer.core

/**
 * Todo el estado persistente de la capa en un solo objeto inmutable (se guarda como un JSON). Lo que se
 * cuenta por día (`usageTotals`, `nudgesSent`, `relaxGranted`) lleva su día en [dailyDay] y se reinicia solo.
 */
data class LayerState(
    val capture: CaptureSettings = CaptureSettings(),
    val limits: List<LimitRule> = emptyList(),
    /** Cambios de reglas que aflojan un compromiso y esperan a cumplir su demora (ver RuleChange). */
    val pendingRules: List<PendingRule> = emptyList(),
    val blocks: List<BlockEntry> = emptyList(),
    /** Pases temporales ("te dejo 15 minutos"): app → hasta cuándo no se aplica el bloqueo. */
    val passes: Map<String, Long> = emptyMap(),
    /** Cuándo pidió el usuario soltar el bloqueo de cada app (empieza la espera). */
    val relaxRequests: Map<String, Long> = emptyMap(),
    val drafts: List<Draft> = emptyList(),

    val brainUrl: String = "",
    val brainToken: String = "",
    val deviceId: String = "",
    val commandCursor: String = "",
    val seenCommandIds: List<String> = emptyList(),
    val lastSyncOkAtMs: Long? = null,
    val lastSyncError: String = "",

    val dailyDay: Long = -1L,
    val usageTotals: Map<String, Long> = emptyMap(),
    val nudgesSent: Map<String, Set<Int>> = emptyMap(),
    val relaxGranted: Int = 0,

    val autostartConfirmed: Boolean = false,
    val repairsMs: List<Long> = emptyList(),
    val accessibilityDownSinceMs: Long? = null,
    val lastRepairNoticeAtMs: Long = 0L,
) {
    val brainConfigured: Boolean
        get() = brainToken.isNotBlank() && BrainUrl.validate(brainUrl) is BrainUrl.Result.Ok

    /** Devuelve el estado con los contadores diarios a cero si [day] no es el día que guardan. */
    fun forDay(day: Long): LayerState =
        if (dailyDay == day) this else copy(dailyDay = day, usageTotals = emptyMap(), nudgesSent = emptyMap(), relaxGranted = 0)
}

interface LayerStateStore {
    val current: LayerState

    /** Aplica [transform] y guarda. Puede lanzar `IOException`; en ese caso el estado en memoria no cambia. */
    fun update(transform: (LayerState) -> LayerState): LayerState
}

/** Línea de tiempo local: lo que la capa observó, con búsqueda de texto completo. */
interface TimelineStore {
    fun insert(event: LayerEvent)
    fun search(query: String, limit: Int): List<LayerEvent>
    fun recent(limit: Int): List<LayerEvent>
    fun count(): Int
    fun deleteAll()
    fun deletePackage(pkg: String): Int

    /** Aplica la retención (antigüedad y filas máximas). Devuelve cuántos eventos borró. */
    fun prune(nowMs: Long): Int
}

/** Cola de envío al cerebro, persistente. */
interface OutboxStore {
    fun enqueue(item: OutboxItem)
    fun due(kind: OutboxItem.Kind, nowMs: Long, limit: Int): List<OutboxItem>

    /**
     * Pospone [items] con espera creciente. Con [rejected] (el servidor dijo que no) cuenta un rechazo y
     * descarta los que ya no tienen arreglo; devuelve los descartados para borrar sus archivos de audio.
     */
    fun markFailed(items: List<OutboxItem>, nowMs: Long, random: Double, rejected: Boolean): List<OutboxItem>
    fun remove(ids: Collection<String>)
    fun count(): Int

    /** Quita lo caducado o sobrante y devuelve lo descartado (para borrar sus archivos de audio). */
    fun prune(nowMs: Long): List<OutboxItem>
    fun clear(): List<OutboxItem>
}
