package com.hermesmarcano.zen.layer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TIKTOK = "com.zhiliaoapp.musically"
private const val OWN = "com.hermesmarcano.zen"

class LimitEngineTest {
    private val rule = LimitRule(TIKTOK, "TikTok", dailyLimitMs = 45 * MIN)

    private fun setup(rule: LimitRule? = this.rule, state: MemoryState = MemoryState(LayerState(limits = listOfNotNull(rule)))) =
        state to LimitEngine(state, ForegroundTracker(UtcDays), UtcDays, OWN)

    @Test fun nudgesAtEightyAndOneHundredPercentOnlyOnce() {
        val (state, engine) = setup()
        assertEquals(LimitEngine.Outcome.NONE, engine.onWindow(TIKTOK, WindowKind.APP, 0))
        assertEquals(LimitEngine.Outcome.NONE, engine.tick(10 * MIN))
        assertEquals(80, engine.tick(36 * MIN).nudge!!.level)
        assertNull(engine.tick(37 * MIN).nudge)
        assertEquals(100, engine.tick(45 * MIN).nudge!!.level)
        assertEquals(LimitEngine.Outcome.NONE, engine.tick(90 * MIN))
        assertEquals(setOf(80, 100), state.current.nudgesSent[TIKTOK])
    }

    @Test fun timeAtHomeIsNotCounted() {
        val (_, engine) = setup()
        engine.onWindow(TIKTOK, WindowKind.APP, 0)
        engine.onWindow("com.miui.home", WindowKind.HOME, 20 * MIN)
        // Tres horas en la pantalla de inicio no suman nada.
        assertEquals(LimitEngine.Outcome.NONE, engine.tick(200 * MIN))
        engine.onWindow(TIKTOK, WindowKind.APP, 201 * MIN)
        assertNull(engine.tick(215 * MIN).nudge) // 20 + 14 = 34 min < 36
        assertEquals(80, engine.tick(217 * MIN).nudge!!.level) // 20 + 16 = 36 min
    }

    @Test fun aKeyboardOverTheAppDoesNotEndTheSession() {
        val (_, engine) = setup()
        engine.onWindow(TIKTOK, WindowKind.APP, 0)
        engine.onWindow("com.miui.securityinputmethod", WindowKind.TRANSIENT, 10 * MIN)
        assertEquals(TIKTOK, engine.foregroundPackage())
        assertEquals(80, engine.tick(36 * MIN).nudge!!.level)
    }

    @Test fun screenOffEndsTheSession() {
        val (_, engine) = setup()
        engine.onWindow(TIKTOK, WindowKind.APP, 0)
        engine.onScreenOff(10 * MIN)
        assertNull(engine.foregroundPackage())
        assertEquals(LimitEngine.Outcome.NONE, engine.tick(5 * HOUR))
    }

    @Test fun blockAtLimitEnforcesUntilMidnight() {
        val (state, engine) = setup(rule.copy(blockAtLimit = true))
        engine.onWindow(TIKTOK, WindowKind.APP, 0)
        val first = engine.tick(45 * MIN).enforce!!
        assertEquals(BlockReason.LIMIT, first.reason)
        assertEquals(DAY, first.untilMs)
        assertNotNull(BlockBook.active(state.current.blocks, TIKTOK, 46 * MIN))
        // Volver a abrirla sigue bloqueada.
        assertNotNull(engine.onWindow(TIKTOK, WindowKind.APP, 50 * MIN).enforce)
        // Pasada la medianoche empieza de nuevo y hoy no lleva nada.
        assertEquals(LimitEngine.Outcome.NONE, engine.onWindow(TIKTOK, WindowKind.APP, DAY + MIN))
    }

    @Test fun aTemporaryPassSuspendsTheBlockThenItReturns() {
        val (state, engine) = setup(rule.copy(blockAtLimit = true))
        val actions = BlockActions(state, UtcDays, OWN)
        engine.onWindow(TIKTOK, WindowKind.APP, 0)
        assertNotNull(engine.tick(45 * MIN).enforce)

        val t = 50 * MIN
        actions.requestRelax(TIKTOK, t)
        val granted = actions.confirmRelax(TIKTOK, "necesito ver el video de mi hermana", t + 61_000) as RelaxPolicy.Result.Granted
        assertNull(engine.onWindow(TIKTOK, WindowKind.APP, t + 62_000).enforce)
        assertNull(engine.tick(granted.untilMs - 1).enforce)
        assertNotNull("al acabar el pase vuelve el bloqueo", engine.tick(granted.untilMs + 1).enforce)
    }

