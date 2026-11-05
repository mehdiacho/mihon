package mihon.data.lansync

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A linked PC downloading the phone's queue. Synthetic queue only. */
class RemoteQueueTest {

    private fun ref(n: Int) = RemoteQueue.ChapterRef("9223372036854775807", "/manga/$n", "/chapter/$n")

    private fun event(n: Int, id: String, where: String = RemoteQueue.WHERE_SERVER) =
        RemoteQueue.DoneEvent(ref(n).sourceId, ref(n).mangaUrl, ref(n).chapterUrl, id, where, "pc-1", 1_000L)

    private val queue = listOf(
        RemoteQueue.Entry(ref(1), downloading = false),
        RemoteQueue.Entry(ref(2), downloading = true),
        RemoteQueue.Entry(ref(3), downloading = false),
    )

    @Test
    fun `wire shapes match the contract`() {
        val json = Json { ignoreUnknownKeys = true }
        val claim = """{"items":[{"sourceId":"9223372036854775807","mangaUrl":"/m","chapterUrl":"/c"}],"by":"pc-1"}"""
        val parsed = RemoteQueue.parse<RemoteQueue.ClaimRequest>(claim.toByteArray())!!
        // The id is a string, so it survives a JavaScript number
        assertEquals("9223372036854775807", parsed.items.single().sourceId)
        assertEquals("pc-1", parsed.by)

        val done = """{"events":[{"sourceId":"1","mangaUrl":"/m","chapterUrl":"/c","eventId":"e1",""" +
            """"where":"device","deviceId":"pc-1","at":5,"extra":true}]}"""
        assertEquals("device", RemoteQueue.parse<RemoteQueue.DoneRequest>(done.toByteArray())!!.events.single().where)

        val item = RemoteQueue.QueueItem("1", "/m", "/c", "Title", "Ch. 1", 7)
        assertEquals(
            """{"sourceId":"1","mangaUrl":"/m","chapterUrl":"/c",""" +
                """"mangaTitle":"Title","chapterName":"Ch. 1","queuedAt":7}""",
            json.encodeToString(item),
        )
        assertNull(RemoteQueue.parse<RemoteQueue.ClaimRequest>("""{"items":[]}""".toByteArray()))
    }

    @Test
    fun `claims leave out what is downloading, gone, or claimed by another PC`() {
        val first = RemoteQueue.claim(RemoteQueue.State(), listOf(ref(1), ref(2), ref(9)), queue, "pc-1", "Desk", 0)
        assertEquals(listOf(ref(1)), first.claimed)

        val other = RemoteQueue.claim(first.state, listOf(ref(1), ref(3)), queue, "pc-2", "Laptop", 10)
        assertEquals(listOf(ref(3)), other.claimed)

        // Claiming again refreshes the time instead of adding a second claim
        val again = RemoteQueue.claim(other.state, listOf(ref(1)), queue, "pc-1", "Desk", 20)
        assertEquals(listOf(ref(1)), again.claimed)
        assertEquals(2, again.state.claims.size)
        assertEquals(20, again.state.claims.single { it.by == "pc-1" }.at)
    }

    @Test
    fun `a release only hands back the releasing PC's own claims`() {
        var state = RemoteQueue.claim(RemoteQueue.State(), listOf(ref(1)), queue, "pc-1", "Desk", 0).state
        state = RemoteQueue.claim(state, listOf(ref(3)), queue, "pc-2", "Laptop", 0).state

        val (after, released) = RemoteQueue.release(state, listOf(ref(1), ref(3)), "pc-1")
        assertEquals(listOf(ref(1)), released)
        assertEquals(listOf(ref(3)), after.claims.map { it.ref })
    }

    @Test
    fun `a claim times out after a day with no event`() {
        val day = RemoteQueue.CLAIM_TIMEOUT_MS
        var state = RemoteQueue.claim(RemoteQueue.State(), listOf(ref(1)), queue, "pc-1", "Desk", 0).state
        state = RemoteQueue.claim(state, listOf(ref(3)), queue, "pc-1", "Desk", day / 2).state

        assertTrue(RemoteQueue.expire(state, day - 1).refs.isEmpty())
        val expired = RemoteQueue.expire(state, day)
        assertEquals(listOf(ref(1)), expired.refs)
        assertEquals(listOf(ref(3)), expired.state.claims.map { it.ref })

        // An expired claim doesn't block another PC
        assertEquals(listOf(ref(1)), RemoteQueue.claim(state, listOf(ref(1)), queue, "pc-2", "Laptop", day).claimed)
    }

    @Test
    fun `events apply once, clear the claim, and note who holds the chapter`() {
        val claimed = RemoteQueue.claim(RemoteQueue.State(), listOf(ref(1), ref(3)), queue, "pc-1", "Desk", 0).state
        val batch = listOf(event(1, "e1"), event(3, "e3", RemoteQueue.WHERE_DEVICE), event(1, "e1"))

        val first = RemoteQueue.apply(claimed, batch)
        assertEquals(listOf("e1", "e3"), first.fresh.map { it.eventId })
        assertEquals(listOf("e1", "e3", "e1"), first.acked)
        assertTrue(first.state.claims.isEmpty())
        assertEquals(listOf(RemoteQueue.Held(ref(3), "pc-1", 1_000L)), first.state.held)

        // The same events again: acked, so the PC stops sending, but nothing happens twice
        val second = RemoteQueue.apply(first.state, batch)
        assertTrue(second.fresh.isEmpty())
        assertEquals(listOf("e1", "e3", "e1"), second.acked)
        assertEquals(first.state, second.state)

        // Malformed events are neither applied nor acked
        val bad = RemoteQueue.apply(first.state, listOf(event(4, "e4", where = "moon"), event(4, " ")))
        assertTrue(bad.acked.isEmpty())
    }

    @Test
    fun `outboxes are read from any position and torn lines are skipped`() {
        val line = { id: String -> Json.encodeToString(RemoteQueue.DoneEvent.serializer(), event(1, id)) }
        val text = listOf(line("a"), "{not json", "", line("b"), line("c")).joinToString("\n")
        val events = RemoteQueue.parseOutboxLines(text)
        assertEquals(listOf("a", "b", "c"), events.map { it.eventId })

        assertEquals(listOf("c"), RemoteQueue.after(events, "b").map { it.eventId })
        assertEquals(listOf("a", "b", "c"), RemoteQueue.after(events, null).map { it.eventId })
        // The cursor fell off the PC's outbox: take it all, repeats are ignored anyway
        assertEquals(3, RemoteQueue.after(events, "gone").size)

        val withCursor = RemoteQueue.apply(RemoteQueue.State(), events, cursorKey = "nas")
        assertEquals("c", withCursor.state.cursors["nas"])

        assertEquals(listOf("a"), RemoteQueue.parseOutbox("[${line("a")}]")!!.map { it.eventId })
        assertEquals(listOf("a"), RemoteQueue.parseOutbox("""{"events":[${line("a")}]}""")!!.map { it.eventId })
        assertNull(RemoteQueue.parseOutbox("nope"))
    }
}
