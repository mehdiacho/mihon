package mihon.data.lansync.changes

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.JsonObject

/**
 * The journal in its own database file, apart from the library's, so the library's schema and its
 * upstream migrations stay untouched.
 */
class SqliteJournalStore(context: Context) : JournalStore {

    private val helper = object : SQLiteOpenHelper(context, FILE, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE journal(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    change_id TEXT NOT NULL UNIQUE,
                    device_id TEXT NOT NULL,
                    at INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    target TEXT NOT NULL,
                    value TEXT NOT NULL,
                    written_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX journal_written ON journal(written_at)")
            db.execSQL(
                """
                CREATE TABLE held(
                    kind TEXT NOT NULL,
                    target_key TEXT NOT NULL,
                    at INTEGER NOT NULL,
                    change_id TEXT NOT NULL,
                    value TEXT NOT NULL,
                    PRIMARY KEY(kind, target_key)
                )
                """.trimIndent(),
            )
            // Ids of dropped entries stay known, so an old repeat isn't applied a second time
            db.execSQL("CREATE TABLE seen(change_id TEXT PRIMARY KEY)")
            db.execSQL(
                "CREATE TABLE cursor(peer TEXT NOT NULL, direction TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY(peer, direction))",
            )
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    private val json = ChangesWire.json

    @Synchronized
    override fun <R> transaction(block: () -> R): R {
        val db = db
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun seen(changeId: String): Boolean =
        db.rawQuery("SELECT 1 FROM seen WHERE change_id = ?", arrayOf(changeId)).use { it.moveToFirst() }

    @Synchronized
    override fun held(kind: String, key: String): ChangeRules.Held? =
        db.rawQuery("SELECT at, change_id, value FROM held WHERE kind = ? AND target_key = ?", arrayOf(kind, key))
            .use {
                if (!it.moveToFirst()) {
                    null
                } else {
                    ChangeRules.Held(it.getLong(0), it.getString(1), json.decodeFromString<JsonObject>(it.getString(2)))
                }
            }

    @Synchronized
    override fun setHeld(kind: String, key: String, held: ChangeRules.Held) {
        db.insertWithOnConflict(
            "held",
            null,
            ContentValues().apply {
                put("kind", kind)
                put("target_key", key)
                put("at", held.at)
                put("change_id", held.changeId)
                put("value", json.encodeToString(held.value))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    override fun append(change: Change, writtenAt: Long): Long = transaction {
        db.insertWithOnConflict(
            "seen",
            null,
            ContentValues().apply { put("change_id", change.changeId) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        db.insertOrThrow(
            "journal",
            null,
            ContentValues().apply {
                put("change_id", change.changeId)
                put("device_id", change.deviceId)
                put("at", change.at)
                put("kind", change.kind)
                put("target", json.encodeToString(change.target))
                put("value", json.encodeToString(change.value))
                put("written_at", writtenAt)
            },
        )
    }

    @Synchronized
    override fun rows(after: Long, limit: Int): List<Change> =
        db.rawQuery(
            "SELECT seq, change_id, device_id, at, kind, target, value FROM journal WHERE seq > ? ORDER BY seq LIMIT ?",
            arrayOf(after.toString(), limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Change(
                            seq = c.getLong(0),
                            changeId = c.getString(1),
                            deviceId = c.getString(2),
                            at = c.getLong(3),
                            kind = c.getString(4),
                            target = json.decodeFromString(c.getString(5)),
                            value = json.decodeFromString(c.getString(6)),
                        ),
                    )
                }
            }
        }

    @Synchronized
    override fun lastSeq(): Long =
        // AUTOINCREMENT keeps counting after rows are dropped, so read its counter, not max(seq)
        db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name = 'journal'", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    @Synchronized
    override fun oldestSeq(): Long? =
        db.rawQuery("SELECT min(seq) FROM journal", null)
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    @Synchronized
    override fun prunedThrough(): Long = meta(PRUNED_THROUGH) ?: 0L

    @Synchronized
    override fun prune(writtenBefore: Long) = transaction {
        val dropped =
            db.rawQuery("SELECT max(seq) FROM journal WHERE written_at < ?", arrayOf(writtenBefore.toString()))
                .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
                ?: return@transaction
        db.delete("journal", "written_at < ?", arrayOf(writtenBefore.toString()))
        setMeta(PRUNED_THROUGH, maxOf(dropped, prunedThrough()))
    }

    @Synchronized
    override fun cursor(peer: String, direction: String): Long? =
        db.rawQuery("SELECT seq FROM cursor WHERE peer = ? AND direction = ?", arrayOf(peer, direction))
            .use { if (it.moveToFirst()) it.getLong(0) else null }

    @Synchronized
    override fun setCursor(peer: String, direction: String, seq: Long) {
        db.insertWithOnConflict(
            "cursor",
            null,
            ContentValues().apply {
                put("peer", peer)
                put("direction", direction)
                put("seq", seq)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun meta(key: String): Long? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }

    private fun setMeta(key: String, value: Long) {
        db.insertWithOnConflict(
            "meta",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private companion object {
        const val FILE = "lansync_journal.db"
        const val VERSION = 1
        const val PRUNED_THROUGH = "pruned_through"
    }
}
