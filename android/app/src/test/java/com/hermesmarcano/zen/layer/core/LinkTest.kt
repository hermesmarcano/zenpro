package com.hermesmarcano.zen.layer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackoffTest {
    @Test fun growsExponentiallyThenCaps() {
        assertEquals(30_000L, Backoff.delayMs(1, 0.5))
        assertEquals(60_000L, Backoff.delayMs(2, 0.5))
        assertEquals(120_000L, Backoff.delayMs(3, 0.5))
        assertEquals(Backoff.MAX_MS, Backoff.delayMs(30, 0.999))
        var previous = 0L
        for (attempt in 1..20) {
            val d = Backoff.delayMs(attempt, 0.5)
            assertTrue(d >= previous)
            previous = d
        }
    }

    @Test fun jitterStaysWithinAQuarter() {
        assertEquals(22_500L, Backoff.delayMs(1, 0.0))
        assertTrue(Backoff.delayMs(1, 0.999999) <= 37_500L)
        // Valores de azar fuera de rango no rompen nada.
        assertEquals(22_500L, Backoff.delayMs(1, -5.0))
        assertTrue(Backoff.delayMs(1, 9.0) <= 37_500L)
    }

    @Test fun zeroOrNegativeAttemptsAreTreatedAsTheFirst() {
        assertEquals(Backoff.delayMs(1, 0.5), Backoff.delayMs(0, 0.5))
        assertEquals(Backoff.delayMs(1, 0.5), Backoff.delayMs(-3, 0.5))
    }
}

class OutboxPolicyTest {
    private fun ev(id: String, at: Long, notBefore: Long = 0, kind: OutboxItem.Kind = OutboxItem.Kind.EVENT) =
        OutboxItem(id, kind, "{}", at, notBeforeMs = notBefore)

    @Test fun dueFiltersByKindAndBackoffAndOrdersByAge() {
        val items = listOf(
            ev("c", 30), ev("a", 10), ev("b", 20),
            ev("later", 5, notBefore = 1_000),
            ev("audio", 1, kind = OutboxItem.Kind.AUDIO),
        )
        assertEquals(listOf("a", "b"), OutboxPolicy.due(items, OutboxItem.Kind.EVENT, nowMs = 100, limit = 2).map { it.id })
        assertEquals(listOf("audio"), OutboxPolicy.due(items, OutboxItem.Kind.AUDIO, 100, 5).map { it.id })
        assertTrue(OutboxPolicy.due(items, OutboxItem.Kind.EVENT, 100, 10).none { it.id == "later" })
        assertEquals(4, OutboxPolicy.due(items, OutboxItem.Kind.EVENT, 2_000, 10).size)
    }

    @Test fun failureCountsAttemptsAndPostponesTheItem() {
        val failed = OutboxPolicy.afterFailure(ev("a", 0), nowMs = 1_000, random = 0.5)
        assertEquals(1, failed.attempts)
        assertEquals(1_000 + 30_000L, failed.notBeforeMs)
        val again = OutboxPolicy.afterFailure(failed, nowMs = 40_000, random = 0.5)
        assertEquals(2, again.attempts)
        assertEquals(40_000 + 60_000L, again.notBeforeMs)
    }

    @Test fun networkFailuresNeverExhaustAnItem() {
        var item = ev("a", 0)
        repeat(200) { item = OutboxPolicy.afterFailure(item, nowMs = it * 1_000L, random = 0.5) }
        assertEquals(200, item.attempts)
        assertEquals(0, item.rejections)
        assertFalse(OutboxPolicy.isExhausted(item))
    }

    @Test fun repeatedServerRejectionsExhaustAnItem() {
        var item = ev("a", 0)
        repeat(OutboxPolicy.MAX_REJECTIONS - 1) { item = OutboxPolicy.afterFailure(item, 0, 0.5, rejected = true) }
        assertFalse(OutboxPolicy.isExhausted(item))
        item = OutboxPolicy.afterFailure(item, 0, 0.5, rejected = true)
        assertTrue(OutboxPolicy.isExhausted(item))
    }

    @Test fun successRemovesOnlyTheSentItems() {
        val left = OutboxPolicy.afterSuccess(listOf(ev("a", 1), ev("b", 2), ev("c", 3)), setOf("a", "c"))
        assertEquals(listOf("b"), left.map { it.id })
    }

    @Test fun pruneDropsExpiredItems() {
        val now = OutboxPolicy.MAX_AGE_MS + 10_000
        val (kept, dropped) = OutboxPolicy.prune(listOf(ev("old", 0), ev("new", now - 1_000)), now)
        assertEquals(listOf("new"), kept.map { it.id })
        assertEquals(listOf("old"), dropped.map { it.id })
    }

    @Test fun pruneDropsTheOldestWhenOverCapacity() {
        val items = (0 until OutboxPolicy.MAX_ITEMS + 3).map { ev("e$it", it.toLong()) }
        val (kept, dropped) = OutboxPolicy.prune(items, nowMs = 10_000)
        assertEquals(OutboxPolicy.MAX_ITEMS, kept.size)
        assertEquals(listOf("e0", "e1", "e2"), dropped.map { it.id })
        assertFalse(kept.any { it.id == "e0" })
    }
}

