package com.hermesmarcano.zen.layer.core

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecretBoxTest {
    private val key = SoftwareKeys.random()
    private val box = AesGcmSecretBox { key }

    @Test fun roundTripsAndNeverContainsThePlainText() {
        val sealed = box.seal("tok-secreto-123", "ctx")
        assertTrue(SealedText.isSealed(sealed))
        assertFalse(sealed.contains("secreto"))
        assertEquals("tok-secreto-123", box.open(sealed, "ctx"))
    }

    @Test fun everyMessageGetsItsOwnIv() {
        assertNotEquals(box.seal("igual", "ctx"), box.seal("igual", "ctx"))
    }

    @Test fun aValueSealedForOneUseDoesNotOpenForAnother() {
        assertNull(box.open(box.seal("x", "layer.brainToken"), "outbox"))
    }

    @Test fun tamperingTruncationAndGarbageAreRejectedNotThrown() {
        val sealed = box.seal("x", "ctx")
        val flipped = sealed.dropLast(2) + if (sealed.last() == 'A') "BB" else "AA"
        for (bad in listOf(flipped, sealed.take(10), "enc1:", "enc1:@@@no-base64@@@", "plano", "")) {
            assertNull(bad, box.open(bad, "ctx"))
        }
    }

    @Test fun anotherKeyCannotOpenIt() {
        val other = AesGcmSecretBox(SoftwareKeys.random().let { k -> { k } })
        assertNull(other.open(box.seal("x", "ctx"), "ctx"))
    }

    @Test fun aBrokenKeystoreFailsClosedWithAnIoException() {
        val broken = AesGcmSecretBox { throw IllegalStateException("Keystore roto") }
        try {
            broken.seal("x", "ctx")
            fail("no debe guardar en claro")
        } catch (_: IOException) {
        }
        assertNull(broken.open("enc1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "ctx"))
    }
}

class RateWindowTest {
    @Test fun allowsTheLimitThenRefusesUntilTheWindowPasses() {
        val w = RateWindow(2, 1_000)
        assertTrue(w.tryAcquire(0))
        assertTrue(w.tryAcquire(10))
        assertFalse(w.tryAcquire(20))
        assertFalse(w.tryAcquire(999))
        assertTrue(w.tryAcquire(1_000)) // el primero ya salió de la ventana
        assertFalse(w.tryAcquire(1_005))
    }

    @Test fun aClockGoingBackwardsDoesNotKeepTheWindowOpen() {
        val w = RateWindow(1, 1_000)
        assertTrue(w.tryAcquire(5_000))
        assertTrue("el reloj retrocedió: se reinicia en vez de abrir de más", w.tryAcquire(100))
        assertFalse(w.tryAcquire(150))
    }
}

class BrainCommandHardeningTest {
    private val own = "com.hermesmarcano.zen.xiaomi"
    private fun check(c: BrainCommand, blockable: Set<String> = emptySet()) = CommandPolicy.check(c, own, emptySet(), blockable)

    @Test fun theBrainBlocksNothingByDefault() {
        assertEquals(CommandPolicy.Verdict.NOT_ALLOWED, check(BrainCommand.Block("1", "com.zhiliaoapp.musically", 60)))
    }

    @Test fun onlyAppsTheUserPutUnderARuleCanBeBlocked() {
        val allowed = setOf("com.zhiliaoapp.musically")
        assertEquals(CommandPolicy.Verdict.ACCEPT, check(BrainCommand.Block("1", "com.zhiliaoapp.musically", 60), allowed))
        assertEquals(CommandPolicy.Verdict.NOT_ALLOWED, check(BrainCommand.Block("2", "com.instagram.android", 60), allowed))
    }

    @Test fun protectedAppsStayProtectedEvenIfSomeoneListedThem() {
        val listed = setOf("com.android.settings", own, "com.android.dialer")
        for (pkg in listed) assertEquals(pkg, CommandPolicy.Verdict.PROTECTED, check(BrainCommand.Block("1", pkg, 5), listed))
    }

    @Test fun noticesAreShortAndWithoutLinks() {
        assertEquals(CommandPolicy.Verdict.ACCEPT, check(BrainCommand.Notify("1", "ZEN", "Llevas 1 h en TikTok")))
        assertEquals(CommandPolicy.Verdict.ACCEPT, check(BrainCommand.Notify("2", "ZEN", "x".repeat(CommandPolicy.MAX_NOTIFY_CHARS))))
        assertEquals(CommandPolicy.Verdict.INVALID, check(BrainCommand.Notify("3", "ZEN", "x".repeat(CommandPolicy.MAX_NOTIFY_CHARS + 1))))
        assertEquals(CommandPolicy.Verdict.INVALID, check(BrainCommand.Notify("4", "t".repeat(CommandPolicy.MAX_NOTIFY_TITLE_CHARS + 1), "ok")))
        val hostile = listOf(
            "mira http://malo.com/x", "entra a www.malo.net", "ve a malo.com", "mailto:a@b.c", "tel:+5255", "javascript:alert(1)", "intent:#Intent;end",
            "evil.ru/pay", "192.168.1.5/login", "bank-login.de", "goo.gl/abc", "youtu.be/abc", "evil\uff0ecom", "evil[.]com", "evil(.)com", "EVIL.COM",
        )
        for (text in hostile) {
            assertEquals(text, CommandPolicy.Verdict.LINK, check(BrainCommand.Notify("5", "ZEN", text)))
        }
        assertEquals(CommandPolicy.Verdict.LINK, check(BrainCommand.Notify("6", "ver malo.com", "texto")))
        assertEquals(CommandPolicy.Verdict.ACCEPT, check(BrainCommand.Notify("7", "ZEN", "Llevas 1 hora y 15 minutos en TikTok")))
    }
}

class CommandRateLimitTest {
    private var now = 10 * HOUR
    private val state = MemoryState(LayerState(limits = listOf(LimitRule("com.zhiliaoapp.musically", "TikTok", 0L))))
    private val notifier = RecordingNotifier()
    private val pipeline = LayerPipeline(state, MemoryTimeline(), MemoryOutbox(), "own", { "{}" }, { now })
    private val applier = CommandApplier(state, BlockActions(state, UtcDays, "own"), DraftController(state, FakeSender(), pipeline, { now }), notifier, "own", { now })

    @Test fun aFloodOfNoticesIsCutAtSixPerHour() {
        val outcomes = (1..8).map { applier.apply(BrainCommand.Notify("n$it", "ZEN", "aviso $it")) }
        assertEquals(6, outcomes.count { it == CommandApplier.Outcome.APPLIED })
        assertEquals(6, notifier.notices.size)
        now += HOUR + 1
        assertEquals(CommandApplier.Outcome.APPLIED, applier.apply(BrainCommand.Notify("n9", "ZEN", "otra hora")))
    }

    @Test fun aBurstOfBrainBlocksIsCutAtThreePerHour() {
        val outcomes = (1..5).map { applier.apply(BrainCommand.Block("b$it", "com.zhiliaoapp.musically", 10)) }
        assertEquals(3, outcomes.count { it == CommandApplier.Outcome.APPLIED })
        assertEquals(3, notifier.blocked.size)
    }

    @Test fun blockingAnAppWithoutARuleIsRejectedAndNothingIsBlocked() {
        assertEquals(CommandApplier.Outcome.REJECTED, applier.apply(BrainCommand.Block("b1", "com.instagram.android", 10)))
        assertTrue(state.current.blocks.isEmpty())
    }
}

class SensitiveHealthAppsTest {
    @Test fun healthAndMedicalAppsAreNeverCaptured() {
        for (pkg in listOf("com.google.android.apps.healthdata", "mx.gob.salud.citas", "com.clinica.portal", "es.hospital.paciente", "com.farmacia.recetas", "com.pharmacy.app", "com.telemedicina.app")) {
            assertTrue(pkg, SensitiveApps.isSensitive(pkg))
        }
        assertFalse(SensitiveApps.isSensitive("com.whatsapp"))
    }
}

class VoiceCapTest {
    @Test fun aSegmentNeverExceedsWhatTheServerAccepts() {
        val loud = ShortArray(16_000) { if (it % 2 == 0) 8_000 else -8_000 }
        val s = SpeechSegmenter()
        val segments = ArrayList<PcmSegment>()
        repeat(70) { segments += s.feed(loud) } // 70 s de ruido seguido
        s.flush()?.let { segments += it }
        assertTrue(segments.size >= 2)
        assertTrue(segments.all { it.durationMs <= 30_000 })
        assertEquals(MAX_SEGMENT_MS.toLong(), segments.first().durationMs)
    }
}

class OutboxRetentionTest {
    @Test fun whatWaitsOnThePhoneIsKeptForADayAtMost() {
        assertEquals(24 * 60 * 60 * 1000L, OutboxPolicy.MAX_AGE_MS)
        assertEquals(500, OutboxPolicy.MAX_ITEMS)
    }
}
