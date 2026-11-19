package com.hermesmarcano.zen.layer.core

/** Franja diaria en minutos desde medianoche. Si [startMinute] > [endMinute] cruza la medianoche (22:00–07:00). */
data class DayWindow(val startMinute: Int, val endMinute: Int) {
    val isValid: Boolean get() = startMinute in 0 until 1440 && endMinute in 0 until 1440 && startMinute != endMinute

    fun contains(minuteOfDay: Int): Boolean {
        if (!isValid) return false
        return if (startMinute < endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    /** Minutos que faltan para que acabe la franja, estando dentro de ella. */
    fun minutesUntilEnd(minuteOfDay: Int): Int =
        if (endMinute > minuteOfDay) endMinute - minuteOfDay else endMinute + 1440 - minuteOfDay
}

/**
 * Compromiso del usuario sobre una app: un tiempo diario y/o una franja prohibida. El bloqueo duro al
 * llegar al límite ([blockAtLimit]) solo existe porque el usuario lo eligió de antemano.
 */
data class LimitRule(
    val pkg: String,
    val label: String,
    val dailyLimitMs: Long = 0L,
    val blockAtLimit: Boolean = false,
    val window: DayWindow? = null,
)

enum class BlockReason { LIMIT, WINDOW, BRAIN, MANUAL }

data class BlockEntry(val pkg: String, val untilMs: Long, val reason: BlockReason)

sealed interface LimitDecision {
    data object Ok : LimitDecision

    /** Aviso con las palabras del propio usuario. [level] es 80 (cerca) o 100 (superado). */
    data class Nudge(val rule: LimitRule, val usedMs: Long, val level: Int) : LimitDecision

    data class Block(val reason: BlockReason, val untilMs: Long) : LimitDecision
}

object LimitPolicy {
    const val SOFT_PERCENT = 80
    const val FULL_PERCENT = 100

    /**
     * @param minuteOfDay minuto local del día (0..1439)
     * @param endOfDayMs medianoche local siguiente (epoch ms)
     * @param sentLevels avisos de hoy ya enviados para esta app (cada nivel se avisa una sola vez)
     */
    fun evaluate(
        rule: LimitRule,
        usedMs: Long,
        minuteOfDay: Int,
        nowMs: Long,
        endOfDayMs: Long,
        sentLevels: Set<Int>,
    ): LimitDecision {
        val window = rule.window
        if (window != null && window.contains(minuteOfDay)) {
            return LimitDecision.Block(BlockReason.WINDOW, nowMs + window.minutesUntilEnd(minuteOfDay) * 60_000L)
        }
        val limit = rule.dailyLimitMs
        if (limit <= 0L) return LimitDecision.Ok
        if (usedMs >= limit) {
            if (rule.blockAtLimit) return LimitDecision.Block(BlockReason.LIMIT, endOfDayMs)
            if (FULL_PERCENT !in sentLevels) return LimitDecision.Nudge(rule, usedMs, FULL_PERCENT)
        }
        if (usedMs * 100 >= limit * SOFT_PERCENT && SOFT_PERCENT !in sentLevels && FULL_PERCENT !in sentLevels) {
            return LimitDecision.Nudge(rule, usedMs, SOFT_PERCENT)
        }
        return LimitDecision.Ok
    }
}

/** Lista de bloqueos activos. Nunca incluye apps protegidas ([NeverBlock]). */
object BlockBook {
    const val MAX_BRAIN_BLOCK_MS = 24 * 60 * 60 * 1000L

    fun active(blocks: List<BlockEntry>, pkg: String, nowMs: Long): BlockEntry? =
        blocks.firstOrNull { it.pkg == pkg && it.untilMs > nowMs }

    /** Añade o prolonga un bloqueo. Devuelve la lista sin cambios si la app está protegida. */
    fun add(blocks: List<BlockEntry>, entry: BlockEntry, ownPackage: String, nowMs: Long): List<BlockEntry> {
        if (NeverBlock.isProtected(entry.pkg, ownPackage) || entry.untilMs <= nowMs) return prune(blocks, nowMs)
        val existing = blocks.firstOrNull { it.pkg == entry.pkg && it.untilMs > nowMs }
        val merged = if (existing != null && existing.untilMs >= entry.untilMs) existing else entry
        return prune(blocks.filter { it.pkg != entry.pkg } + merged, nowMs)
    }

    fun remove(blocks: List<BlockEntry>, pkg: String): List<BlockEntry> = blocks.filter { it.pkg != pkg }

    fun prune(blocks: List<BlockEntry>, nowMs: Long): List<BlockEntry> = blocks.filter { it.untilMs > nowMs }
}

/**
 * Soltar un bloqueo cuesta algo: esperar un minuto y escribir por qué. Y solo suelta un rato, no el día
 * entero. Es la "fricción" que hace que el compromiso valga algo.
 */
object RelaxPolicy {
    const val COOLDOWN_MS = 60_000L
    const val MIN_REASON_CHARS = 8
    const val EXTENSION_MS = 15 * 60_000L
    const val MAX_PER_DAY = 3

    sealed interface Result {
        data class Granted(val untilMs: Long) : Result
        data class TooEarly(val remainingMs: Long) : Result
        data object NeedReason : Result
        data object LimitReached : Result
    }

    fun confirm(requestedAtMs: Long, nowMs: Long, reason: String, grantedToday: Int): Result {
        if (grantedToday >= MAX_PER_DAY) return Result.LimitReached
        // Un reloj que retrocede cuenta como "acaba de pedirlo": nunca se acorta la espera.
        val waited = if (nowMs < requestedAtMs) 0L else nowMs - requestedAtMs
        if (waited < COOLDOWN_MS) return Result.TooEarly(COOLDOWN_MS - waited)
        if (reason.trim().length < MIN_REASON_CHARS) return Result.NeedReason
        return Result.Granted(nowMs + EXTENSION_MS)
    }
}