class BrainUrlTest {
    private fun ok(raw: String) = (BrainUrl.validate(raw) as BrainUrl.Result.Ok).baseUrl
    private fun errorOf(raw: String): BrainUrl.Error {
        val result = BrainUrl.validate(raw)
        check(result is BrainUrl.Result.Invalid) { "$raw debería ser inválida" }
        return result.error
    }

    @Test fun httpsIsAlwaysFineAndIsNormalized() {
        assertEquals("https://brain.example.com", ok("https://brain.example.com/"))
        assertEquals("https://brain.example.com:8443/api", ok("  https://brain.example.com:8443/api/  "))
        assertEquals("https://brain.example.com", ok("HTTPS://Brain.Example.COM"))
    }

    @Test fun cleartextOnlyToPrivateHosts() {
        assertEquals(BrainUrl.Error.CLEARTEXT_NOT_ALLOWED, errorOf("http://example.com"))
        assertEquals(BrainUrl.Error.CLEARTEXT_NOT_ALLOWED, errorOf("http://8.8.8.8:3030"))
        for (host in listOf("192.168.1.5:8080", "10.0.0.5", "172.16.0.1", "172.31.255.1", "100.64.0.1", "100.127.9.9", "127.0.0.1", "localhost:3030", "mybox.local", "pc.tail1234.ts.net")) {
            assertEquals("http://$host", ok("http://$host"))
        }
        assertEquals("http://[::1]:8080", ok("http://[::1]:8080"))
    }

    @Test fun addressesJustOutsidePrivateRangesAreRejected() {
        for (host in listOf("172.15.0.1", "172.32.0.1", "100.63.0.1", "100.128.0.1", "192.169.0.1", "11.0.0.1")) {
            assertEquals(host, BrainUrl.Error.CLEARTEXT_NOT_ALLOWED, errorOf("http://$host"))
        }
    }

    @Test fun invalidInputs() {
        assertEquals(BrainUrl.Error.EMPTY, errorOf(""))
        assertEquals(BrainUrl.Error.EMPTY, errorOf("   "))
        assertEquals(BrainUrl.Error.MALFORMED, errorOf("not a url"))
        assertEquals(BrainUrl.Error.MALFORMED, errorOf("ftp://example.com"))
        assertEquals(BrainUrl.Error.MALFORMED, errorOf("example.com"))
        assertEquals(BrainUrl.Error.NO_HOST, errorOf("https:///path"))
        assertEquals(BrainUrl.Error.NO_HOST, errorOf("http://300.1.1.1")) // no es una IPv4 válida: no se interpreta como host
        assertEquals(BrainUrl.Error.CREDENTIALS_IN_URL, errorOf("https://user:secret@example.com"))
    }
}

class DraftBoardTest {
    private fun draft(id: String, key: String = "wa|marta", text: String = "claro, a las 9", at: Long = 1_000, ttl: Long = DraftBoard.TTL_MS) =
        Draft(id, key, "com.whatsapp", "Marta", text, at, at + ttl)

    @Test fun addsAPendingDraft() {
        val board = DraftBoard.add(emptyList(), draft("1"), nowMs = 1_000)
        assertEquals(listOf("1"), DraftBoard.pending(board, 1_000).map { it.id })
    }

    @Test fun aNewDraftForTheSameConversationReplacesThePendingOne() {
        var board = DraftBoard.add(emptyList(), draft("1", text = "primera"), 1_000)
        board = DraftBoard.add(board, draft("2", text = "segunda", at = 2_000), 2_000)
        assertEquals(listOf("2"), DraftBoard.pending(board, 2_000).map { it.id })
        assertEquals(DraftStatus.DISCARDED, board.first { it.id == "1" }.status)
    }

    @Test fun differentConversationsCoexist() {
        var board = DraftBoard.add(emptyList(), draft("1", key = "a"), 1_000)
        board = DraftBoard.add(board, draft("2", key = "b", at = 1_001), 1_001)
        assertEquals(2, DraftBoard.pending(board, 1_001).size)
    }

    @Test fun blankTextOrKeyIsIgnoredAndLongTextIsClamped() {
        assertTrue(DraftBoard.add(emptyList(), draft("1", text = "   "), 1_000).isEmpty())
        assertTrue(DraftBoard.add(emptyList(), draft("1", key = " "), 1_000).isEmpty())
        val long = DraftBoard.add(emptyList(), draft("1", text = "x".repeat(5_000)), 1_000).single()
        assertEquals(DraftBoard.MAX_TEXT, long.text.length)
    }

    @Test fun pendingIsCappedDroppingTheOldest() {
        var board = emptyList<Draft>()
        for (i in 0 until DraftBoard.MAX_PENDING + 5) board = DraftBoard.add(board, draft("d$i", key = "k$i", at = 1_000L + i), 1_000L + i)
        val pending = DraftBoard.pending(board, 2_000)
        assertEquals(DraftBoard.MAX_PENDING, pending.size)
        assertFalse(pending.any { it.id == "d0" })
    }

