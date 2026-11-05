package mihon.data.lansync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

/**
 * A linked PC downloading this phone's queue: the wire shapes, and what a claim, a release or a
 * done event does to the phone's record of it. The contract is TachiMana's
 * `docs/rust-port/profiles-and-remote-downloads.md`, §3.
 *
 * Android-free like [LanSyncLink], so the rules are unit tested. [LanSyncQueue] applies the
 * results to the real download queue.
 */
object RemoteQueue {

    const val WHERE_SERVER = "server"
    const val WHERE_DEVICE = "device"
    const val WHERE_SENT = "sent"
    private val WHERE = setOf(WHERE_SERVER, WHERE_DEVICE, WHERE_SENT)

    /** A PC that claimed something and then went quiet for this long gets nothing back from it. */
    const val CLAIM_TIMEOUT_MS = 24L * 60 * 60 * 1000

    /** Event ids remembered so a repeat is ignored. Old ones fall off; the PC stops resending once acked. */
    const val MAX_APPLIED = 5000

    /** Claim and done bodies list chapters, so they get more room than a link request. */
    const val MAX_BODY_BYTES = 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ChapterRef(val sourceId: String, val mangaUrl: String, val chapterUrl: String)

    @Serializable
    data class QueueItem(
        val sourceId: String,
        val mangaUrl: String,
        val chapterUrl: String,
        val mangaTitle: String,
        val chapterName: String,
        val queuedAt: Long,
    )

    @Serializable
    data class QueueResponse(val items: List<QueueItem>)

    @Serializable
    data class ClaimRequest(val items: List<ChapterRef>, val by: String)

    @Serializable
    data class ClaimResponse(val claimed: List<ChapterRef>)

    @Serializable
    data class ReleaseRequest(val items: List<ChapterRef>)

    @Serializable
    data class DoneEvent(
        val sourceId: String,
        val mangaUrl: String,
        val chapterUrl: String,
        val eventId: String,
        val where: String,
        val deviceId: String,
        val at: Long,
    ) {
        val ref get() = ChapterRef(sourceId, mangaUrl, chapterUrl)
    }

    @Serializable
    data class DoneRequest(val events: List<DoneEvent>)

    @Serializable
    data class DoneResponse(val acked: List<String>)

    @Serializable
    data class Claim(val ref: ChapterRef, val by: String, val byName: String, val at: Long)

    /** Which device holds a chapter it kept for itself (`where: "device"`). Feeds the badges. */
    @Serializable
    data class Held(val ref: ChapterRef, val deviceId: String, val at: Long)

    /** Everything the phone keeps about remote downloads. App state, never in a backup. */
    @Serializable
    data class State(
        val claims: List<Claim> = emptyList(),
        val applied: List<String> = emptyList(),
        val held: List<Held> = emptyList(),
        /** Last event id taken from each outbox (a linked device id, or "nas"), for `?since=`. */
        val cursors: Map<String, String> = emptyMap(),
    )

    /** One entry of the phone's queue as the claim rules need to see it. */
    data class Entry(val ref: ChapterRef, val downloading: Boolean)

    data class ClaimResult(val state: State, val claimed: List<ChapterRef>)

    data class ApplyResult(
        val state: State,
        /** New events, in order, for the phone to act on. Repeats are left out. */
        val fresh: List<DoneEvent>,
        /** Every well-formed event id in the batch, repeats included, so the PC can drop them. */
        val acked: List<String>,
    )

    /**
     * Claims what [by] asked for, leaving out anything not queued, already downloading, or held
     * by another device's live claim. Claiming again what it already has refreshes the time.
     */
    fun claim(
        state: State,
        wanted: List<ChapterRef>,
        queue: List<Entry>,
        by: String,
        byName: String,
        now: Long,
    ): ClaimResult {
        val live = expire(state, now).state
        val queued = queue.associateBy { it.ref }
        val othersClaims = live.claims.filter { it.by != by }.mapTo(HashSet()) { it.ref }
        val claimed = wanted.distinct().filter { ref ->
            val entry = queued[ref]
            entry != null && !entry.downloading && ref !in othersClaims
        }
        val kept = live.claims.filterNot { it.by == by && it.ref in claimed }
        return ClaimResult(live.copy(claims = kept + claimed.map { Claim(it, by, byName, now) }), claimed)
    }

