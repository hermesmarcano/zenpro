package com.hermesmarcano.zen.layer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private fun r(text: String) = Redactor.redact(text)

    @Test fun otpAfterKeywordIsHidden() {
        assertEquals("Tu código de WhatsApp es ${Redactor.OTP}", r("Tu código de WhatsApp es 123-456"))
        assertFalse(r("G-123456 is your Google verification code").contains("123456"))
        assertFalse(r("Your OTP is 8842").contains("8842"))
    }

    @Test fun otpOnTheLineAfterTheKeyword() {
        val out = r("Código de verificación:\n482913")
        assertFalse(out.contains("482913"))
        assertTrue(out.contains(Redactor.OTP))
    }

    @Test fun numbersWithoutAKeywordAreKept() {
        val text = "Nos vemos a las 1830 en la calle 4567"
        assertEquals(text, r(text))
    }

    @Test fun shortKeywordsNeedWholeWords() {
        // "pin" dentro de "shopping" o "pinterest" no es un código.
        val text = "Going shopping on Pinterest 12345"
        assertEquals(text, r(text))
    }

    @Test fun longDigitRunsAreNotTreatedAsCodes() {
        val text = "código 04121234567"
        assertEquals(text, r(text))
    }

    @Test fun luhnValidCardsAreHidden() {
        assertEquals("Pago con ${Redactor.CARD} ok", r("Pago con 4111 1111 1111 1111 ok"))
        assertEquals(Redactor.CARD, r("4111-1111-1111-1111"))
        assertEquals(Redactor.CARD, r("4111111111111111"))
    }

    @Test fun sixteenDigitsThatFailLuhnAreKept() {
        val text = "ref 1234 5678 9012 3456"
        assertEquals(text, r(text))
    }

    @Test fun eighteenDigitAccountsAreHidden() {
        assertEquals("CLABE ${Redactor.ACCOUNT}", r("CLABE 012345678901234567"))
    }

    @Test fun labelledSecretsKeepTheLabel() {
        assertEquals("contraseña: ${Redactor.SECRET}", r("contraseña: hunter2"))
        assertEquals("password=${Redactor.SECRET}", r("password=abc123"))
        assertEquals("cvv: ${Redactor.SECRET}", r("cvv: 123"))
    }

    @Test fun knownTokenShapesAreHidden() {
        assertEquals(Redactor.SECRET, r("sk-abcdefghijklmnopqrstuv"))
        assertEquals("k=${Redactor.SECRET}", r("k=ghp_abcdefghijklmnopqrstuvwxyz"))
        assertEquals(Redactor.SECRET, r("AKIAABCDEFGHIJKLMNOP"))
        assertEquals(Redactor.SECRET, r("eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0.SflKxwRJSMeKKF2QT4"))
    }

    @Test fun countsWhatItHides() {
        val result = Redactor.redactCounting("código 1234 y tarjeta 4111 1111 1111 1111")
        assertEquals(2, result.count)
    }

    @Test fun isIdempotent() {
        val samples = listOf(
            "Tu código es 482913, contraseña: abc, 4111 1111 1111 1111",
            "password: x,\nclave=1234",
            "sin nada que ocultar",
            "",
        )
        for (s in samples) assertEquals(r(s), r(r(s)))
    }

    @Test fun pairRedactionCatchesACodeSplitBetweenTitleAndBody() {
        val (title, body) = Redactor.redactPair("Código de verificación", "482913")
        assertEquals("Código de verificación", title)
        assertEquals(Redactor.OTP, body)
        val (t2, b2) = Redactor.redactPair("Marta", "nos vemos a las 1830")
        assertEquals("Marta" to "nos vemos a las 1830", t2 to b2)
        assertEquals("" to "solo cuerpo", Redactor.redactPair("", "solo cuerpo"))
        assertEquals("solo título" to "", Redactor.redactPair("solo título", ""))
        // Un salto de línea en el título no desplaza el reparto.
        val (t3, b3) = Redactor.redactPair("Código\nde acceso", "123456")
        assertFalse(t3.contains('\n'))
        assertEquals(Redactor.OTP, b3)
    }

    @Test fun emptyAndPlainTextPassThrough() {
        assertEquals("", r(""))
        assertEquals("hola mundo", r("hola mundo"))
    }
}

class CapturePolicyTest {
    private val own = "com.hermesmarcano.zen"
    private val on = CaptureSettings(screenText = true, notifications = true, ambientAudio = true)
    private fun decide(s: CaptureSettings, src: Source, pkg: String, now: Long = 1_000L) = CapturePolicy.decide(s, src, pkg, own, now)

    @Test fun everythingStartsOff() {
        assertEquals(CaptureDecision.SOURCE_OFF, decide(CaptureSettings(), Source.SCREEN, "com.whatsapp"))
        assertEquals(CaptureDecision.SOURCE_OFF, decide(CaptureSettings(), Source.AUDIO, ""))
    }

    @Test fun pauseWinsOverEverythingAndEndsOnTime() {
        val paused = on.copy(pausedUntilMs = 5_000L)
        assertEquals(CaptureDecision.PAUSED, decide(paused, Source.SCREEN, "com.whatsapp", now = 4_999L))
        assertEquals(CaptureDecision.PAUSED, decide(paused, Source.AUDIO, "", now = 4_999L))
        assertEquals(CaptureDecision.ALLOWED, decide(paused, Source.SCREEN, "com.whatsapp", now = 5_000L))
    }

    @Test fun ownAppIsNeverCaptured() {
        assertEquals(CaptureDecision.OWN_APP, decide(on, Source.SCREEN, own))
        assertEquals(CaptureDecision.OWN_APP, decide(on, Source.NOTIFICATIONS, own))
    }

