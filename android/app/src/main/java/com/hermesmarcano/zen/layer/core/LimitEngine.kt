package com.hermesmarcano.zen.layer.core

import java.io.IOException

/** Un cambio de regla que aún no manda: aflojar un compromiso tarda en aplicarse. [rule] `null` = quitar la regla. */
data class PendingRule(val pkg: String, val rule: LimitRule?, val effectiveAtMs: Long)

/** Endurecer un límite es inmediato; aflojarlo espera 24 h. Si no, bastaría con editarlo para saltárselo. */
object RuleChange {
    const val LOOSEN_DELAY_MS = 24 * 60 * 60 * 1000L

    fun isLooser(old: LimitRule?, new: LimitRule?): Boolean {
        if (old == null) return false
        if (new == null) return true
        if (old.dailyLimitMs > 0 && (new.dailyLimitMs <= 0 || new.dailyLimitMs > old.dailyLimitMs)) return true
        if (old.blockAtLimit && !new.blockAtLimit) return true
        val oldWindow = old.window?.takeIf { it.isValid }
        if (oldWindow != null) {
            val newWindow = new.window?.takeIf { it.isValid } ?: return true
            if (lengthMinutes(newWindow) < lengthMinutes(oldWindow)) return true
        }
        return false
    }

    private fun lengthMinutes(w: DayWindow): Int = if (w.endMinute > w.startMinute) w.endMinute - w.startMinute else w.endMinute + 1440 - w.startMinute

    sealed interface Applied {
        /** Rige desde ya. */
        data object Now : Applied

        /** Rige a partir de [effectiveAtMs]. */
        data class Later(val effectiveAtMs: Long) : Applied
    }

    /** Aplica el cambio pedido por el usuario sobre [state] (o lo aplaza si afloja). */
    fun request(state: LayerState, pkg: String, proposed: LimitRule?, nowMs: Long): Pair<LayerState, Applied> {
        val old = state.limits.firstOrNull { it.pkg == pkg }
        if (proposed != null && proposed.pkg != pkg) return state to Applied.Now
        val withoutPending = state.pendingRules.filter { it.pkg != pkg }
        if (!isLooser(old, proposed)) {
            val limits = state.limits.filter { it.pkg != pkg } + listOfNotNull(proposed)
            return state.copy(limits = limits, pendingRules = withoutPending) to Applied.Now
        }
        val at = nowMs + LOOSEN_DELAY_MS
        return state.copy(pendingRules = withoutPending + PendingRule(pkg, proposed, at)) to Applied.Later(at)
    }

    /** Pasa a las reglas vigentes lo que ya cumplió su espera. */
    fun applyDue(state: LayerState, nowMs: Long): LayerState {
        val (due, waiting) = state.pendingRules.partition { it.effectiveAtMs <= nowMs }
        if (due.isEmpty()) return state
        var limits = state.limits
        for (p in due.sortedBy { it.effectiveAtMs }) limits = limits.filter { it.pkg != p.pkg } + listOfNotNull(p.rule)
        return state.copy(limits = limits, pendingRules = waiting)
    }

    fun cancelPending(state: LayerState, pkg: String): LayerState = state.copy(pendingRules = state.pendingRules.filter { it.pkg != pkg })
}

/**
 * Orquesta los compromisos: recibe "esta app está delante", cuenta el tiempo, y decide si hay que avisar
 * o bloquear. No toca Android: devuelve qué hacer ([Outcome]) y el servicio de accesibilidad lo ejecuta.
 */
