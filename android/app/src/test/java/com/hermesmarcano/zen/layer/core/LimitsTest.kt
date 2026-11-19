package com.hermesmarcano.zen.layer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class ForegroundTrackerTest {
    private fun tracker(maxIdle: Long = 2 * HOUR) = ForegroundTracker(UtcDays, maxIdle)

    @Test fun countsASession() {
        val t = tracker()
        t.onForeground("tiktok", 10 * MIN)
        t.onForeground("whatsapp", 25 * MIN)
        assertEquals(15 * MIN, t.usedMs("tiktok", 30 * MIN))
        assertEquals(5 * MIN, t.usedMs("whatsapp", 30 * MIN)) // sesión aún abierta
    }

    @Test fun sameAppRepeatedDoesNotRestartTheSession() {
        val t = tracker()
        t.onForeground("a", 0)
        t.onForeground("a", 5 * MIN)
        t.onForeground("a", 9 * MIN)
        assertEquals(9 * MIN, t.usedMs("a", 9 * MIN))
    }

    @Test fun screenOffClosesTheSession() {
        val t = tracker()
        t.onForeground("a", 0)
        t.onScreenOff(10 * MIN)
        assertEquals(10 * MIN, t.usedMs("a", 5 * HOUR))
    }

    @Test fun sessionAcrossMidnightIsSplitByDay() {
        val t = tracker()
        t.onForeground("a", DAY - 10 * MIN)
        t.onScreenOff(DAY + 20 * MIN)
        assertEquals(20 * MIN, t.usedMs("a", DAY + 30 * MIN)) // "hoy" es el día siguiente
        assertEquals(10 * MIN, t.usedMs("a", DAY - 1)) // y "ayer" conserva su parte
    }

    @Test fun staleSessionIsCappedByTheIdleLimit() {
        val t = tracker(maxIdle = 30 * MIN)
        t.onForeground("a", 0)
        // Nunca llegó el aviso de pantalla apagada: no se inflan 5 horas.
        assertEquals(30 * MIN, t.usedMs("a", 5 * HOUR))
        t.onForeground("b", 5 * HOUR)
        assertEquals(30 * MIN, t.usedMs("a", 5 * HOUR + MIN))
    }

    @Test fun sameAppAfterALongSilenceDoesNotInflateTheSession() {
        val t = tracker(maxIdle = 30 * MIN)
        t.onForeground("a", 0)
        t.onForeground("a", 10 * MIN)
        // Cinco horas después la misma app da señal: solo se acredita hasta que caducó la sesión (10 + 30 min).
        t.onForeground("a", 10 * MIN + 5 * HOUR)
        assertEquals(40 * MIN + MIN, t.usedMs("a", 11 * MIN + 5 * HOUR)) // 40 de la vieja + 1 de la nueva
    }

    @Test fun clockGoingBackwardsNeverSubtracts() {
        val t = tracker()
        t.onForeground("a", 10 * MIN)
        t.onScreenOff(5 * MIN)
        assertEquals(0L, t.usedMs("a", 6 * MIN))
    }

    @Test fun restoreRecoversTodayButIgnoresOtherDays() {
        val t = tracker()
        t.restore(day = 1, saved = mapOf("a" to 7 * MIN), nowMs = DAY + HOUR)
        assertEquals(7 * MIN, t.usedMs("a", DAY + HOUR))
        val other = tracker()
        other.restore(day = 0, saved = mapOf("a" to 7 * MIN), nowMs = DAY + HOUR)
        assertEquals(0L, other.usedMs("a", DAY + HOUR))
    }

    @Test fun restoreNeverLowersWhatIsAlreadyCounted() {
        val t = tracker()
        t.onForeground("a", DAY)
        t.onScreenOff(DAY + 10 * MIN)
        t.restore(day = 1, saved = mapOf("a" to 3 * MIN), nowMs = DAY + 11 * MIN)
        assertEquals(10 * MIN, t.usedMs("a", DAY + 11 * MIN))
    }
}

class LimitPolicyTest {
    private val tiktok = LimitRule("tiktok", "TikTok", dailyLimitMs = 45 * MIN)
    private fun eval(rule: LimitRule, used: Long, minute: Int = 600, sent: Set<Int> = emptySet()) =
        LimitPolicy.evaluate(rule, used, minute, nowMs = 1_000L, endOfDayMs = DAY, sentLevels = sent)

    @Test fun belowTheSoftThresholdNothingHappens() = assertEquals(LimitDecision.Ok, eval(tiktok, 20 * MIN))

    @Test fun softNudgeAtEightyPercentOnlyOnce() {
        val decision = eval(tiktok, 36 * MIN) as LimitDecision.Nudge
        assertEquals(80, decision.level)
        assertEquals(LimitDecision.Ok, eval(tiktok, 40 * MIN, sent = setOf(80)))
    }

    @Test fun fullNudgeWhenTheLimitIsReached() {
        assertEquals(100, (eval(tiktok, 45 * MIN) as LimitDecision.Nudge).level)
        assertEquals(100, (eval(tiktok, 45 * MIN, sent = setOf(80)) as LimitDecision.Nudge).level)
        assertEquals(LimitDecision.Ok, eval(tiktok, 90 * MIN, sent = setOf(80, 100)))
    }

    @Test fun skippingStraightToTheLimitDoesNotAlsoSendTheSoftNudge() {
        assertEquals(LimitDecision.Ok, eval(tiktok, 44 * MIN, sent = setOf(100)))
    }

