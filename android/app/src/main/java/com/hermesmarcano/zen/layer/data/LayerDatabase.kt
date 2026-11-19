package com.hermesmarcano.zen.layer.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hermesmarcano.zen.layer.core.FtsQuery
import com.hermesmarcano.zen.layer.core.LayerEvent
import com.hermesmarcano.zen.layer.core.OutboxItem
import com.hermesmarcano.zen.layer.core.OutboxPolicy
import com.hermesmarcano.zen.layer.core.OutboxStore
import com.hermesmarcano.zen.layer.core.TimelineStore
import org.json.JSONObject

/**
 * Base de datos de la capa: la línea de tiempo (con búsqueda de texto completo FTS4) y la cola de envío.
 * Con [name] `null` es una base en memoria (pruebas).
 */
class LayerDatabase(context: Context, name: String? = "zen-layer.db") : SQLiteOpenHelper(context, name, null, VERSION) {
    init {
        // Con WAL, el hilo del micrófono puede escribir mientras la pantalla lee la línea de tiempo.
        if (name != null) setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, " +
                "pkg TEXT NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL, at_ms INTEGER NOT NULL, extra TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX events_at ON events(at_ms)")
        db.execSQL("CREATE INDEX events_pkg ON events(pkg)")
        // `unicode61` ignora mayúsculas y acentos ("reunion" encuentra "reunión").
        db.execSQL("CREATE VIRTUAL TABLE events_fts USING fts4(title, text, tokenize=unicode61)")
        db.execSQL(
            "CREATE TABLE outbox (id TEXT PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                "attempts INTEGER NOT NULL, rejections INTEGER NOT NULL, not_before INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX outbox_due ON outbox(kind, not_before, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versión 1: aún no hay migraciones.
    }

    companion object {
        const val VERSION = 1
    }
}

/** Línea de tiempo en SQLite. Cada evento está una vez en `events` y una vez en el índice de texto `events_fts`. */
class SqliteTimeline(
    private val helper: LayerDatabase,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) : TimelineStore {
    override fun insert(event: LayerEvent) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", event.id)
                put("kind", event.kind.name)
                put("pkg", event.pkg)
                put("title", event.title)
                put("text", event.text)
                put("at_ms", event.atMs)
                put("extra", JSONObject(event.extra as Map<*, *>).toString())
            }
            // Un id repetido se ignora: reenviar el mismo evento no lo duplica.
            val seq = db.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (seq != -1L) {
                db.execSQL("INSERT INTO events_fts(docid, title, text) VALUES (?, ?, ?)", arrayOf<Any>(seq, event.title, event.text))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun search(query: String, limit: Int): List<LayerEvent> {
        val match = FtsQuery.build(query) ?: return recent(limit)
        return read(
            "SELECT $COLUMNS FROM events WHERE seq IN (SELECT docid FROM events_fts WHERE events_fts MATCH ?) ORDER BY at_ms DESC, seq DESC LIMIT ?",
            arrayOf(match, limit.coerceIn(1, 500).toString()),
        )
    }

    override fun recent(limit: Int): List<LayerEvent> =
        read("SELECT $COLUMNS FROM events ORDER BY at_ms DESC, seq DESC LIMIT ?", arrayOf(limit.coerceIn(1, 500).toString()))

    override fun count(): Int = DatabaseUtils.queryNumEntries(helper.readableDatabase, "events").toInt()

    override fun deleteAll() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM events_fts")
            db.execSQL("DELETE FROM events")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun deletePackage(pkg: String): Int = deleteWhere("pkg = ?", arrayOf(pkg))

    override fun prune(nowMs: Long): Int {
        var removed = deleteWhere("at_ms < ?", arrayOf((nowMs - maxAgeMs).toString()))
        if (count() > maxRows) {
            // Se conserva lo más reciente: se borra todo lo anterior a la fila número [maxRows] contando desde la más nueva.
            val cutoff = helper.readableDatabase.rawQuery("SELECT at_ms FROM events ORDER BY at_ms DESC, seq DESC LIMIT 1 OFFSET ?", arrayOf((maxRows - 1).toString()))
                .use { if (it.moveToFirst()) it.getLong(0) else null }
            if (cutoff != null) removed += deleteWhere("at_ms < ?", arrayOf(cutoff.toString()))
        }
        return removed
    }

    private fun deleteWhere(where: String, args: Array<String>): Int {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM events_fts WHERE docid IN (SELECT seq FROM events WHERE $where)", args)
            val n = db.delete("events", where, args)
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
        }
    }