    @Test fun windowRuleBlocksAtNightAndNotInTheDay() {
        val night = rule.copy(dailyLimitMs = 0, window = DayWindow(22 * 60, 7 * 60))
        val (_, engine) = setup(night)
        assertNotNull(engine.onWindow(TIKTOK, WindowKind.APP, 23 * HOUR).enforce)
        val (_, day) = setup(night)
        assertEquals(LimitEngine.Outcome.NONE, day.onWindow(TIKTOK, WindowKind.APP, 12 * HOUR))
    }

    @Test fun protectedAppsAreNeverEnforcedEvenIfTheStateSaysSo() {
        val bad = LayerState(
            limits = listOf(LimitRule("com.android.settings", "Ajustes", dailyLimitMs = MIN, blockAtLimit = true)),
            blocks = listOf(BlockEntry("com.android.settings", DAY, BlockReason.BRAIN), BlockEntry(OWN, DAY, BlockReason.BRAIN)),
        )
        val (_, engine) = setup(null, MemoryState(bad))
        assertEquals(LimitEngine.Outcome.NONE, engine.onWindow("com.android.settings", WindowKind.APP, 0))
        assertEquals(LimitEngine.Outcome.NONE, engine.tick(2 * HOUR))
        assertEquals(LimitEngine.Outcome.NONE, engine.onWindow(OWN, WindowKind.APP, 2 * HOUR))
    }

    @Test fun usageSurvivesAProcessRestart() {
        val (state, first) = setup()
        first.onWindow(TIKTOK, WindowKind.APP, 0)
        first.onScreenOff(20 * MIN)
        assertEquals(20 * MIN, state.current.usageTotals[TIKTOK])

        val second = LimitEngine(state, ForegroundTracker(UtcDays), UtcDays, OWN)
        second.restore(21 * MIN)
        second.onWindow(TIKTOK, WindowKind.APP, 21 * MIN)
        assertNull(second.tick(35 * MIN).nudge) // 20 + 14
        assertEquals(80, second.tick(37 * MIN).nudge!!.level) // 20 + 16
    }

    @Test fun aNewDayResetsTheCountersAndTheNudges() {
        val (state, engine) = setup()
        engine.onWindow(TIKTOK, WindowKind.APP, 0)
        assertEquals(80, engine.tick(40 * MIN).nudge!!.level)
        engine.onWindow("com.miui.home", WindowKind.HOME, 41 * MIN)

        val tomorrow = DAY + 10 * MIN
        assertEquals(LimitEngine.Outcome.NONE, engine.onWindow(TIKTOK, WindowKind.APP, tomorrow))
        assertTrue(state.current.nudgesSent.isEmpty())
        assertEquals("hoy el aviso vuelve a poder darse", 80, engine.tick(tomorrow + 36 * MIN).nudge!!.level)
    }

    @Test fun diskFailuresNeverCrashNorRepeatTheNudge() {
        val (state, engine) = setup()
        state.failWrites = true
        engine.onWindow(TIKTOK, WindowKind.APP, 0)
        assertEquals(80, engine.tick(36 * MIN).nudge!!.level)
        assertNull("sin poder guardar, no se repite", engine.tick(37 * MIN).nudge)
    }

    @Test fun needsTickOnlyForAppsWithRules() {
        val (_, engine) = setup()
        assertTrue(engine.needsTick(TIKTOK))
        assertFalse(engine.needsTick("com.whatsapp"))
        assertFalse(engine.needsTick(null))
    }

    @Test fun staleBlocksAndPassesAreCleanedUp() {
        val state = MemoryState(
            LayerState(
                blocks = listOf(BlockEntry("a", 5 * MIN, BlockReason.LIMIT)),
                passes = mapOf("a" to 5 * MIN),
                relaxRequests = mapOf("a" to 0L),
            ),
        )
        val engine = LimitEngine(state, ForegroundTracker(UtcDays), UtcDays, OWN)
        engine.onWindow("com.whatsapp", WindowKind.APP, HOUR)
        assertTrue(state.current.blocks.isEmpty())
        assertTrue(state.current.passes.isEmpty())
        assertTrue(state.current.relaxRequests.isEmpty())
    }

