package mihon.data.lansync.changes

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/** `changes/1` against TachiMana's test files, and the journal's serving rules. Synthetic data only. */
class ChangeJournalTest {

    @Serializable
    private data class Vector(
        val note: String,
        val held: List<Change> = emptyList(),
        val incoming: List<Change>,
        val expect: Expect,
    )

    @Serializable
    private data class Expect(val values: List<Value>, val applied: Int, val ignored: Int)

    @Serializable
    private data class Value(val kind: String, val target: JsonObject, val value: JsonObject)

    private fun vectors(): List<Pair<String, Vector>> {
        val dir = File(javaClass.classLoader!!.getResource("tlp/changes-1")!!.toURI())
        return dir.listFiles()!!.sortedBy { it.name }.map {
            it.name to ChangesWire.json.decodeFromString<Vector>(it.readText())
        }
    }

    @TestFactory
    fun `protocol test files pass`(): List<DynamicTest> = vectors().map { (name, vector) ->
        DynamicTest.dynamicTest("$name: ${vector.note}") {
            val store = MemoryJournalStore()
            val journal = ChangeJournal(store, { "receiver" })
            runBlocking {
                journal.receive(vector.held) { _, _ -> true }
                val result = journal.receive(vector.incoming) { _, _ -> true }
                assertEquals(vector.expect.applied, result.applied, "applied")
                assertEquals(vector.expect.ignored, result.ignored, "ignored")
            }
            vector.expect.values.forEach { want ->
                val held = store.held(want.kind, ChangeRules.targetKey(want.target))!!.value
                want.value.forEach { (k, v) -> assertEquals(v, held[k], "${want.kind}.$k") }
            }
        }
    }

    private val target = buildJsonObject {
        put("sourceId", "1")
        put("mangaUrl", "/m")
        put("chapterUrl", "/c")
    }

    private fun read(read: Boolean) = buildJsonObject {
        put("read", read)
        put("lastPageRead", 0)
    }

    @Test
    fun `serving leaves out the caller's own entries but still moves the cursor`() {
        val store = MemoryJournalStore()
        val phone = ChangeJournal(store, { "phone" })
        runBlocking {
            phone.receive(listOf(Change("x", "pc", 1, 0, ChangeKind.CHAPTER_READ, target, read(true)))) { _, _ -> true }
        }
        val page = (phone.serve(null, 500, "pc") as ChangeJournal.Served.Page).page
        assertTrue(page.changes.isEmpty())
        assertEquals(1, page.next)
        assertEquals(false, page.more)

        phone.record(ChangeKind.CHAPTER_READ, target, read(false))
        val next = (phone.serve(page.next, 500, "pc") as ChangeJournal.Served.Page).page
        assertEquals(listOf("phone"), next.changes.map { it.deviceId })
        assertEquals(2, next.next)
    }

    @Test
    fun `limit pages oldest first`() {
        val journal = ChangeJournal(MemoryJournalStore(), { "phone" })
        repeat(5) {
            journal.record(ChangeKind.CHAPTER_BOOKMARK, target, buildJsonObject { put("bookmark", it % 2 == 0) })
        }
        val page = (journal.serve(1, 2, "pc") as ChangeJournal.Served.Page).page
        assertEquals(listOf(2L, 3L), page.changes.map { it.seq })
        assertEquals(3, page.next)
        assertEquals(true, page.more)
    }

    @Test
    fun `a cursor older than what is kept is too old`() {
        var now = 0L
        val journal = ChangeJournal(MemoryJournalStore(), { "phone" }, clock = { now })
        journal.record(ChangeKind.CHAPTER_BOOKMARK, target, buildJsonObject { put("bookmark", true) })
        now = ChangeJournal.RETENTION_MS + 10
        journal.record(ChangeKind.CHAPTER_BOOKMARK, target, buildJsonObject { put("bookmark", false) })
        journal.prune()
        assertEquals(ChangeJournal.Served.TooOld(2), journal.serve(null, 500, "pc"))
        assertEquals(ChangeJournal.Served.TooOld(2), journal.serve(0, 500, "pc"))
        assertTrue(journal.serve(1, 500, "pc") is ChangeJournal.Served.Page)
        // A cursor past the end, from before this journal was reset
        assertEquals(ChangeJournal.Served.TooOld(2), journal.serve(40, 500, "pc"))
    }

    @Test
    fun `a follower's entries are ignored by the device it follows`() = runBlocking {
        val journal = ChangeJournal(MemoryJournalStore(), { "phone" })
        val result = journal.receive(
            listOf(Change("x", "pc", 1, 0, ChangeKind.CHAPTER_READ, target, read(true))),
            ignoreFrom = setOf("pc"),
        ) { _, _ -> error("not applied") }
        assertEquals(ChangesWire.PushResult(0, 1), result)
    }

    @Test
    fun `history adds reading time from local and incoming sessions`() = runBlocking {
        val store = MemoryJournalStore()
        val journal = ChangeJournal(store, { "phone" })
        journal.record(ChangeKind.HISTORY_READ, target, history(10, 100))
        journal.receive(listOf(Change("h", "pc", 5, 0, ChangeKind.HISTORY_READ, target, history(5, 50)))) { _, v ->
            assertEquals(JsonPrimitive(10), v["lastRead"])
            assertEquals(JsonPrimitive(150), v["timeRead"])
            true
        }
        Unit
    }

    @Test
    fun `push sends what the peer did not make, from the cursor`() {
        val journal = ChangeJournal(MemoryJournalStore(), { "phone" })
        journal.record(ChangeKind.CHAPTER_BOOKMARK, target, buildJsonObject { put("bookmark", true) })
        journal.startPushingNow("pc")
        journal.record(ChangeKind.CHAPTER_BOOKMARK, target, buildJsonObject { put("bookmark", false) })
        val (changes, cursor) = journal.toPush("pc")
        assertEquals(listOf(2L), changes.map { it.seq })
        assertEquals(2, cursor)
    }

    private fun history(lastRead: Long, timeRead: Long) = buildJsonObject {
        put("lastRead", lastRead)
        put("timeRead", timeRead)
    }
}