    private fun read(sql: String, args: Array<String>): List<LayerEvent> =
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<LayerEvent>(c.count)
            while (c.moveToNext()) toEvent(c)?.let(out::add)
            out
        }

    private fun toEvent(c: Cursor): LayerEvent? {
        val kind = LayerEvent.Kind.entries.firstOrNull { it.name == c.getString(1) } ?: return null
        val extra = try {
            JSONObject(c.getString(6)).let { o -> o.keys().asSequence().associateWith { k -> o.optString(k) } }
        } catch (_: Exception) {
            emptyMap()
        }
        return LayerEvent(c.getString(0), kind, c.getString(2), c.getString(3), c.getString(4), c.getLong(5), extra)
    }

    companion object {
        const val DEFAULT_MAX_ROWS = 20_000
        const val DEFAULT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val COLUMNS = "id, kind, pkg, title, text, at_ms, extra"
    }
}

/** Cola de envío persistente: sobrevive a que el proceso muera o se reinicie el teléfono. */
class SqliteOutbox(
    private val helper: LayerDatabase,
    private val maxItems: Int = OutboxPolicy.MAX_ITEMS,
    private val maxAgeMs: Long = OutboxPolicy.MAX_AGE_MS,
) : OutboxStore {
    override fun enqueue(item: OutboxItem) {
        helper.writableDatabase.insertWithOnConflict("outbox", null, values(item), SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun due(kind: OutboxItem.Kind, nowMs: Long, limit: Int): List<OutboxItem> = query(
        "kind = ? AND not_before <= ? ORDER BY created_at ASC LIMIT ?",
        arrayOf(kind.name, nowMs.toString(), limit.coerceAtLeast(1).toString()),
    )

    override fun markFailed(items: List<OutboxItem>, nowMs: Long, random: Double, rejected: Boolean): List<OutboxItem> {
        if (items.isEmpty()) return emptyList()
        val db = helper.writableDatabase
        val dropped = ArrayList<OutboxItem>()
        db.beginTransaction()
        try {
            for (item in items) {
                val next = OutboxPolicy.afterFailure(item, nowMs, random, rejected)
                if (OutboxPolicy.isExhausted(next)) {
                    db.delete("outbox", "id = ?", arrayOf(item.id))
                    dropped += next
                } else {
                    db.update("outbox", values(next), "id = ?", arrayOf(item.id))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return dropped
    }

    override fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("outbox", "id = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun count(): Int = DatabaseUtils.queryNumEntries(helper.readableDatabase, "outbox").toInt()

    override fun prune(nowMs: Long): List<OutboxItem> {
        val dropped = ArrayList<OutboxItem>()
        dropped += query("created_at < ?", arrayOf((nowMs - maxAgeMs).toString()))
        val overflow = count() - dropped.size - maxItems
        if (overflow > 0) {
            val gone = dropped.map { it.id }.toSet()
            dropped += query("1 = 1 ORDER BY created_at ASC LIMIT ?", arrayOf((overflow + gone.size).toString())).filter { it.id !in gone }.take(overflow)
        }
        remove(dropped.map { it.id })
        return dropped
    }

    override fun clear(): List<OutboxItem> {
        val all = query("kind = ?", arrayOf(OutboxItem.Kind.AUDIO.name))
        helper.writableDatabase.delete("outbox", null, null)
        return all
    }

    private fun query(where: String, args: Array<String>): List<OutboxItem> =
        helper.readableDatabase.rawQuery(
            "SELECT id, kind, payload, created_at, attempts, rejections, not_before FROM outbox WHERE $where",
            args,
        ).use { c ->
            val out = ArrayList<OutboxItem>(c.count)
            while (c.moveToNext()) {
                val kind = OutboxItem.Kind.entries.firstOrNull { it.name == c.getString(1) } ?: continue
                out += OutboxItem(c.getString(0), kind, c.getString(2), c.getLong(3), c.getInt(4), c.getInt(5), c.getLong(6))
            }
            out
        }

    private fun values(item: OutboxItem) = ContentValues().apply {
        put("id", item.id)
        put("kind", item.kind.name)
        put("payload", item.payload)
        put("created_at", item.createdAtMs)
        put("attempts", item.attempts)
        put("rejections", item.rejections)
        put("not_before", item.notBeforeMs)
    }
}