    @Test fun draftsExpire() {
        val board = DraftBoard.add(emptyList(), draft("1", ttl = 10_000), 1_000)
        assertTrue(DraftBoard.pending(board, 12_000).isEmpty())
        assertEquals(DraftStatus.EXPIRED, DraftBoard.expire(board, 12_000).single().status)
    }

    @Test fun onlyAPendingDraftCanBeResolved() {
        val board = DraftBoard.add(emptyList(), draft("1"), 1_000)
        val sent = DraftBoard.resolve(board, "1", DraftStatus.SENT)
        assertEquals(DraftStatus.SENT, sent.single().status)
        // Una vez enviado no puede pasar a "descartado".
        assertEquals(DraftStatus.SENT, DraftBoard.resolve(sent, "1", DraftStatus.DISCARDED).single().status)
        assertEquals(board, DraftBoard.resolve(board, "no-existe", DraftStatus.SENT))
    }

    @Test fun resolvingToPendingIsAProgrammingError() {
        try {
            DraftBoard.resolve(emptyList(), "1", DraftStatus.PENDING)
            fail("debería lanzar")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun resolvedHistoryIsTrimmed() {
        var board = emptyList<Draft>()
        for (i in 0 until DraftBoard.KEEP_RESOLVED + 10) {
            board = DraftBoard.add(board, draft("d$i", key = "k$i", at = 1_000L + i), 1_000L + i)
            board = DraftBoard.resolve(board, "d$i", DraftStatus.DISCARDED)
        }
        assertTrue(board.size <= DraftBoard.KEEP_RESOLVED + 1)
    }
}

class CommandPolicyTest {
    private val own = "com.hermesmarcano.zen"
    private fun verdict(c: BrainCommand, seen: Set<String> = emptySet()) = CommandPolicy.check(c, own, seen)

    @Test fun acceptsWellFormedCommands() {
        assertEquals(CommandPolicy.Verdict.ACCEPT, verdict(BrainCommand.DraftReply("1", "wa|marta", "ok")))
        assertEquals(CommandPolicy.Verdict.ACCEPT, verdict(BrainCommand.Block("2", "com.zhiliaoapp.musically", 60)))
        assertEquals(CommandPolicy.Verdict.ACCEPT, verdict(BrainCommand.Notify("3", "ZEN", "Llevas 1 h en TikTok")))
        assertEquals(CommandPolicy.Verdict.ACCEPT, verdict(BrainCommand.Pause("4", 30)))
    }

    @Test fun duplicatesAreRejected() {
        assertEquals(CommandPolicy.Verdict.DUPLICATE, verdict(BrainCommand.Pause("4", 30), seen = setOf("4")))
    }

    @Test fun blankIdsAreInvalid() = assertEquals(CommandPolicy.Verdict.INVALID, verdict(BrainCommand.Pause(" ", 30)))

    @Test fun blockHasBoundsAndNeverTouchesProtectedApps() {
        assertEquals(CommandPolicy.Verdict.INVALID, verdict(BrainCommand.Block("1", "a", 0)))
        assertEquals(CommandPolicy.Verdict.INVALID, verdict(BrainCommand.Block("1", "a", CommandPolicy.MAX_BLOCK_MINUTES + 1)))
        assertEquals(CommandPolicy.Verdict.INVALID, verdict(BrainCommand.Block("1", " ", 10)))
        assertEquals(CommandPolicy.Verdict.PROTECTED, verdict(BrainCommand.Block("1", "com.android.settings", 10)))
        assertEquals(CommandPolicy.Verdict.PROTECTED, verdict(BrainCommand.Block("1", own, 10)))
    }

    @Test fun pauseAndNotifyAndDraftsAreValidated() {
        assertEquals(CommandPolicy.Verdict.INVALID, verdict(BrainCommand.Pause("1", 0)))
        assertEquals(CommandPolicy.Verdict.INVALID, verdict(BrainCommand.Pause("1", CommandPolicy.MAX_PAUSE_MINUTES + 1)))
        assertEquals(CommandPolicy.Verdict.INVALID, verdict(BrainCommand.Notify("1", "t", "")))
        assertEquals(CommandPolicy.Verdict.INVALID, verdict(BrainCommand.Notify("1", "t", "x".repeat(CommandPolicy.MAX_NOTIFY_CHARS + 1))))
        assertEquals(CommandPolicy.Verdict.INVALID, verdict(BrainCommand.DraftReply("1", "", "hola")))
        assertEquals(CommandPolicy.Verdict.INVALID, verdict(BrainCommand.DraftReply("1", "k", "  ")))
    }

    @Test fun rememberKeepsTheMostRecentIdsOnly() {
        var seen = emptyList<String>()
        for (i in 0 until CommandPolicy.REMEMBER_IDS + 20) seen = CommandPolicy.remember(seen, "c$i")
        assertEquals(CommandPolicy.REMEMBER_IDS, seen.size)
        assertFalse("c0" in seen)
        assertTrue("c${CommandPolicy.REMEMBER_IDS + 19}" in seen)
        assertEquals(seen, CommandPolicy.remember(seen, seen.last()))
    }
}
