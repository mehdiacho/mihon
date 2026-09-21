package mihon.data.remote

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.days

/**
 * Applies the deferred eviction policies.
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

    data class Result(val evicted: Int, val examined: Int)

    suspend fun sweep(): Result {
        if (!mirror.isEnabled || !preferences.evictAfterUpload.get()) return Result(0, 0)

        val timing = preferences.evictWhen.get()
        if (timing == EvictionTiming.AFTER_UPLOAD) return Result(0, 0)

        val graceMillis = preferences.evictAfterDays.get().days.inWholeMilliseconds
        val now = System.currentTimeMillis()

        var evicted = 0
        var examined = 0

        for (manga in getFavorites.await()) {
            val source = sourceManager.getOrStub(manga.source)
            val sourceDirName = provider.getSourceDirName(source)
            val mangaDirName = provider.getMangaDirName(manga.title)

            // One PROPFIND for the series, then a set lookup per chapter. Doing
            // this the other way round would be one request per chapter.
            val mangaDir = provider.findMangaDir(manga.title, source) ?: continue
            if (keepStore.isSeriesKept(sourceDirName, mangaDirName)) continue

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

                val due = when (timing) {
                    EvictionTiming.AFTER_READING -> chapter.read
                    EvictionTiming.AFTER_DAYS -> now - file.lastModified() >= graceMillis
                    EvictionTiming.AFTER_UPLOAD -> true
                }
                if (!due) continue

                mirror.evictLocalCopy(file, chapter.id, manga.id)
                evicted++
            }
        }

        if (evicted > 0) {
            logcat(LogPriority.INFO) { "Remote maintenance evicted $evicted of $examined local copies" }
        }
        return Result(evicted, examined)
    }
}
