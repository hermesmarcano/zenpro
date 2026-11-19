package com.hermesmarcano.zen.layer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySettingTest {
    private val ours = "com.hermesmarcano.zen/com.hermesmarcano.zen.layer.capture.LayerAccessibilityService"

    @Test fun normalizeExpandsTheShortForm() {
        assertEquals(
            "com.hermesmarcano.zen/com.hermesmarcano.zen.layer.capture.LayerAccessibilityService",
            AccessibilitySetting.normalize("com.hermesmarcano.zen/.layer.capture.LayerAccessibilityService"),
        )
        assertEquals("a/b.C", AccessibilitySetting.normalize("  a/b.C "))
        assertEquals("sin-barra", AccessibilitySetting.normalize("sin-barra"))
    }

    @Test fun containsMatchesBothSpellings() {
        assertTrue(AccessibilitySetting.contains(ours, ours))
        assertTrue(AccessibilitySetting.contains("com.hermesmarcano.zen/.layer.capture.LayerAccessibilityService", ours))
        assertTrue(AccessibilitySetting.contains("x/y.Z:$ours:a/b.C", ours))
        assertFalse(AccessibilitySetting.contains("x/y.Z", ours))
        assertFalse(AccessibilitySetting.contains(null, ours))
        assertFalse(AccessibilitySetting.contains("", ours))
    }

    @Test fun withEnabledAddsOnceAndKeepsOthers() {
        assertEquals(ours, AccessibilitySetting.withEnabled(null, ours))
        assertEquals(ours, AccessibilitySetting.withEnabled("", ours))
        assertEquals("x/y.Z:$ours", AccessibilitySetting.withEnabled("x/y.Z", ours))
        assertEquals("x/y.Z:$ours", AccessibilitySetting.withEnabled("x/y.Z:$ours", ours)) // idempotente
    }

    @Test fun withoutRemovesOnlyOurs() {
        assertEquals("x/y.Z:a/b.C", AccessibilitySetting.without("x/y.Z:$ours:a/b.C", ours))
        assertEquals("", AccessibilitySetting.without(ours, ours))
        assertEquals("", AccessibilitySetting.without(null, ours))
        assertEquals("x/y.Z", AccessibilitySetting.without("x/y.Z", ours))
    }

    @Test fun strayColonsAndSpacesAreTolerated() {
        assertEquals("x/y.Z:$ours", AccessibilitySetting.withEnabled(":x/y.Z: :", ours))
    }
}

class SelfHealPolicyTest {
    private val now = 10 * 60 * 60_000L
    private fun decide(
        wanted: Boolean = true,
        listed: Boolean = true,
        connected: Boolean = false,
        canWrite: Boolean = true,
        downSince: Long? = null,
        repairs: List<Long> = emptyList(),
    ) = SelfHealPolicy.decide(wanted, listed, connected, canWrite, now, downSince, repairs)

    @Test fun healthyWhenListedAndConnected() = assertEquals(HealDecision.Healthy, decide(connected = true))

    @Test fun nothingToDoWhenNotWanted() = assertEquals(HealDecision.Healthy, decide(wanted = false, listed = false))

    @Test fun notListedIsEnabledWhenWeCanWrite() {
        assertEquals(HealDecision.Enable, decide(listed = false))
        assertEquals(HealDecision.NeedUser(HealDecision.Reason.NOT_ENABLED), decide(listed = false, canWrite = false))
    }

    @Test fun listedButDisconnectedWaitsThroughTheGracePeriod() {
        assertEquals(HealDecision.Wait(now), decide(downSince = null))
        assertEquals(HealDecision.Wait(now - 30_000), decide(downSince = now - 30_000))
        assertEquals(HealDecision.Wait(now), decide(downSince = now + 5_000)) // marca "del futuro"
    }