    @Test fun aLoosenedRuleTakesEffectOnlyAfterTheDelay() {
        val (state, engine) = setup(rule.copy(dailyLimitMs = 30 * MIN))
        val (next, applied) = RuleChange.request(state.current, TIKTOK, rule.copy(dailyLimitMs = 3 * HOUR), nowMs = 0)
        state.update { next }
        assertTrue(applied is RuleChange.Applied.Later)
        engine.onWindow(TIKTOK, WindowKind.APP, 0)
        // Con el límite de 30 min aún vigente, a los 25 min ya avisa (80 % de 30).
        assertEquals(80, engine.tick(25 * MIN).nudge!!.level)
        // Cumplida la espera, el límite nuevo manda.
        engine.onWindow("com.miui.home", WindowKind.HOME, 26 * MIN)
        engine.onWindow(TIKTOK, WindowKind.APP, RuleChange.LOOSEN_DELAY_MS + MIN)
        assertEquals(3 * HOUR, state.current.limits.single().dailyLimitMs)
        assertTrue(state.current.pendingRules.isEmpty())
    }
}

class RuleChangeTest {
    private val base = LimitRule("a", "A", dailyLimitMs = 45 * MIN, blockAtLimit = true, window = DayWindow(22 * 60, 7 * 60))

    @Test fun whatCountsAsLooser() {
        assertTrue(RuleChange.isLooser(base, null))
        assertTrue(RuleChange.isLooser(base, base.copy(dailyLimitMs = 60 * MIN)))
        assertTrue(RuleChange.isLooser(base, base.copy(dailyLimitMs = 0)))
        assertTrue(RuleChange.isLooser(base, base.copy(blockAtLimit = false)))
        assertTrue(RuleChange.isLooser(base, base.copy(window = null)))
        assertTrue(RuleChange.isLooser(base, base.copy(window = DayWindow(23 * 60, 7 * 60))))
    }

    @Test fun whatDoesNot() {
        assertFalse(RuleChange.isLooser(null, base))
        assertFalse(RuleChange.isLooser(base, base))
        assertFalse(RuleChange.isLooser(base, base.copy(dailyLimitMs = 30 * MIN)))
        assertFalse(RuleChange.isLooser(base, base.copy(window = DayWindow(21 * 60, 7 * 60))))
        // Un límite que antes no existía puede ponerse o quitarse sin demora.
        val none = base.copy(dailyLimitMs = 0, blockAtLimit = false, window = null)
        assertFalse(RuleChange.isLooser(none, none.copy(dailyLimitMs = 10 * MIN)))
    }

    @Test fun tighteningAppliesNowAndLooseningWaits() {
        val state = LayerState(limits = listOf(base))
        val (tight, a1) = RuleChange.request(state, "a", base.copy(dailyLimitMs = 20 * MIN), nowMs = 0)
        assertEquals(RuleChange.Applied.Now, a1)
        assertEquals(20 * MIN, tight.limits.single().dailyLimitMs)

        val (loose, a2) = RuleChange.request(tight, "a", base.copy(dailyLimitMs = 2 * HOUR), nowMs = 1_000)
        assertEquals(RuleChange.Applied.Later(1_000 + RuleChange.LOOSEN_DELAY_MS), a2)
        assertEquals("sigue vigente la regla estricta", 20 * MIN, loose.limits.single().dailyLimitMs)
        assertEquals(1, loose.pendingRules.size)
    }

    @Test fun addingANewRuleIsImmediate() {
        val (s, applied) = RuleChange.request(LayerState(), "a", base, nowMs = 0)
        assertEquals(RuleChange.Applied.Now, applied)
        assertEquals(listOf(base), s.limits)
    }

    @Test fun removingARuleWaits() {
        val (s, applied) = RuleChange.request(LayerState(limits = listOf(base)), "a", null, nowMs = 0)
        assertTrue(applied is RuleChange.Applied.Later)
        assertEquals(1, s.limits.size)
        val later = RuleChange.applyDue(s, RuleChange.LOOSEN_DELAY_MS)
        assertTrue(later.limits.isEmpty())
    }

    @Test fun aNewRequestReplacesThePendingOneAndCanBeCancelled() {
        var s = RuleChange.request(LayerState(limits = listOf(base)), "a", null, 0).first
        s = RuleChange.request(s, "a", base.copy(dailyLimitMs = 2 * HOUR), 1_000).first
        assertEquals(1, s.pendingRules.size)
        assertEquals(2 * HOUR, s.pendingRules.single().rule!!.dailyLimitMs)
        assertTrue(RuleChange.cancelPending(s, "a").pendingRules.isEmpty())
    }

    @Test fun makingTheRuleStricterAgainDropsThePendingLoosening() {
        var s = RuleChange.request(LayerState(limits = listOf(base)), "a", base.copy(dailyLimitMs = 2 * HOUR), 0).first
        assertEquals(1, s.pendingRules.size)
        s = RuleChange.request(s, "a", base.copy(dailyLimitMs = 10 * MIN), 10).first
        assertTrue(s.pendingRules.isEmpty())
        assertEquals(10 * MIN, s.limits.single().dailyLimitMs)
    }

