package com.hermesmarcano.zen.layer.data

import com.hermesmarcano.zen.layer.core.CountingBox
import android.app.Application
import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermesmarcano.zen.layer.core.LayerEvent
import com.hermesmarcano.zen.layer.core.OutboxItem
import com.hermesmarcano.zen.layer.core.softwareBox
import com.hermesmarcano.zen.layer.core.OutboxPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val MIN = 60_000L
private const val DAY = 24 * 60 * MIN

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SqliteTimelineTest {
    private lateinit var db: LayerDatabase
    private lateinit var timeline: SqliteTimeline

    @Before fun open() {
        db = LayerDatabase(ApplicationProvider.getApplicationContext<Context>(), null)
        timeline = SqliteTimeline(db, maxRows = 3, maxAgeMs = 10 * DAY)
    }

    @After fun close() = db.close()

    private fun ev(id: String, at: Long, title: String = "", text: String = "", pkg: String = "com.whatsapp", kind: LayerEvent.Kind = LayerEvent.Kind.SCREEN) =
        LayerEvent(id, kind, pkg, title, text, at)

    @Test fun insertsAndReadsBackWithExtras() {
        timeline.insert(LayerEvent("e1", LayerEvent.Kind.NOTIFICATION, "com.whatsapp", "Marta", "¿cenamos?", 5_000, mapOf("conv" to "wa|marta")))
        val e = timeline.recent(10).single()
        assertEquals(LayerEvent.Kind.NOTIFICATION, e.kind)
        assertEquals("Marta", e.title)
        assertEquals("¿cenamos?", e.text)
        assertEquals(mapOf("conv" to "wa|marta"), e.extra)
        assertEquals(5_000L, e.atMs)
    }

    @Test fun searchIgnoresCaseAndAccentsAndMatchesPrefixes() {
        timeline.insert(ev("e1", 1_000, "Reunión con Marta", "mañana a las 9 en la oficina"))
        timeline.insert(ev("e2", 2_000, "", "compra leche y pan"))
        assertEquals(listOf("e1"), timeline.search("reunion", 10).map { it.id })
        assertEquals(listOf("e1"), timeline.search("MARTA", 10).map { it.id })
        assertEquals(listOf("e1"), timeline.search("ofi", 10).map { it.id })
        assertEquals(listOf("e1"), timeline.search("marta oficina", 10).map { it.id }) // todas las palabras
        assertTrue(timeline.search("marta leche", 10).isEmpty())
        assertTrue(timeline.search("zzzz", 10).isEmpty())
        assertEquals(listOf("e1"), timeline.search("manana", 10).map { it.id }) // «ñ» también se busca sin tilde
        assertEquals(listOf("e2"), timeline.search("leche", 10).map { it.id })
    }

    @Test fun blankQueryReturnsTheMostRecent() {
        timeline.insert(ev("e1", 1_000, text = "uno"))
        timeline.insert(ev("e2", 2_000, text = "dos"))
        assertEquals(listOf("e2", "e1"), timeline.search("  ", 10).map { it.id })
        assertEquals(listOf("e2"), timeline.recent(1).map { it.id })
    }

    @Test fun hostileQueriesNeitherCrashNorMatchEverything() {
        timeline.insert(ev("e1", 1_000, text = "hola mundo"))
        for (q in listOf("\"", "*", "NEAR/2", "a OR b", "'; DROP TABLE events; --", "(", ")", "-", "\" OR 1=1", "title:hola")) {
            timeline.search(q, 10) // no debe lanzar
        }
        assertEquals(1, timeline.count())
        assertTrue(timeline.search("\" OR 1=1", 10).isEmpty())
    }

    @Test fun aRepeatedIdIsIgnoredAndNotIndexedTwice() {
        timeline.insert(ev("e1", 1_000, text = "hola mundo"))
        timeline.insert(ev("e1", 2_000, text = "hola mundo otra vez"))
        assertEquals(1, timeline.count())
        assertEquals(1, timeline.search("hola", 10).size)
        assertEquals(1_000L, timeline.recent(1).single().atMs)
    }

    @Test fun resultsComeNewestFirstAndRespectTheLimit() {
        timeline.insert(ev("e1", 1_000, text = "palabra"))
        timeline.insert(ev("e2", 3_000, text = "palabra"))
        timeline.insert(ev("e3", 2_000, text = "palabra"))
        assertEquals(listOf("e2", "e3", "e1"), timeline.search("palabra", 10).map { it.id })
        assertEquals(listOf("e2"), timeline.search("palabra", 1).map { it.id })
    }

    @Test fun deleteAllClearsEventsAndTheIndex() {
        timeline.insert(ev("e1", 1_000, text = "hola"))
        timeline.deleteAll()
        assertEquals(0, timeline.count())
        assertTrue(timeline.search("hola", 10).isEmpty())
    }

    @Test fun deletePackageRemovesOnlyThatApp() {
        timeline.insert(ev("e1", 1_000, text = "secreto", pkg = "com.tiktok"))
        timeline.insert(ev("e2", 2_000, text = "secreto", pkg = "com.whatsapp"))
        assertEquals(1, timeline.deletePackage("com.tiktok"))
        assertEquals(listOf("e2"), timeline.search("secreto", 10).map { it.id })
        assertEquals(0, timeline.deletePackage("com.no.existe"))
    }

    @Test fun pruneRemovesOldEventsAndTheirIndexEntries() {
        val now = 100 * DAY
        timeline.insert(ev("old", now - 11 * DAY, text = "antiguo"))
        timeline.insert(ev("new", now - DAY, text = "reciente"))
        assertEquals(1, timeline.prune(now))
        assertEquals(listOf("new"), timeline.recent(10).map { it.id })
        assertTrue(timeline.search("antiguo", 10).isEmpty())
    }

    @Test fun pruneKeepsOnlyTheNewestRowsWhenOverTheCap() {
        val now = 100 * DAY
        for (i in 1..5) timeline.insert(ev("e$i", now - (6 - i) * MIN, text = "fila$i")) // e5 es el más nuevo
        assertEquals(2, timeline.prune(now))
        assertEquals(listOf("e5", "e4", "e3"), timeline.recent(10).map { it.id })
        assertTrue(timeline.search("fila1", 10).isEmpty())
    }

    @Test fun pruneWithNothingToDoRemovesNothing() {
        timeline.insert(ev("e1", 99 * DAY, text = "hola"))
        assertEquals(0, timeline.prune(100 * DAY))
    }

    @Test fun rowsWithAnUnknownKindAreSkippedNotFatal() {
        timeline.insert(ev("e1", 1_000, text = "bueno"))
        val values = ContentValues().apply {
            put("id", "raro"); put("kind", "FUTURO"); put("pkg", ""); put("title", ""); put("text", "x"); put("at_ms", 2_000); put("extra", "{}")
        }
        db.writableDatabase.insert("events", null, values)
        assertEquals(listOf("e1"), timeline.recent(10).map { it.id })
    }

    @Test fun veryLongTextIsStored() {
        timeline.insert(ev("e1", 1_000, text = "palabra " + "x".repeat(LayerEvent.MAX_TEXT)))
        assertEquals(1, timeline.search("palabra", 10).size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SqliteOutboxTest {
    private lateinit var db: LayerDatabase
    private lateinit var outbox: SqliteOutbox
    private val box = softwareBox()

    @Before fun open() {
        db = LayerDatabase(ApplicationProvider.getApplicationContext<Context>(), null)
        outbox = SqliteOutbox(db, box, maxItems = 3, maxAgeMs = 10 * DAY)
    }

    private fun storedPayload(id: String): String =
        db.readableDatabase.rawQuery("SELECT payload FROM outbox WHERE id = ?", arrayOf(id)).use { it.moveToFirst(); it.getString(0) }

    @Test fun eventTextIsEncryptedAtRestAndReadBackIntact() {
        outbox.enqueue(item("a", 10, payload = "{\"text\":\"cita con el médico\"}"))
        val raw = storedPayload("a")
        assertTrue(raw.startsWith("enc1:"))
        assertFalse("el texto no puede estar en claro en la base", raw.contains("médico"))
        assertEquals("{\"text\":\"cita con el médico\"}", outbox.due(OutboxItem.Kind.EVENT, 100, 10).single().payload)
    }

    @Test fun anAudioItemKeepsItsFileNameInThePlain() {
        outbox.enqueue(item("s", 10, OutboxItem.Kind.AUDIO, "seg-x.wav"))
        assertEquals("seg-x.wav", storedPayload("s"))
    }

    @Test fun aKeystoreThatFailsRightNowNeverDiscardsAnything() {
        val counting = CountingBox()
        val flaky = SqliteOutbox(db, counting, maxItems = 10, maxAgeMs = 10 * DAY)
        flaky.enqueue(item("a", 10))
        flaky.enqueue(item("b", 20))
        counting.down = true
        assertTrue(flaky.due(OutboxItem.Kind.EVENT, 100, 10).isEmpty())
        assertEquals("nada se borró por un fallo pasajero", 2, flaky.count())
        counting.down = false
        assertEquals(listOf("a", "b"), flaky.due(OutboxItem.Kind.EVENT, 100, 10).map { it.id })
    }

    @Test fun pruningAndClearingDoNotNeedTheKeystore() {
        val counting = CountingBox()
        val q = SqliteOutbox(db, counting, maxItems = 1, maxAgeMs = 10 * DAY)
        q.enqueue(item("old", 0))
        q.enqueue(item("new", 20 * DAY))
        counting.down = true
        assertEquals(listOf("old"), q.prune(20 * DAY).map { it.id })
        q.clear()
        assertEquals(0, q.count())
    }

    @Test fun unreadableRowsAtTheFrontDoNotStarveTheReadableOnes() {
        val lost = SqliteOutbox(db, softwareBox(), maxItems = 10, maxAgeMs = 10 * DAY) // otra clave: lo suyo ya no se podrá abrir
        lost.enqueue(item("l1", 1)); lost.enqueue(item("l2", 2)); lost.enqueue(item("l3", 3))
        outbox.enqueue(item("good", 4))
        assertEquals(listOf("good"), outbox.due(OutboxItem.Kind.EVENT, 100, 2).map { it.id })
        assertEquals("lo irrecuperable se descartó", 1, outbox.count())
    }

    @Test fun anEventThatCannotBeDecryptedIsDiscardedInsteadOfBlockingTheQueue() {
        outbox.enqueue(item("a", 10))
        val withLostKey = SqliteOutbox(db, softwareBox(), maxItems = 3, maxAgeMs = 10 * DAY)
        assertTrue(withLostKey.due(OutboxItem.Kind.EVENT, 100, 10).isEmpty())
        assertEquals("y ya no ocupa sitio", 0, withLostKey.count())
    }

    @Test fun anEventSealedForAnotherIdIsNotAccepted() {
        outbox.enqueue(item("a", 10, payload = "uno"))
        outbox.enqueue(item("b", 20, payload = "dos"))
        db.writableDatabase.execSQL("UPDATE outbox SET payload = ? WHERE id = 'b'", arrayOf(storedPayload("a")))
        assertEquals(listOf("a"), outbox.due(OutboxItem.Kind.EVENT, 100, 10).map { it.id })
    }

    @Test fun retryBookkeepingDoesNotReEncryptOrLosePayload() {
        outbox.enqueue(item("a", 0, payload = "texto"))
        val before = storedPayload("a")
        outbox.markFailed(outbox.due(OutboxItem.Kind.EVENT, Long.MAX_VALUE, 10), 5, 0.5, rejected = false)
        assertEquals(before, storedPayload("a"))
        assertEquals("texto", outbox.due(OutboxItem.Kind.EVENT, Long.MAX_VALUE, 10).single().payload)
    }

    @After fun close() = db.close()

    private fun item(id: String, at: Long, kind: OutboxItem.Kind = OutboxItem.Kind.EVENT, payload: String = "{}") = OutboxItem(id, kind, payload, at)

    @Test fun dueReturnsReadyItemsOldestFirstAndByKind() {
        outbox.enqueue(item("b", 20))
        outbox.enqueue(item("a", 10))
        outbox.enqueue(item("later", 5).copy(notBeforeMs = 9_000))
        outbox.enqueue(item("audio", 1, OutboxItem.Kind.AUDIO, "seg.wav"))
        assertEquals(listOf("a", "b"), outbox.due(OutboxItem.Kind.EVENT, 100, 10).map { it.id })
        assertEquals(listOf("a"), outbox.due(OutboxItem.Kind.EVENT, 100, 1).map { it.id })
        assertEquals(listOf("audio"), outbox.due(OutboxItem.Kind.AUDIO, 100, 10).map { it.id })
        assertEquals(3, outbox.due(OutboxItem.Kind.EVENT, 10_000, 10).size)
    }

    @Test fun enqueuingTheSameIdReplacesIt() {
        outbox.enqueue(item("a", 10, payload = "uno"))
        outbox.enqueue(item("a", 10, payload = "dos"))
        assertEquals(1, outbox.count())
        assertEquals("dos", outbox.due(OutboxItem.Kind.EVENT, 100, 10).single().payload)
    }

    @Test fun networkFailuresPostponeButNeverDrop() {
        outbox.enqueue(item("a", 0))
        repeat(30) { i ->
            val current = outbox.due(OutboxItem.Kind.EVENT, Long.MAX_VALUE, 10)
            assertTrue(outbox.markFailed(current, nowMs = i * 1_000L, random = 0.5, rejected = false).isEmpty())
        }
        val a = outbox.due(OutboxItem.Kind.EVENT, Long.MAX_VALUE, 10).single()
        assertEquals(30, a.attempts)
        assertEquals(0, a.rejections)
        assertTrue(a.notBeforeMs > 0)
        assertTrue("no está lista justo después de fallar", outbox.due(OutboxItem.Kind.EVENT, 29_000L, 10).isEmpty())
    }

    @Test fun repeatedRejectionsDropTheItemAndReturnIt() {
        outbox.enqueue(item("a", 0, OutboxItem.Kind.AUDIO, "seg-x.wav"))
        var dropped = emptyList<OutboxItem>()
        repeat(OutboxPolicy.MAX_REJECTIONS) {
            val current = outbox.due(OutboxItem.Kind.AUDIO, Long.MAX_VALUE, 10)
            dropped = outbox.markFailed(current, 0, 0.5, rejected = true)
        }
        assertEquals(listOf("seg-x.wav"), dropped.map { it.payload })
        assertEquals(0, outbox.count())
    }

    @Test fun removeDeletesOnlyTheGivenIds() {
        outbox.enqueue(item("a", 1)); outbox.enqueue(item("b", 2)); outbox.enqueue(item("c", 3))
        outbox.remove(listOf("a", "c", "no-existe"))
        assertEquals(listOf("b"), outbox.due(OutboxItem.Kind.EVENT, 100, 10).map { it.id })
        outbox.remove(emptyList())
    }

    @Test fun pruneDropsExpiredItems() {
        val now = 100 * DAY
        outbox.enqueue(item("old", now - 11 * DAY))
        outbox.enqueue(item("new", now - DAY))
        assertEquals(listOf("old"), outbox.prune(now).map { it.id })
        assertEquals(1, outbox.count())
    }

    @Test fun pruneDropsTheOldestWhenOverTheCap() {
        val now = 100 * DAY
        for (i in 1..5) outbox.enqueue(item("e$i", now - (6 - i) * MIN))
        assertEquals(listOf("e1", "e2"), outbox.prune(now).map { it.id })
        assertEquals(3, outbox.count())
    }

    @Test fun pruneDoesNotCountExpiredItemsTwice() {
        val now = 100 * DAY
        outbox.enqueue(item("old", now - 11 * DAY))
        for (i in 1..3) outbox.enqueue(item("e$i", now - (4 - i) * MIN))
        // 4 en total: 1 caducado + 3 vigentes (= tope). No sobra nada más que quitar.
        assertEquals(listOf("old"), outbox.prune(now).map { it.id })
        assertEquals(3, outbox.count())
    }

    @Test fun clearReturnsTheAudioItemsAndEmptiesTheQueue() {
        outbox.enqueue(item("e", 1)); outbox.enqueue(item("a", 2, OutboxItem.Kind.AUDIO, "seg.wav"))
        assertEquals(listOf("seg.wav"), outbox.clear().map { it.payload })
        assertEquals(0, outbox.count())
        assertFalse(outbox.due(OutboxItem.Kind.EVENT, Long.MAX_VALUE, 10).isNotEmpty())
    }
}