    @Test fun afterTheGraceItRestartsOrAsksTheUser() {
        val since = now - SelfHealPolicy.GRACE_MS
        assertEquals(HealDecision.Restart, decide(downSince = since))
        assertEquals(HealDecision.NeedUser(HealDecision.Reason.NOT_RESPONDING), decide(downSince = since, canWrite = false))
    }

    @Test fun repairsAreRateLimited() {
        val since = now - SelfHealPolicy.GRACE_MS
        // Una reparación hace 5 minutos: aún no.
        assertEquals(HealDecision.NeedUser(HealDecision.Reason.REPAIR_LIMIT), decide(downSince = since, repairs = listOf(now - 5 * 60_000)))
        // Hace 11 minutos: sí.
        assertEquals(HealDecision.Restart, decide(downSince = since, repairs = listOf(now - 11 * 60_000)))
        // Cuatro en seis horas: se acabó, aunque la última sea antigua.
        val four = listOf(1, 2, 3, 4).map { now - it * 60 * 60_000L }
        assertEquals(HealDecision.NeedUser(HealDecision.Reason.REPAIR_LIMIT), decide(downSince = since, repairs = four))
        assertEquals(HealDecision.NeedUser(HealDecision.Reason.REPAIR_LIMIT), decide(listed = false, repairs = four))
    }

    @Test fun oldRepairsFallOutOfTheWindow() {
        val old = listOf(1, 2, 3, 4).map { now - SelfHealPolicy.WINDOW_MS - it * 60_000L }
        assertTrue(SelfHealPolicy.repairsAllowed(now, old))
    }

    @Test fun futureRepairMarksDoNotBlock() {
        assertTrue(SelfHealPolicy.repairsAllowed(now, listOf(now + 1_000_000)))
    }

    @Test fun recordRepairKeepsAShortRecentHistory() {
        var history = emptyList<Long>()
        for (i in 0 until 20) history = SelfHealPolicy.recordRepair(history, now + i * 1_000L)
        assertTrue(history.size <= SelfHealPolicy.MAX_REPAIRS_IN_WINDOW * 2)
        assertEquals(now + 19_000L, history.last())
        // Las de hace más de la ventana se olvidan.
        val pruned = SelfHealPolicy.recordRepair(listOf(now - SelfHealPolicy.WINDOW_MS - 1), now)
        assertEquals(listOf(now), pruned)
    }
}

class LayerHealthTest {
    private val now = 100 * 60 * 60_000L
    private fun input(
        settings: CaptureSettings = CaptureSettings(),
        hasLimits: Boolean = false,
        accessibilityListed: Boolean = true,
        accessibilityConnected: Boolean = true,
        listenerEnabled: Boolean = true,
        listenerConnected: Boolean = true,
        mic: Boolean = true,
        notifications: Boolean = true,
        battery: Boolean = true,
        xiaomi: Boolean = false,
        autostart: Boolean = false,
        secure: Boolean = false,
        brain: Boolean = false,
        lastSync: Long? = null,
    ) = HealthInput(
        settings, hasLimits, accessibilityListed, accessibilityConnected, listenerEnabled, listenerConnected,
        mic, notifications, battery, xiaomi, autostart, secure, brain, lastSync, now,
    )

    private fun List<HealthItem>.byId(id: String) = filter { it.id == id }

    @Test fun nothingEnabledOnlyMentionsTheBrain() {
        val items = LayerHealth.evaluate(input())
        assertEquals(listOf("brain"), items.map { it.id })
        assertFalse(LayerHealth.hasBlocker(items))
    }

    @Test fun accessibilityIsRequiredByScreenTextOrByLimits() {
        for (i in listOf(input(CaptureSettings(screenText = true)), input(hasLimits = true))) {
            assertEquals(1, LayerHealth.evaluate(i).byId("accessibility").size)
        }
        assertTrue(LayerHealth.evaluate(input(CaptureSettings(notifications = true))).byId("accessibility").isEmpty())
    }