    /** Hands back what [by] claimed among [refs]. Another device's claims are left alone. */
    fun release(state: State, refs: List<ChapterRef>, by: String): Pair<State, List<ChapterRef>> {
        val wanted = refs.toSet()
        val (released, kept) = state.claims.partition { it.by == by && it.ref in wanted }
        return state.copy(claims = kept) to released.map { it.ref }
    }

    /** Drops claims older than [CLAIM_TIMEOUT_MS], so those chapters download here again. */
    fun expire(state: State, now: Long): Expired {
        val (expired, live) = state.claims.partition { now - it.at >= CLAIM_TIMEOUT_MS }
        return Expired(state.copy(claims = live), expired.map { it.ref })
    }

    data class Expired(val state: State, val refs: List<ChapterRef>)

    /**
     * Records [events] and returns the ones not seen before. A finished chapter is no longer
     * claimed by anyone, and `device` events note who holds it now.
     */
    fun apply(state: State, events: List<DoneEvent>, cursorKey: String? = null): ApplyResult {
        val seen = state.applied.toHashSet()
        val fresh = mutableListOf<DoneEvent>()
        val acked = mutableListOf<String>()
        for (event in events) {
            if (!isWellFormed(event)) continue
            acked += event.eventId
            if (seen.add(event.eventId)) fresh += event
        }
        val cursors = cursorKey?.let { key -> acked.lastOrNull()?.let { state.cursors + (key to it) } }
            ?: state.cursors
        if (fresh.isEmpty()) return ApplyResult(state.copy(cursors = cursors), fresh, acked)

        val done = fresh.mapTo(HashSet()) { it.ref }
        val nowHeld = fresh.filter { it.where == WHERE_DEVICE }
        val held = state.held.filterNot { h -> nowHeld.any { it.ref == h.ref && it.deviceId == h.deviceId } } +
            nowHeld.map { Held(it.ref, it.deviceId, it.at) }
        val applied = (state.applied + fresh.map { it.eventId }).takeLast(MAX_APPLIED)
        return ApplyResult(
            state.copy(
                claims = state.claims.filterNot { it.ref in done },
                applied = applied,
                held = held,
                cursors = cursors,
            ),
            fresh,
            acked,
        )
    }

    private fun isWellFormed(event: DoneEvent): Boolean =
        event.where in WHERE &&
            listOf(event.eventId, event.deviceId, event.sourceId, event.mangaUrl, event.chapterUrl)
                .none { it.isBlank() || it.length > MAX_FIELD_CHARS }

    private const val MAX_FIELD_CHARS = 2048

    inline fun <reified T> parse(body: ByteArray): T? = try {
        jsonForParse.decodeFromString<T>(body.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    @PublishedApi
    internal val jsonForParse = Json { ignoreUnknownKeys = true }

    /**
     * A PC's `/outbox` answer. The contract doesn't fix its shape yet, so take `{events: [...]}`
     * like `/downloads/done`, or a bare array.
     */
    fun parseOutbox(text: String): List<DoneEvent>? = try {
        when (val element = json.parseToJsonElement(text)) {
            is JsonArray -> element.map { json.decodeFromJsonElement<DoneEvent>(it) }
            is JsonObject -> element["events"]?.jsonArray?.map { json.decodeFromJsonElement<DoneEvent>(it) }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** The NAS outbox: one event per line. A torn or foreign line is skipped, not fatal. */
    fun parseOutboxLines(text: String): List<DoneEvent> = text.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { line -> runCatching { json.decodeFromString<DoneEvent>(line) }.getOrNull() }
        .toList()

    /** Events after [since] in an outbox listed oldest first; all of them when it isn't there. */
    fun after(events: List<DoneEvent>, since: String?): List<DoneEvent> {
        if (since == null) return events
        val index = events.indexOfLast { it.eventId == since }
        return if (index < 0) events else events.drop(index + 1)
    }
}