    @Test fun applyDueIsANoOpWhenNothingIsDue() {
        val s = RuleChange.request(LayerState(limits = listOf(base)), "a", null, 0).first
        assertEquals(s, RuleChange.applyDue(s, 1_000))
    }
}

class BlockActionsTest {
    private val log = ArrayList<Triple<String, String, String>>()
    private fun setup(state: MemoryState = MemoryState()) = state to BlockActions(state, UtcDays, OWN) { t, x, p -> log += Triple(t, x, p) }

    @Test fun blockingUntilEndOfDayIsLoggedAndProtectedAppsAreRefused() {
        val (state, actions) = setup()
        assertTrue(actions.blockUntilEndOfDay(TIKTOK, BlockReason.MANUAL, 5 * HOUR))
        assertEquals(DAY, state.current.blocks.single().untilMs)
        assertEquals(1, log.size)
        assertFalse(actions.blockUntilEndOfDay("com.android.settings", BlockReason.MANUAL, 0))
        assertFalse(actions.blockUntilEndOfDay(OWN, BlockReason.BRAIN, 0))
        assertEquals(1, state.current.blocks.size)
    }

    @Test fun relaxingNeedsTheWaitThenAReasonThenGivesAShortPass() {
        val (state, actions) = setup()
        actions.requestRelax(TIKTOK, 1_000)
        assertTrue(actions.confirmRelax(TIKTOK, "motivo largo suficiente", 20_000) is RelaxPolicy.Result.TooEarly)
        assertEquals(RelaxPolicy.Result.NeedReason, actions.confirmRelax(TIKTOK, "ya", 62_000))
        val granted = actions.confirmRelax(TIKTOK, "  motivo largo suficiente  ", 62_000) as RelaxPolicy.Result.Granted
        assertEquals(62_000 + RelaxPolicy.EXTENSION_MS, granted.untilMs)
        assertEquals(granted.untilMs, state.current.passes[TIKTOK])
        assertEquals(1, state.current.relaxGranted)
        assertFalse("la petición se consume", TIKTOK in state.current.relaxRequests)
        assertTrue(log.single().second.contains("motivo largo suficiente"))
    }

    @Test fun confirmingWithoutAPriorRequestStillWaits() {
        val (_, actions) = setup()
        assertTrue(actions.confirmRelax(TIKTOK, "motivo largo suficiente", 5_000_000) is RelaxPolicy.Result.TooEarly)
    }

    @Test fun theDailyCapStopsTheFourthRelax() {
        val (_, actions) = setup()
        var t = 0L
        repeat(RelaxPolicy.MAX_PER_DAY) {
            actions.requestRelax(TIKTOK, t)
            assertTrue(actions.confirmRelax(TIKTOK, "motivo largo suficiente", t + 61_000) is RelaxPolicy.Result.Granted)
            t += 2 * MIN
        }
        actions.requestRelax(TIKTOK, t)
        assertEquals(RelaxPolicy.Result.LimitReached, actions.confirmRelax(TIKTOK, "motivo largo suficiente", t + 61_000))
    }

    @Test fun theDailyCapResetsTomorrow() {
        val (_, actions) = setup()
        repeat(RelaxPolicy.MAX_PER_DAY) { i ->
            actions.requestRelax(TIKTOK, i * 2 * MIN)
            actions.confirmRelax(TIKTOK, "motivo largo suficiente", i * 2 * MIN + 61_000)
        }
        actions.requestRelax(TIKTOK, DAY + MIN)
        assertTrue(actions.confirmRelax(TIKTOK, "motivo largo suficiente", DAY + 3 * MIN) is RelaxPolicy.Result.Granted)
    }

    @Test fun cancellingARequestForgetsIt() {
        val (state, actions) = setup()
        actions.requestRelax(TIKTOK, 0)
        actions.cancelRelax(TIKTOK)
        assertTrue(state.current.relaxRequests.isEmpty())
    }

    @Test fun activeBlocksHidesTheExpiredOnes() {
        val state = MemoryState(LayerState(blocks = listOf(BlockEntry("a", 5 * MIN, BlockReason.LIMIT), BlockEntry("b", 9 * MIN, BlockReason.LIMIT))))
        val (_, actions) = setup(state)
        assertEquals(listOf("b"), actions.activeBlocks(6 * MIN).map { it.pkg })
    }
}