class LimitEngine(
    private val state: LayerStateStore,
    private val tracker: ForegroundTracker,
    private val clock: DayClock,
    private val ownPackage: String,
    private val persistEveryMs: Long = 30_000L,
) {
    data class Outcome(
        /** La app está bloqueada ahora: hay que sacarla de primer plano. */
        val enforce: BlockEntry? = null,
        /** Hay un aviso nuevo que mostrar. */
        val nudge: LimitDecision.Nudge? = null,
    ) {
        companion object {
            val NONE = Outcome()
        }
    }

    private var current: String? = null
    private var lastPersistMs = Long.MIN_VALUE
    private val nudgedLocally = HashSet<String>()
    private var nudgedDay = Long.MIN_VALUE

    private companion object {
        /** Una petición de soltar un bloqueo caduca si no se confirma: hay que volver a empezar la espera. */
        const val RELAX_REQUEST_TTL_MS = 10 * 60_000L
    }

    /** Recupera lo contado hoy tras un reinicio del proceso. */
    @Synchronized
    fun restore(nowMs: Long) {
        val s = state.current
        tracker.restore(s.dailyDay, s.usageTotals, nowMs)
    }

    @Synchronized
    fun onWindow(pkg: String?, kind: WindowKind, nowMs: Long): Outcome {
        ensureDay(nowMs)
        when (kind) {
            WindowKind.TRANSIENT -> return Outcome.NONE
            WindowKind.HOME -> {
                tracker.onForeground(null, nowMs)
                current = null
                persistUsage(nowMs, force = true)
                return Outcome.NONE
            }
            WindowKind.APP -> {
                if (pkg.isNullOrBlank()) return Outcome.NONE
                tracker.onForeground(pkg, nowMs)
                current = pkg
            }
        }
        return evaluate(current!!, nowMs)
    }

    /** Reevalúa la app que está delante (por si pasó el límite sin que hubiera eventos). */
    @Synchronized
    fun tick(nowMs: Long): Outcome {
        val pkg = current ?: return Outcome.NONE
        ensureDay(nowMs)
        return evaluate(pkg, nowMs)
    }

    @Synchronized
    fun onScreenOff(nowMs: Long) {
        tracker.onScreenOff(nowMs)
        current = null
        persistUsage(nowMs, force = true)
    }

    @Synchronized
    fun foregroundPackage(): String? = current

    /** ¿Hay alguna regla o bloqueo que haga útil el reloj periódico mientras [pkg] está delante? */
    fun needsTick(pkg: String?): Boolean {
        if (pkg == null) return false
        val s = state.current
        return s.limits.any { it.pkg == pkg } || s.blocks.any { it.pkg == pkg }
    }

    private fun evaluate(pkg: String, nowMs: Long): Outcome {
        // Lo protegido (ajustes, teléfono, esta app) nunca se bloquea, ni siquiera si un estado corrupto lo dice.
        if (NeverBlock.isProtected(pkg, ownPackage)) return Outcome.NONE
        val day = clock.dayOf(nowMs)
        // Se lee siempre "como de hoy": si el contador guardado es de ayer, no cuentan sus avisos.
        val s = state.current.forDay(day)
        val pass = s.passes[pkg]
        if (pass != null && pass > nowMs) return Outcome.NONE
        BlockBook.active(s.blocks, pkg, nowMs)?.let { return Outcome(enforce = it) }
        val rule = s.limits.firstOrNull { it.pkg == pkg } ?: return Outcome.NONE

        val used = tracker.usedMs(pkg, nowMs)
        val sent = s.nudgesSent[pkg].orEmpty() + nudgedLocally.filter { it.startsWith("$pkg|") }.map { it.substringAfter('|').toInt() }
        return when (val decision = LimitPolicy.evaluate(rule, used, clock.minuteOfDay(nowMs), nowMs, clock.nextDayStart(nowMs), sent)) {
            LimitDecision.Ok -> Outcome.NONE
            is LimitDecision.Nudge -> {
                nudgedLocally += "$pkg|${decision.level}"
                safeUpdate {
                    val d = it.forDay(day)
                    d.copy(nudgesSent = d.nudgesSent + (pkg to (d.nudgesSent[pkg].orEmpty() + decision.level)))
                }
                Outcome(nudge = decision)
            }
            is LimitDecision.Block -> {
                val entry = BlockEntry(pkg, decision.untilMs, decision.reason)
                safeUpdate { it.copy(blocks = BlockBook.add(it.blocks, entry, ownPackage, nowMs)) }
                Outcome(enforce = entry)
            }
        }
    }

    /** Pone al día el contador diario, descarta lo caducado y aplica los cambios de reglas que ya cumplieron su espera. */
    private fun ensureDay(nowMs: Long) {
        val day = clock.dayOf(nowMs)
        if (nudgedDay != day) {
            nudgedLocally.clear()
            nudgedDay = day
        }
        val s = state.current
        if (tidy(s, nowMs, day) != s) safeUpdate { tidy(it, nowMs, day) }
    }

    /** Se calcula sobre el estado que `update` entrega (no sobre una copia vieja) para no pisar cambios de la pantalla. */
    private fun tidy(s: LayerState, nowMs: Long, day: Long): LayerState {
        val due = RuleChange.applyDue(s, nowMs)
        return due.copy(
            blocks = BlockBook.prune(due.blocks, nowMs),
            passes = due.passes.filterValues { it > nowMs },
            relaxRequests = due.relaxRequests.filterValues { nowMs - it in 0 until RELAX_REQUEST_TTL_MS },
        ).forDay(day)
    }

    private fun persistUsage(nowMs: Long, force: Boolean) {
        if (!force && lastPersistMs != Long.MIN_VALUE && nowMs - lastPersistMs < persistEveryMs) return
        lastPersistMs = nowMs
        val day = clock.dayOf(nowMs)
        val totals = tracker.todayTotals(nowMs)
        safeUpdate { it.forDay(day).copy(usageTotals = totals) }
    }

    private fun safeUpdate(transform: (LayerState) -> LayerState) {
        try {
            state.update(transform)
        } catch (_: IOException) {
            // Sin disco no se puede recordar; el contador en memoria sigue valiendo hasta que el proceso muera.
        }
    }
}

