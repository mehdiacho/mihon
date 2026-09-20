package mihon.data.remote

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Knows which chapters the remote holds.
 *
 * The local [eu.kanade.tachiyomi.data.download.DownloadCache] is derived from
 * the filesystem, so it cannot answer anything about the server. This is the
 * equivalent for the remote, and it is what lets a chapter that has been
 * evicted locally still be shown as *kept somewhere*.
 *
 * Filled in lazily, one directory at a time. Nothing walks the whole library:
 * a manga is listed the first time something asks about it, and the answer is
 * kept until invalidated. A miss is not an answer of "no" -- it is "not looked
 * yet", which is why [changes] exists for the UI to recompose on.
 */
@Inject
@SingleIn(AppScope::class)
class RemoteIndex(
    private val preferences: RemoteStoragePreferences,
    private val clientProvider: RemoteClientProvider,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("RemoteIndex"))

    /** `<source dir>/<manga dir>` to the chapter file names found under it. */
    private val entries = ConcurrentHashMap<String, Set<String>>()

    /** Directories currently being listed, so a burst of queries costs one PROPFIND. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val mutex = Mutex()

    // replay = 1 deliberately. A refresh is started from composition and
    // finishes on an IO thread, often before the screen's collector has
    // attached; with no replay that emission goes nowhere and the row keeps
    // showing the stale "not on the server" answer until something else
    // recomposes it. Replaying the last signal means a late subscriber is told
    // immediately that the index has moved on.
    private val _changes = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    /** Emits when the index has learned something new. */
    val changes = _changes.asSharedFlow()

    private fun keyOf(sourceDirName: String, mangaDirName: String) = "$sourceDirName/$mangaDirName"

    /**
     * Whether the remote holds [chapterFileName].
     *
     * Returns false both for "the server does not have it" and for "we have not
     * looked". The difference matters only to the caller's patience, not to its
     * correctness, and a lookup that blocked on the network would be worse.
     */
    fun contains(sourceDirName: String, mangaDirName: String, chapterFileName: String): Boolean {
        if (!preferences.enabled.get()) return false

        val key = keyOf(sourceDirName, mangaDirName)
        val known = entries[key]
        if (known == null) {
            refresh(sourceDirName, mangaDirName)
            return false
        }
        return chapterFileName in known
    }

    /** How many chapters of this manga the remote holds, or 0 if not yet known. */
    fun countFor(sourceDirName: String, mangaDirName: String): Int {
        if (!preferences.enabled.get()) return 0

        val key = keyOf(sourceDirName, mangaDirName)
        val known = entries[key]
        if (known == null) {
            refresh(sourceDirName, mangaDirName)
            return 0
        }
        return known.size
    }

    /**
     * Lists one manga directory in the background, then signals [changes].
     * Concurrent callers for the same directory collapse into one request.
     */
    fun refresh(sourceDirName: String, mangaDirName: String) {
        val key = keyOf(sourceDirName, mangaDirName)
        if (!inFlight.add(key)) return

        scope.launch {
            try {
                val client = clientProvider.get() ?: return@launch
                val listing = client.list(listOf(sourceDirName, mangaDirName))
                mutex.withLock {
                    // A directory that does not exist is a valid answer -- it
                    // means the remote holds nothing for this manga -- and is
                    // cached as the empty set so it is not asked again.
                    entries[key] = listing.orEmpty()
                        .filter { !it.isDirectory }
                        .mapTo(HashSet()) { it.name }
                }
                _changes.tryEmit(Unit)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /** Records an upload without a round trip to confirm what we just did. */
    fun onUploaded(segments: List<String>) {
        if (segments.size < 3) return
        val key = keyOf(segments[0], segments[1])
        // If this directory has never been listed there is nothing to add to:
        // inserting a single-element set would assert the server holds only
        // this chapter, which is a stronger claim than we have. Listing it is
        // the honest answer, and it is one request.
        val updated = entries.computeIfPresent(key) { _, existing -> existing + segments.last() }
        if (updated == null) {
            refresh(segments[0], segments[1])
            return
        }
        _changes.tryEmit(Unit)
    }

    /** Records a deletion, likewise. */
    fun onDeleted(segments: List<String>) {
        if (segments.size < 3) return
        val key = keyOf(segments[0], segments[1])
        entries.computeIfPresent(key) { _, existing -> existing - segments.last() }
        _changes.tryEmit(Unit)
    }

    /** Forgets a manga directory, e.g. after it is renamed. */
    fun forget(sourceDirName: String, mangaDirName: String) {
        entries.remove(keyOf(sourceDirName, mangaDirName))
        _changes.tryEmit(Unit)
    }

    /** Drops everything; the next query re-lists. */
    fun invalidate() {
        entries.clear()
        _changes.tryEmit(Unit)
    }
}
