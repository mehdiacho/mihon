package mihon.data.lansync.changes

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Where a journal keeps its rows. SQLite on the phone, a map in tests. */
interface JournalStore {

    fun <R> transaction(block: () -> R): R

    fun seen(changeId: String): Boolean

    fun held(kind: String, key: String): ChangeRules.Held?

    fun setHeld(kind: String, key: String, held: ChangeRules.Held)

    /** Writes [change] under the next `seq`, noting [writtenAt] for retention, and returns the seq. */
    fun append(change: Change, writtenAt: Long): Long

    /** Up to [limit] rows after [after], oldest first, whoever made them. */
    fun rows(after: Long, limit: Int): List<Change>

    /** The highest seq written, 0 for an empty journal. */
    fun lastSeq(): Long

    /** The lowest seq still kept, or null if nothing is. */
    fun oldestSeq(): Long?

    /** The highest seq ever dropped by [prune], 0 if none was. */
    fun prunedThrough(): Long

    /** Drops rows written before [writtenBefore]. */
    fun prune(writtenBefore: Long)

    fun cursor(peer: String, direction: String): Long?

    fun setCursor(peer: String, direction: String, seq: Long)
}

/**
 * This device's change journal (`changes/1`): records the person's changes, serves them after a
 * cursor, and applies entries from linked devices by the "who wins" rules.
 */
class ChangeJournal(
    private val store: JournalStore,
    private val deviceId: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    sealed interface Served {
        data class Page(val page: ChangesWire.Page) : Served
        data class TooOld(val oldest: Long) : Served
    }

    /** Writes [value] for one target into the journal, as a change made on this device. */
    fun record(kind: String, target: JsonObject, value: JsonObject, at: Long = clock()): Change =
        store.transaction {
            val key = ChangeRules.targetKey(target)
            val held = store.held(kind, key)
            val change = Change(
                changeId = newId(),
                deviceId = deviceId(),
                at = at,
                kind = kind,
                target = target,
                value = value,
            )
            val seq = store.append(change, clock())
            store.setHeld(kind, key, ChangeRules.Held(at, change.changeId, ChangeRules.afterLocal(kind, held?.value, value)))
            change.copy(seq = seq)
        }

    private val receiving = Mutex()

    /**
     * Applies [changes] in order. [ignoreFrom] are devices whose entries are not taken at all (the
     * ones that follow this device). [apply] writes a winning value into the library and says
     * whether it could; an entry it couldn't apply is left out of the journal so a later full
     * merge can still bring it.
     */
    suspend fun receive(
        changes: List<Change>,
        ignoreFrom: Set<String> = emptySet(),
        apply: suspend (Change, JsonObject) -> Boolean,
    ): ChangesWire.PushResult = receiving.withLock {
        var applied = 0
        var ignored = 0
        val me = deviceId()
        for (change in changes) {
            if (change.kind !in ChangeKind.KNOWN ||
                change.deviceId == me ||
                change.deviceId in ignoreFrom ||
                store.seen(change.changeId)
            ) {
                ignored++
                continue
            }
            val key = ChangeRules.targetKey(change.target)
            val held = store.held(change.kind, key)
            when (val decision = ChangeRules.decide(held, change)) {
                is ChangeRules.Decision.Apply -> {
                    if (apply(change, decision.value)) {
                        store.transaction {
                            store.append(change.copy(seq = 0), clock())
                            store.setHeld(change.kind, key, ChangeRules.Held(change.at, change.changeId, decision.value))
                        }
                        applied++
                    } else {
                        ignored++
                    }
                }
                is ChangeRules.Decision.Adjust -> {
                    if (held != null && apply(change, decision.value)) {
                        store.setHeld(change.kind, key, held.copy(value = decision.value))
                    }
                    ignored++
                }
                ChangeRules.Decision.Ignore -> ignored++
            }
        }
        ChangesWire.PushResult(applied, ignored)
    }

    /** `GET /changes`: what [caller] hasn't got, after its cursor. */
    fun serve(after: Long?, limit: Int, caller: String): Served {
        val last = store.lastSeq()
        val oldest = store.oldestSeq() ?: (last + 1)
        val from = after ?: 0
        // Dropped entries the caller never had, or a cursor from before this journal was reset
        if (from < store.prunedThrough() || from > last) return Served.TooOld(oldest.coerceAtMost(last).coerceAtLeast(0))

        val rows = store.rows(from, limit)
        val next = rows.lastOrNull()?.seq ?: from
        return Served.Page(
            ChangesWire.Page(
                deviceId = deviceId(),
                changes = rows.filter { it.deviceId != caller },
                next = next,
                more = next < last,
            ),
        )
    }

    /** Entries to push to [peer] after this device's push cursor for it, and the cursor they reach. */
    fun toPush(peer: String, limit: Int = ChangesWire.MAX_PUSH): Pair<List<Change>, Long> {
        val from = store.cursor(peer, PUSH) ?: store.lastSeq()
        val rows = store.rows(from, limit)
        return rows.filter { it.deviceId != peer } to (rows.lastOrNull()?.seq ?: from)
    }

    fun pullCursor(peer: String): Long? = store.cursor(peer, PULL)

    fun setPullCursor(peer: String, seq: Long) = store.setCursor(peer, PULL, seq)

    fun setPushCursor(peer: String, seq: Long) = store.setCursor(peer, PUSH, seq)

    /** Starts pushing to [peer] from now: what is already in the journal reached it another way. */
    fun startPushingNow(peer: String) = store.setCursor(peer, PUSH, store.lastSeq())

    fun hasPushCursor(peer: String) = store.cursor(peer, PUSH) != null

    fun lastSeq(): Long = store.lastSeq()

    /** Keeps at least [RETENTION_MS] of entries. */
    fun prune() = store.prune(clock() - RETENTION_MS)

    companion object {
        const val PULL = "pull"
        const val PUSH = "push"

        /** The order says at least 90 days; a little over, so a device away for exactly 90 still catches up. */
        const val RETENTION_MS = 100L * 24 * 60 * 60 * 1000
    }
}
