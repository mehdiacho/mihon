package mihon.data.remote

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.days

/**
 * Removes local copies the server has been confirmed to hold.
 *
 * Three callers want this with slightly different rules -- the periodic job,
 * the end of a bulk upload, and the user pressing "Free up space" -- so there
 * is one walk with a [Mode] rather than three walks that would drift apart.
 *
 * [EvictionTiming.AFTER_UPLOAD] needs nothing from here -- the upload path can
 * act the moment it has verified the transfer. The other two are conditions
 * that become true later, with no event to hang them on, so something has to
 * come back and look.
 *
 * Walks the library rather than the downloads tree, unlike
 * [MirrorExistingDownloads]. Eviction needs a chapter's read state and its row
 * id, and neither of those can be recovered from a filename. The cost is that
 * downloads belonging to a series no longer in the library are never swept;
 * that is the safer way round, since nothing else would ever bring them back.
 */
@Inject
@SingleIn(AppScope::class)
class RemoteMaintenance(
    private val preferences: RemoteStoragePreferences,
    private val mirror: RemoteMirror,
    private val index: RemoteIndex,
    private val keepStore: RemoteKeepStore,
    private val provider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val getFavorites: GetFavorites,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    data class Result(val evicted: Int, val examined: Int, val freedBytes: Long)

    /**
     * Why this walk is happening, which is the only thing that differs between
     * the three callers.
     *
     * Kept as one walk rather than three, because the parts that must not drift
     * -- never evict what the server has not been confirmed to hold, never
     * evict what the user pinned -- are the parts they have in common.
     */
    enum class Mode {
        /** The periodic job: only the timings that need a later look. */
        SCHEDULED,

        /** Catching up after a bulk upload. Obeys the eviction preference. */
        AFTER_BULK_UPLOAD,

        /** The user pressed the button: everything the server holds, now. */
        ON_DEMAND,
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    sealed interface State {
        data object Idle : State

        data class Running(val examined: Int, val evicted: Int, val freedBytes: Long) : State

        data class Done(val evicted: Int, val freedBytes: Long) : State
    }

    suspend fun sweep(): Result = walk(Mode.SCHEDULED)

    /**
     * Frees every local copy the server is confirmed to hold.
     *
     * The catch-up for everything that predates the eviction policy, or that
     * was downloaded while it was off. Unlike the scheduled sweep this asks the
     * server about each series rather than trusting the cached index, because a
     * button that does nothing the first time it is pressed is a broken button.
     *
     * Nothing is deleted that the server has not answered for, so every file
     * this removes can be read back on demand.
     */
    suspend fun reclaimNow(): Result {
        val result = walk(Mode.ON_DEMAND) { examined, evicted, freed ->
            _state.value = State.Running(examined, evicted, freed)
        }
        _state.value = State.Done(result.evicted, result.freedBytes)
        return result
    }

    /**
     * The same, for one series. Called as a bulk upload finishes each series so
     * that peak disk use stays at one series rather than the whole library.
     */
    suspend fun reclaimSeries(sourceDirName: String, mangaDirName: String): Result =
        walk(Mode.AFTER_BULK_UPLOAD, only = sourceDirName to mangaDirName)

    fun resetState() {
        _state.value = State.Idle
    }

    private suspend fun walk(
        mode: Mode,
        only: Pair<String, String>? = null,
        onProgress: ((examined: Int, evicted: Int, freed: Long) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        val nothing = Result(0, 0, 0L)
        if (!mirror.isEnabled) return@withContext nothing

        val timing = preferences.evictWhen.get()
        when (mode) {
            // The user asking for space back is its own authority; the
            // preference governs what happens without being asked.
            Mode.ON_DEMAND -> Unit
            Mode.AFTER_BULK_UPLOAD -> if (!preferences.evictAfterUpload.get()) return@withContext nothing
            Mode.SCHEDULED -> {
                if (!preferences.evictAfterUpload.get()) return@withContext nothing
                // Nothing deferred to do: that timing is handled the moment the
                // upload verifies.
                if (timing == EvictionTiming.AFTER_UPLOAD) return@withContext nothing
            }
        }

        val graceMillis = preferences.evictAfterDays.get().days.inWholeMilliseconds
        val now = System.currentTimeMillis()

        var evicted = 0
        var examined = 0
        var freed = 0L

        for (manga in getFavorites.await()) {
            val source = sourceManager.getOrStub(manga.source)
            val sourceDirName = provider.getSourceDirName(source)
            val mangaDirName = provider.getMangaDirName(manga.title)

            if (only != null && only != sourceDirName to mangaDirName) continue

            val mangaDir = provider.findMangaDir(manga.title, source) ?: continue
            if (keepStore.isSeriesKept(sourceDirName, mangaDirName)) continue

            // One PROPFIND for the series, then a set lookup per chapter. Doing
            // this the other way round would be one request per chapter. The
            // scheduled sweep makes do with whatever the index already holds,
            // since it will come back around; the other two cannot.
            if (mode == Mode.ON_DEMAND && index.refreshNow(sourceDirName, mangaDirName) == null) continue

            for (chapter in getChaptersByMangaId.await(manga.id)) {
                // Older downloads are named without the URL hash, on the
                // device and on the server alike, so both lookups take the
                // whole candidate list.
                val fileNames = provider.remoteChapterFileNames(chapter.name, chapter.scanlator, chapter.url)
                val file = fileNames.firstNotNullOfOrNull { mangaDir.findFile(it) } ?: continue

                examined++

                val segments = mirror.segmentsFor(sourceDirName, mangaDirName, fileNames)
                if (keepStore.isKept(segments)) continue

                // Never evict something the server has not been confirmed to
                // hold. A miss here schedules a refresh and returns false, so
                // the worst case is that this chapter waits for the next sweep.
                if (!index.contains(sourceDirName, mangaDirName, fileNames)) continue

                val due = when {
                    mode == Mode.ON_DEMAND -> true
                    timing == EvictionTiming.AFTER_UPLOAD -> true
                    timing == EvictionTiming.AFTER_READING -> chapter.read
                    else -> now - file.lastModified() >= graceMillis
                }
                if (!due) continue

                val size = file.length()
                if (!mirror.evictLocalCopy(file, chapter.id, manga.id)) continue
                evicted++
                freed += size.coerceAtLeast(0L)
                onProgress?.invoke(examined, evicted, freed)
            }
        }

        if (evicted > 0) {
            logcat(LogPriority.INFO) { "$mode freed $freed bytes over $evicted of $examined local copies" }
        }
        Result(evicted, examined, freed)
    }
}