    @Test fun audioIgnoresThePackage() {
        assertEquals(CaptureDecision.ALLOWED, decide(on, Source.AUDIO, ""))
        assertEquals(CaptureDecision.ALLOWED, decide(on.copy(denyPackages = setOf("")), Source.AUDIO, ""))
    }

    @Test fun noiseAppsAreSkippedOnScreenOnly() {
        assertEquals(CaptureDecision.NOISE, decide(on, Source.SCREEN, "com.android.systemui"))
        assertEquals(CaptureDecision.ALLOWED, decide(on, Source.NOTIFICATIONS, "com.android.systemui"))
    }

    @Test fun sensitiveAppsAreBlockedByNameAndByHeuristic() {
        assertEquals(CaptureDecision.SENSITIVE, decide(on, Source.SCREEN, "com.android.settings"))
        assertEquals(CaptureDecision.SENSITIVE, decide(on, Source.SCREEN, "com.x8bit.bitwarden"))
        assertEquals(CaptureDecision.SENSITIVE, decide(on, Source.SCREEN, "com.bbva.mx"))
        assertEquals(CaptureDecision.SENSITIVE, decide(on, Source.NOTIFICATIONS, "com.example.MyBankApp"))
        assertEquals(CaptureDecision.ALLOWED, decide(on, Source.SCREEN, "com.whatsapp"))
        assertEquals(CaptureDecision.ALLOWED, decide(on, Source.SCREEN, "com.zhiliaoapp.musically"))
    }

    @Test fun userDenyListApplies() {
        val s = on.copy(denyPackages = setOf("com.zhiliaoapp.musically"))
        assertEquals(CaptureDecision.DENIED, decide(s, Source.SCREEN, "com.zhiliaoapp.musically"))
        assertEquals(CaptureDecision.ALLOWED, decide(s, Source.SCREEN, "com.whatsapp"))
    }

    @Test fun onlyAllowedIsTrue() {
        assertTrue(CaptureDecision.ALLOWED.allowed)
        assertTrue(CaptureDecision.entries.filter { it != CaptureDecision.ALLOWED }.none { it.allowed })
    }

    @Test fun neverBlockProtectsSettingsPhoneAndSelf() {
        for (p in listOf(own, "com.android.settings", "com.android.phone", "com.google.android.dialer", "com.miui.home", "android", "")) {
            assertTrue("$p debería estar protegida", NeverBlock.isProtected(p, own))
        }
        assertFalse(NeverBlock.isProtected("com.zhiliaoapp.musically", own))
    }
}

class TextDeduperTest {
    @Test fun onlyNewLinesComeOut() {
        val d = TextDeduper(minIntervalMs = 0)
        assertEquals(listOf("hola Marta", "¿cenamos hoy?"), d.fresh("wa", listOf("hola Marta", "¿cenamos hoy?"), 1_000))
        assertEquals(listOf("sí, a las 9"), d.fresh("wa", listOf("hola Marta", "¿cenamos hoy?", "sí, a las 9"), 2_000))
        assertTrue(d.fresh("wa", listOf("hola Marta", "sí, a las 9"), 3_000).isEmpty())
    }

    @Test fun throttledCallsDoNotMarkLinesAsSeen() {
        val d = TextDeduper(minIntervalMs = 1_500)
        assertEquals(listOf("primera"), d.fresh("a", listOf("primera"), 1_000))
        // Demasiado pronto: se descarta, pero "segunda" sigue siendo nueva.
        assertTrue(d.fresh("a", listOf("segunda"), 1_500).isEmpty())
        assertEquals(listOf("segunda"), d.fresh("a", listOf("segunda"), 2_600))
    }

    @Test fun forceSkipsTheThrottle() {
        val d = TextDeduper(minIntervalMs = 10_000)
        d.fresh("a", listOf("uno"), 1_000)
        assertEquals(listOf("dos"), d.fresh("a", listOf("dos"), 1_100, force = true))
    }

    @Test fun packagesAreIndependent() {
        val d = TextDeduper(minIntervalMs = 0)
        d.fresh("a", listOf("misma línea"), 1)
        assertEquals(listOf("misma línea"), d.fresh("b", listOf("misma línea"), 2))
    }

    @Test fun clockGoingBackwardsDoesNotFreezeCapture() {
        val d = TextDeduper(minIntervalMs = 5_000)
        d.fresh("a", listOf("uno"), 100_000)
        assertEquals(listOf("dos"), d.fresh("a", listOf("dos"), 10))
    }

    @Test fun interfaceJunkIsDropped() {
        val d = TextDeduper(minIntervalMs = 0)
        assertEquals(listOf("mensaje real"), d.fresh("a", listOf("12:45", "•••", "ab", "mensaje real", "   "), 1))
    }

    @Test fun whitespaceIsNormalized() {
        val d = TextDeduper(minIntervalMs = 0)
        d.fresh("a", listOf("hola    mundo"), 1)
        assertTrue(d.fresh("a", listOf("  hola mundo "), 2).isEmpty())
    }

    @Test fun oldLinesAreForgottenWhenTheCapIsReached() {
        val d = TextDeduper(minIntervalMs = 0, maxLinesPerPackage = 3)
        d.fresh("a", listOf("línea uno", "línea dos", "línea tres", "línea cuatro"), 1)
        assertEquals(listOf("línea uno"), d.fresh("a", listOf("línea uno"), 2))
    }

    @Test fun forgetAndClearReset() {
        val d = TextDeduper(minIntervalMs = 0)
        d.fresh("a", listOf("algo largo"), 1)
        d.forget("a")
        assertEquals(listOf("algo largo"), d.fresh("a", listOf("algo largo"), 2))
        d.clear()
        assertEquals(listOf("algo largo"), d.fresh("a", listOf("algo largo"), 3))
    }
}
