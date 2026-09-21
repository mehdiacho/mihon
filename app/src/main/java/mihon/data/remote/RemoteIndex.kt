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
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
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
     * The first of [chapterFileNames] the remote holds, or null for none.
     *
     * Takes a list because a chapter downloaded before Mihon added the URL hash
     * to chapter names is on the server under that older name, and the server
     * keeps whatever it was given.
     *
     * Null also covers "we have not looked yet". The difference matters only to
     * the caller's patience, not to its correctness, and a lookup that blocked
     * on the network would be worse.
     */
    fun resolve(sourceDirName: String, mangaDirName: String, chapterFileNames: List<String>): String? {
        if (!preferences.enabled.get()) return null

        val key = keyOf(sourceDirName, mangaDirName)
        val known = entries[key]
        if (known == null) {
            refresh(sourceDirName, mangaDirName)
            return null
        }
        return chapterFileNames.firstOrNull { it in known }
    }

    /** Whether the remote holds the chapter under any of [chapterFileNames]. */
    fun contains(sourceDirName: String, mangaDirName: String, chapterFileNames: List<String>): Boolean =
        resolve(sourceDirName, mangaDirName, chapterFileNames) != null

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
                if (listing == null) {
                    // Caching a failed request as "holds nothing" is how one
                    // refused connection becomes a permanent "not on the
                    // server" for a whole series. Leave the key unknown.
                    logcat(LogPriority.WARN) { "Could not list $key on the remote" }
                    return@launch
                }
                mutex.withLock {
                    entries[key] = listing.filter { !it.isDirectory }.mapTo(HashSet()) { it.name }
                }
                _changes.tryEmit(Unit)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /**
     * [refresh], but waits for the answer and reports what was found. Null
     * means the server could not be asked. Used by the sweep, which needs to
     * count and to know the difference between empty and unreachable.
     */
    suspend fun refreshNow(sourceDirName: String, mangaDirName: String): Int? {
        val client = clientProvider.get() ?: return null
        val listing = withContext(Dispatchers.IO) { client.list(listOf(sourceDirName, mangaDirName)) } ?: return null
        val names = listing.filter { !it.isDirectory }.mapTo(HashSet()) { it.name }
        mutex.withLock { entries[keyOf(sourceDirName, mangaDirName)] = names }
        _changes.tryEmit(Unit)
        return names.size
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
