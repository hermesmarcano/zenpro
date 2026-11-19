package com.hermesmarcano.zen.layer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleFormTest {
    private val own = "com.hermesmarcano.zen"

    private fun build(
        pkg: String = "com.tiktok", label: String = "TikTok", minutes: String = "45", block: Boolean = false,
        windowOn: Boolean = false, from: String = "22", to: String = "7",
    ) = RuleForm.build(pkg, label, minutes, block, windowOn, from, to, own)

    private fun error(r: RuleForm.Result) = (r as RuleForm.Result.Error).message
    private fun rule(r: RuleForm.Result) = (r as RuleForm.Result.Ok).rule

    @Test fun aMinutesLimitOnly() {
        val r = rule(build())
        assertEquals(LimitRule("com.tiktok", "TikTok", 45 * 60_000L, false, null), r)
    }

    @Test fun aWindowOnlyWrapsMidnight() {
        val r = rule(build(minutes = "", windowOn = true))
        assertEquals(0L, r.dailyLimitMs)
        assertEquals(DayWindow(22 * 60, 7 * 60), r.window)
    }

    @Test fun both() {
        val r = rule(build(minutes = "30", block = true, windowOn = true, from = "9", to = "17"))
        assertEquals(DayWindow(9 * 60, 17 * 60), r.window)
        assertTrue(r.blockAtLimit)
    }

    @Test fun anAppIsRequired() = assertEquals("Elige una app.", error(build(pkg = " ")))

    @Test fun protectedAppsAreRefused() {
        for (p in listOf("com.android.settings", own, "com.android.phone")) {
            assertEquals("Esa app está protegida y no se puede limitar.", error(build(pkg = p)))
        }
    }

    @Test fun nothingToEnforceIsRefused() {
        assertEquals("Pon un límite de minutos o una franja prohibida.", error(build(minutes = "")))
        assertEquals("Pon un límite de minutos o una franja prohibida.", error(build(minutes = "0")))
    }

    @Test fun aDayHasOnlyAsManyMinutesAsItHas() {
        assertEquals(RuleForm.MAX_MINUTES * 60_000L, rule(build(minutes = "1440")).dailyLimitMs)
        assertEquals("Un día tiene 1440 minutos.", error(build(minutes = "1441")))
    }

    @Test fun windowHoursMustBeDifferentAndInRange() {
        val message = "La franja necesita dos horas distintas entre 0 y 23."
        assertEquals(message, error(build(windowOn = true, from = "22", to = "22")))
        assertEquals(message, error(build(windowOn = true, from = "24", to = "3")))
        assertEquals(message, error(build(windowOn = true, from = "", to = "3")))
        assertEquals(message, error(build(windowOn = true, from = "-1", to = "3")))
        assertEquals(message, error(build(windowOn = true, from = "x", to = "3")))
    }

    @Test fun anInvalidWindowIsIgnoredWhenItIsSwitchedOff() {
        assertNull(rule(build(windowOn = false, from = "99", to = "99")).window)
    }

    @Test fun blockingAtTheLimitMeansNothingWithoutALimit() {
        val r = rule(build(minutes = "", block = true, windowOn = true))
        assertTrue("sin minutos no se guarda un bloqueo al llegar al límite", !r.blockAtLimit)
    }

    @Test fun aBlankLabelFallsBackToThePackage() = assertEquals("com.tiktok", rule(build(label = " ")).label)

    @Test fun spacesAroundNumbersAreTolerated() {
        assertEquals(10 * 60_000L, rule(build(minutes = " 10 ")).dailyLimitMs)
    }
}
