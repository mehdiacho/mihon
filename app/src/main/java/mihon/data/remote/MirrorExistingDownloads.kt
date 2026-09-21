package mihon.data.remote

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager

/**
 * Uploads chapters that were downloaded before mirroring was switched on.
 *
 * The upload hook in [eu.kanade.tachiyomi.data.download.Downloader] only fires
 * when a download completes, so without this a library that already exists on
 * disk would never reach the server -- which is exactly the state anyone
 * enabling this feature is in.
 *
 * Walks the downloads tree rather than the library, because the tree is what
 * actually has files: a chapter that was downloaded and then removed from the
 * library is still a chapter worth keeping.
 */
@Inject
@SingleIn(AppScope::class)
class MirrorExistingDownloads(
    private val storageManager: StorageManager,
    private val clientProvider: RemoteClientProvider,
    private val mirror: RemoteMirror,
    private val index: RemoteIndex,
    private val maintenance: RemoteMaintenance,
) {

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    sealed interface State {
        data object Idle : State

        data class Scanning(val found: Int) : State

        data class Running(
            val uploaded: Int,
            val skipped: Int,
            val failed: Int,
            val total: Int,
            val current: String,
        ) : State

        data class Done(val uploaded: Int, val skipped: Int, val failed: Int, val freedBytes: Long) : State
    }

    /**
     * Walks every `<source>/<manga>/<chapter>.cbz` under the downloads
     * directory and uploads what the server does not already hold.
     *
     * Suspending and cancellable rather than fire-and-forget: this is a
     * foreground action the user started and can watch, unlike the automatic
     * per-chapter mirroring.
     *
     * The dispatcher is chosen here rather than left to the caller. Every step
     * of this blocks -- walking a SAF tree, stat-ing each file, and one HTTP
     * request per chapter -- so a caller that launches it from a composable's
     * scope gets the main thread, an ANR, and a NetworkOnMainThreadException
     * per chapter swallowed into the failure count.
     */
    suspend fun run() = withContext(Dispatchers.IO) {
        if (!mirror.isEnabled) return@withContext

        val client = clientProvider.get() ?: return@withContext
        val root = storageManager.getDownloadsDirectory() ?: return@withContext

        // Nothing from a previous run should decide what this one uploads.
        remoteListings.clear()

        _state.value = State.Scanning(0)

        // Collected up front so the total is known before the first upload;
        // a progress bar that discovers its own length is not a progress bar.
        val candidates = mutableListOf<Candidate>()
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { sourceDir ->
            val sourceName = sourceDir.name ?: return@forEach
            sourceDir.listFiles().orEmpty().filter { it.isDirectory }.forEach { mangaDir ->
                val mangaName = mangaDir.name ?: return@forEach
                mangaDir.listFiles().orEmpty()
                    .filter { it.isFile && it.name?.endsWith(".cbz") == true }
                    .forEach { file ->
                        candidates += Candidate(file, listOf(sourceName, mangaName, file.name!!))
                        _state.value = State.Scanning(candidates.size)
                    }
            }
        }

        var uploaded = 0
        var skipped = 0
        var failed = 0
        var freed = 0L

        // One listing per manga directory, rather than one HEAD per chapter.
        // Over a library of several thousand chapters that is the difference
        // between a few hundred requests and a few thousand.
        candidates.forEachIndexed { i, candidate ->
            _state.value = State.Running(uploaded, skipped, failed, candidates.size, candidate.segments[1])

            val (source, manga, chapter) = candidate.segments
            val dirKey = "$source/$manga"
            val remote = remoteListings.getOrPut(dirKey) {
                client.list(candidate.segments.dropLast(1)).orEmpty()
                    .filter { !it.isDirectory }
                    .associate { it.name to it.size }
            }

            val localSize = candidate.file.length()

            when {
                // Same name and same length is as close to "already there" as
                // this can get without reading both copies back.
                remote[chapter] == localSize && localSize > 0L -> {
                    skipped++
                    index.onUploaded(candidate.segments)
                }
                localSize <= 0L -> {
                    failed++
                    logcat(LogPriority.WARN) { "Empty or unreadable: $dirKey/$chapter" }
                }
                else -> {
                    val result = client.put(candidate.segments, localSize) { candidate.file.openInputStream() }
                    if (result.isSuccess) {
                        uploaded++
                        index.onUploaded(candidate.segments)
                    } else {
                        failed++
                        logcat(LogPriority.WARN, result.exceptionOrNull()) { "Could not mirror $dirKey/$chapter" }
                    }
                }
            }

            // Reclaim as each series finishes rather than at the end, so the
            // peak local footprint is one series instead of the whole library.
            // A no-op unless the user asked for local copies to be removed.
            val nextDirKey = candidates.getOrNull(i + 1)?.let { "${it.segments[0]}/${it.segments[1]}" }
            if (nextDirKey != dirKey) {
                freed += maintenance.reclaimSeries(source, manga).freedBytes
            }

            if (i == candidates.lastIndex) {
                _state.value = State.Done(uploaded, skipped, failed, freed)
            }
        }

        if (candidates.isEmpty()) {
            _state.value = State.Done(0, 0, 0, 0L)
        }
    }

    fun reset() {
        _state.value = State.Idle
    }

    /** One PROPFIND per manga directory, reused across its chapters. */
    private val remoteListings = mutableMapOf<String, Map<String, Long>>()

    private data class Candidate(val file: UniFile, val segments: List<String>)
}