    @Test fun accessibilityOffIsABlocker() {
        val items = LayerHealth.evaluate(input(CaptureSettings(screenText = true), accessibilityListed = false, accessibilityConnected = false))
        val item = items.byId("accessibility").single()
        assertFalse(item.ok)
        assertEquals(Severity.BLOCKER, item.severity)
        assertTrue(LayerHealth.hasBlocker(items))
    }

    @Test fun listedButNotConnectedExplainsTheXiaomiCase() {
        val item = LayerHealth.evaluate(input(CaptureSettings(screenText = true), accessibilityConnected = false)).byId("accessibility").single()
        assertFalse(item.ok)
        assertTrue(item.title.contains("no responde"))
    }

    @Test fun notificationListenerStates() {
        val s = CaptureSettings(notifications = true)
        assertEquals(Severity.BLOCKER, LayerHealth.evaluate(input(s, listenerEnabled = false)).byId("listener").single().severity)
        val disconnected = LayerHealth.evaluate(input(s, listenerConnected = false)).byId("listener").single()
        assertFalse(disconnected.ok)
        assertEquals(Severity.WARNING, disconnected.severity)
        assertTrue(LayerHealth.evaluate(input(s)).byId("listener").single().ok)
    }

    @Test fun micNeedsThePermissionAndWarnsAboutHiddenNotifications() {
        val s = CaptureSettings(ambientAudio = true)
        val items = LayerHealth.evaluate(input(s, mic = false, notifications = false))
        assertFalse(items.byId("mic").single().ok)
        assertFalse(items.byId("post_notifications").single().ok)
        assertTrue(LayerHealth.evaluate(input(s)).byId("post_notifications").isEmpty())
    }

    @Test fun batteryAndAutostartOnlyMatterWhenSomethingIsOn() {
        assertTrue(LayerHealth.evaluate(input(xiaomi = true)).byId("battery").isEmpty())
        val items = LayerHealth.evaluate(input(CaptureSettings(notifications = true), battery = false, xiaomi = true))
        assertFalse(items.byId("battery").single().ok)
        assertFalse(items.byId("autostart").single().ok)
        assertTrue(LayerHealth.evaluate(input(CaptureSettings(notifications = true), xiaomi = false)).byId("autostart").isEmpty())
        assertTrue(LayerHealth.evaluate(input(CaptureSettings(notifications = true), xiaomi = true, autostart = true)).byId("autostart").single().ok)
    }

    @Test fun secureSettingsIsOnlyInformational() {
        val item = LayerHealth.evaluate(input(CaptureSettings(screenText = true))).byId("secure_settings").single()
        assertFalse(item.ok)
        assertEquals(Severity.INFO, item.severity)
    }

    @Test fun brainSyncFreshness() {
        assertTrue(LayerHealth.evaluate(input(brain = true, lastSync = now - 60_000)).byId("brain").single().ok)
        assertFalse(LayerHealth.evaluate(input(brain = true, lastSync = now - LayerHealth.STALE_SYNC_MS - 1)).byId("brain").single().ok)
        assertFalse(LayerHealth.evaluate(input(brain = true, lastSync = null)).byId("brain").single().ok)
    }

    @Test fun warningsAloneAreNotBlockers() {
        val items = LayerHealth.evaluate(input(CaptureSettings(notifications = true), battery = false, xiaomi = true, brain = true))
        assertFalse(LayerHealth.hasBlocker(items))
    }
}

class LayerEventTest {
    @Test fun clampDoesNotSplitSurrogatePairs() {
        val text = "ab😀cd" // 😀 ocupa dos unidades
        assertEquals("ab", LayerEvent.clamp(text, 3))
        assertEquals("ab😀", LayerEvent.clamp(text, 4))
        assertEquals(text, LayerEvent.clamp(text, 100))
        assertEquals("", LayerEvent.clamp("😀", 1))
    }
}
