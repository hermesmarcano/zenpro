package com.hermesmarcano.zen.layer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRulesTest {
    @Test fun inputIsTrimmedAndStrippedOfControlCharacters() {
        assertEquals("hola mundo", ChatRules.cleanInput("  hola\u0000 mundo\u0007  "))
        assertEquals("a\nb", ChatRules.cleanInput("a\nb"))
    }

    @Test fun emptyOrTooLongInputIsRefused() {
        assertNull(ChatRules.cleanInput("   \n "))
        assertNull(ChatRules.cleanInput("\u0000\u0001"))
        assertEquals(ChatRules.MAX_TEXT_CHARS, ChatRules.cleanInput("x".repeat(ChatRules.MAX_TEXT_CHARS))?.length)
        assertNull(ChatRules.cleanInput("x".repeat(ChatRules.MAX_TEXT_CHARS + 1)))
    }

    @Test fun onlyThreeWordsOrMoreCountAsAConversation() {
        assertFalse(ChatRules.qualifies("hola"))
        assertFalse(ChatRules.qualifies("  ok  gracias "))
        assertFalse(ChatRules.qualifies(""))
        assertTrue(ChatRules.qualifies("agéndame la cena"))
        assertTrue(ChatRules.qualifies("uno\n dos\ttres"))
    }

    @Test fun theModelTextCannotDisguiseItselfWithDirectionControls() {
        val tricky = "pago‮3 000‬ seguro⁦ hoy⁩‏"
        assertEquals("pago3 000 seguro hoy", ChatRules.cleanReply(tricky))
        assertEquals("a\nb", ChatRules.cleanReply("\u0000a\nb\u0007 "))
    }

    @Test fun invisibleDisguisesAreRemovedButEmojiJoinersStay() {
        val tags = "a" + String(Character.toChars(0xE0041)) + "b" // «etiqueta» U+E0041
        assertEquals("ab", ChatRules.cleanReply(tags))
        assertEquals("ab", ChatRules.cleanReply("a\u200Bb\u2060\uFEFF\u061C"))
        assertEquals("👩\u200D💻", ChatRules.cleanReply("👩\u200D💻")) // ZWJ de un emoji compuesto: se conserva
    }

    @Test fun aLongReplyIsCut() {
        assertEquals(ChatRules.MAX_REPLY_CHARS, ChatRules.cleanReply("y".repeat(ChatRules.MAX_REPLY_CHARS * 2)).length)
    }

    @Test fun onlyCalendarBlockAndPauseAreChatCommands() {
        assertTrue(ChatRules.isChatCommand(BrainCommand.CalendarEvent("1", "t", 1, 2, "chat")))
        assertTrue(ChatRules.isChatCommand(BrainCommand.Block("2", "a.b", 5)))
        assertTrue(ChatRules.isChatCommand(BrainCommand.Pause("3", 5)))
        assertFalse(ChatRules.isChatCommand(BrainCommand.Notify("4", "t", "x")))
        assertFalse(ChatRules.isChatCommand(BrainCommand.DraftReply("5", "c", "x")))
    }

    @Test fun historyKeepsTheNewestMessages() {
        val all = (1..150).map { ChatMessage("m$it", ChatRole.USER, "x", it.toLong()) }
        val kept = ChatRules.trim(all)
        assertEquals(ChatRules.MAX_HISTORY, kept.size)
        assertEquals("m150", kept.last().id)
        assertEquals("m51", kept.first().id)
    }

    @Test fun voiceLengthIsMeasuredInMilliseconds() {
        assertEquals(1_000L, ChatRules.voiceMs(16_000))
        assertEquals(500L, ChatRules.voiceMs(8_000))
    }

    @Test fun adapterErrorsBecomeHonestReasons() {
        fun f(code: Int, error: String = "") = ChatFailure.fromHttp(code, error)
        assertEquals(ChatFailure.UNAUTHORIZED, f(401))
        assertEquals(ChatFailure.UNAUTHORIZED, f(403))
        assertEquals(ChatFailure.CHAT_OFF, f(404, "chat_off"))
        assertEquals(ChatFailure.REJECTED, f(404))
        assertEquals(ChatFailure.NO_SPEECH, f(422, "no_speech"))
        assertEquals(ChatFailure.STT_OFF, f(422, "stt_off"))
        assertEquals(ChatFailure.STT_FAILED, f(422, "stt_failed"))
        assertEquals(ChatFailure.REJECTED, f(422, "not_wav"))
        assertEquals(ChatFailure.RATE_LIMITED, f(429, "rate_limited"))
        assertEquals(ChatFailure.BUSY, f(503, "busy"))
        assertEquals(ChatFailure.COOLDOWN, f(503, "cooldown"))
        assertEquals(ChatFailure.SERVER_ERROR, f(503, "audit_unavailable"))
        assertEquals(ChatFailure.SERVER_ERROR, f(500, "internal_error"))
        assertEquals(ChatFailure.TIMEOUT, f(504, "brain_timeout"))
        assertEquals(ChatFailure.BAD_REPLY, f(502, "bad_reply"))
        assertEquals(ChatFailure.UNAVAILABLE, f(502, "brain_unavailable"))
        assertEquals(ChatFailure.UNAVAILABLE, f(408))
        assertEquals(ChatFailure.REJECTED, f(413))
    }

    @Test fun onlyTransientReasonsOfferARetry() {
        assertTrue(ChatFailure.TIMEOUT.retryable && ChatFailure.BUSY.retryable && ChatFailure.UNAVAILABLE.retryable)
        assertFalse(ChatFailure.UNAUTHORIZED.retryable || ChatFailure.REJECTED.retryable || ChatFailure.NO_SPEECH.retryable)
        assertTrue(ChatFailure.SERVER_ERROR.retryable)
        assertFalse(ChatFailure.COOLDOWN.retryable) // reintentar enseguida solo recibiría otro 503
    }
}