    @Test fun blockAtLimitBlocksUntilEndOfDay() {
        val hard = tiktok.copy(blockAtLimit = true)
        val block = eval(hard, 46 * MIN) as LimitDecision.Block
        assertEquals(BlockReason.LIMIT, block.reason)
        assertEquals(DAY, block.untilMs)
    }

    @Test fun noLimitMeansNoDecision() = assertEquals(LimitDecision.Ok, eval(tiktok.copy(dailyLimitMs = 0), 10 * HOUR))

    @Test fun windowBlocksUntilItEnds() {
        val night = tiktok.copy(window = DayWindow(22 * 60, 7 * 60))
        val block = eval(night, 0, minute = 23 * 60) as LimitDecision.Block
        assertEquals(BlockReason.WINDOW, block.reason)
        assertEquals(1_000L + 8 * HOUR, block.untilMs)
        assertEquals(LimitDecision.Ok, eval(night, 0, minute = 12 * 60))
    }

    @Test fun windowMath() {
        val night = DayWindow(22 * 60, 7 * 60)
        assertTrue(night.contains(22 * 60))
        assertTrue(night.contains(0))
        assertTrue(night.contains(6 * 60 + 59))
        assertFalse(night.contains(7 * 60))
        assertFalse(night.contains(21 * 60 + 59))
        assertEquals(60, night.minutesUntilEnd(6 * 60))
        assertEquals(9 * 60, night.minutesUntilEnd(22 * 60))
        val day = DayWindow(9 * 60, 17 * 60)
        assertTrue(day.contains(9 * 60))
        assertFalse(day.contains(17 * 60))
        assertFalse(DayWindow(100, 100).isValid)
        assertFalse(DayWindow(-1, 50).isValid)
        assertFalse(DayWindow(0, 1440).isValid)
        assertFalse(DayWindow(100, 100).contains(100))
    }
}

class BlockBookTest {
    private val own = "com.hermesmarcano.zen"

    @Test fun addsAndFindsAnActiveBlock() {
        val book = BlockBook.add(emptyList(), BlockEntry("tiktok", 10 * MIN, BlockReason.LIMIT), own, nowMs = 0)
        assertNotNull(BlockBook.active(book, "tiktok", 5 * MIN))
        assertNull(BlockBook.active(book, "tiktok", 10 * MIN))
        assertNull(BlockBook.active(book, "otra", 5 * MIN))
    }

    @Test fun protectedAppsAreRefused() {
        for (p in listOf(own, "com.android.settings", "com.android.phone")) {
            assertTrue(BlockBook.add(emptyList(), BlockEntry(p, HOUR, BlockReason.BRAIN), own, 0).isEmpty())
        }
    }

    @Test fun expiredEntriesAreRefusedAndPruned() {
        assertTrue(BlockBook.add(emptyList(), BlockEntry("a", 100, BlockReason.MANUAL), own, nowMs = 100).isEmpty())
        val book = listOf(BlockEntry("a", 100, BlockReason.MANUAL), BlockEntry("b", 900, BlockReason.MANUAL))
        assertEquals(listOf("b"), BlockBook.prune(book, 500).map { it.pkg })
    }

    @Test fun aLaterBlockExtendsButAnEarlierOneNeverShortens() {
        var book = BlockBook.add(emptyList(), BlockEntry("a", 60 * MIN, BlockReason.LIMIT), own, 0)
        book = BlockBook.add(book, BlockEntry("a", 10 * MIN, BlockReason.BRAIN), own, 0)
        assertEquals(60 * MIN, BlockBook.active(book, "a", 0)!!.untilMs)
        book = BlockBook.add(book, BlockEntry("a", 90 * MIN, BlockReason.BRAIN), own, 0)
        assertEquals(90 * MIN, BlockBook.active(book, "a", 0)!!.untilMs)
        assertEquals(1, book.size)
    }

    @Test fun removeLiftsTheBlock() {
        val book = BlockBook.add(emptyList(), BlockEntry("a", HOUR, BlockReason.LIMIT), own, 0)
        assertTrue(BlockBook.remove(book, "a").isEmpty())
    }
}

class RelaxPolicyTest {
    private val reason = "necesito ver el video de mi hermana"

    @Test fun mustWaitTheCooldown() {
        val r = RelaxPolicy.confirm(requestedAtMs = 0, nowMs = 20_000, reason = reason, grantedToday = 0)
        assertEquals(RelaxPolicy.Result.TooEarly(40_000), r)
    }

    @Test fun mustGiveAReason() {
        assertEquals(RelaxPolicy.Result.NeedReason, RelaxPolicy.confirm(0, 61_000, "   ", 0))
        assertEquals(RelaxPolicy.Result.NeedReason, RelaxPolicy.confirm(0, 61_000, "porque", 0)) // 6 < 8
    }

    @Test fun grantsOnlyAShortExtension() {
        val r = RelaxPolicy.confirm(0, 61_000, reason, 0) as RelaxPolicy.Result.Granted
        assertEquals(61_000 + RelaxPolicy.EXTENSION_MS, r.untilMs)
    }

    @Test fun dailyCapApplies() {
        assertEquals(RelaxPolicy.Result.LimitReached, RelaxPolicy.confirm(0, HOUR, reason, RelaxPolicy.MAX_PER_DAY))
    }

    @Test fun clockGoingBackwardsNeverShortensTheWait() {
        assertTrue(RelaxPolicy.confirm(requestedAtMs = 10 * MIN, nowMs = MIN, reason = reason, grantedToday = 0) is RelaxPolicy.Result.TooEarly)
    }
}
