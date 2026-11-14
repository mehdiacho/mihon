package mihon.data.lansync.changes

/** A [JournalStore] in memory, for tests and for a phone whose journal file can't be opened. */
class MemoryJournalStore : JournalStore {

    private data class Row(val change: Change, val writtenAt: Long)

    private val rows = sortedMapOf<Long, Row>()
    private val held = HashMap<Pair<String, String>, ChangeRules.Held>()
    private val ids = HashSet<String>()
    private val cursors = HashMap<Pair<String, String>, Long>()
    private var last = 0L
    private var pruned = 0L

    @Synchronized
    override fun <R> transaction(block: () -> R): R = block()

    @Synchronized
    override fun seen(changeId: String) = changeId in ids

    @Synchronized
    override fun held(kind: String, key: String) = held[kind to key]

    @Synchronized
    override fun setHeld(kind: String, key: String, held: ChangeRules.Held) {
        this.held[kind to key] = held
    }

    @Synchronized
    override fun append(change: Change, writtenAt: Long): Long {
        val seq = ++last
        rows[seq] = Row(change.copy(seq = seq), writtenAt)
        ids += change.changeId
        return seq
    }

    @Synchronized
    override fun rows(after: Long, limit: Int): List<Change> =
        rows.tailMap(after + 1).values.take(limit).map { it.change }

    @Synchronized
    override fun lastSeq() = last

    @Synchronized
    override fun oldestSeq(): Long? = if (rows.isEmpty()) null else rows.firstKey()

    @Synchronized
    override fun prunedThrough() = pruned

    @Synchronized
    override fun prune(writtenBefore: Long) {
        val old = rows.filterValues { it.writtenAt < writtenBefore }.keys
        old.forEach { rows.remove(it) }
        old.maxOrNull()?.let { pruned = maxOf(pruned, it) }
    }

    @Synchronized
    override fun cursor(peer: String, direction: String) = cursors[peer to direction]

    @Synchronized
    override fun setCursor(peer: String, direction: String, seq: Long) {
        cursors[peer to direction] = seq
    }
}