/** Acciones del usuario (o de un aviso) sobre los bloqueos. Soltar uno cuesta: espera, motivo y un tope diario. */
class BlockActions(
    private val state: LayerStateStore,
    private val clock: DayClock,
    private val ownPackage: String,
    private val log: (title: String, text: String, pkg: String) -> Unit = { _, _, _ -> },
) {
    /** Bloquea [pkg] hasta medianoche. Devuelve `false` si la app está protegida (ajustes, teléfono, esta app). */
    fun blockUntilEndOfDay(pkg: String, reason: BlockReason, nowMs: Long): Boolean =
        blockUntil(pkg, clock.nextDayStart(nowMs), reason, nowMs)

    /** Bloquea [pkg] hasta [untilMs]. Un bloqueo nunca acorta a otro más largo que ya esté puesto. */
    fun blockUntil(pkg: String, untilMs: Long, reason: BlockReason, nowMs: Long): Boolean {
        if (NeverBlock.isProtected(pkg, ownPackage) || untilMs <= nowMs) return false
        val entry = BlockEntry(pkg, untilMs, reason)
        state.update { it.copy(blocks = BlockBook.add(it.blocks, entry, ownPackage, nowMs)) }
        log("Bloqueo", "Bloqueada $pkg (${reason.name.lowercase()}).", pkg)
        return true
    }

    /** El usuario quiere soltar el bloqueo: empieza la espera. */
    fun requestRelax(pkg: String, nowMs: Long) {
        state.update { it.copy(relaxRequests = it.relaxRequests + (pkg to nowMs)) }
    }

    /** Confirma tras la espera, con el motivo escrito. Si procede, da un pase corto (no quita el bloqueo del día). */
    fun confirmRelax(pkg: String, reason: String, nowMs: Long): RelaxPolicy.Result {
        val s = state.current.forDay(clock.dayOf(nowMs))
        val requestedAt = s.relaxRequests[pkg] ?: nowMs
        val result = RelaxPolicy.confirm(requestedAt, nowMs, reason, s.relaxGranted)
        if (result is RelaxPolicy.Result.Granted) {
            state.update {
                val day = it.forDay(clock.dayOf(nowMs))
                day.copy(
                    passes = day.passes + (pkg to result.untilMs),
                    relaxRequests = day.relaxRequests - pkg,
                    relaxGranted = day.relaxGranted + 1,
                )
            }
            log("Bloqueo soltado", "Pase de ${RelaxPolicy.EXTENSION_MS / 60_000} min para $pkg. Motivo: ${reason.trim()}", pkg)
        }
        return result
    }

    fun cancelRelax(pkg: String) {
        state.update { it.copy(relaxRequests = it.relaxRequests - pkg) }
    }

    fun activeBlocks(nowMs: Long): List<BlockEntry> = BlockBook.prune(state.current.blocks, nowMs)
}
