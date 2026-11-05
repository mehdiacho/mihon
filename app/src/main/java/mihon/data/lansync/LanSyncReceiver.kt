package mihon.data.lansync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.io.InputStream

/**
 * Writes a chapter a linked PC sent with `PUT /file` into this install's downloads.
 *
 * The path is never used to walk the storage. It has to name a chapter of a series this install
 * knows, by the exact directory names [DownloadProvider] would make for it, and only then is the
 * file written, through the provider, where the downloader would have put it.
 */
@Inject
@SingleIn(AppScope::class)
class LanSyncReceiver(
    private val getFavorites: GetFavorites,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val sourceManager: SourceManager,
    private val downloadProvider: DownloadProvider,
    private val downloadCache: DownloadCache,
    private val downloadManager: DownloadManager,
) {

    sealed interface Result {
        data object Written : Result
        data object AlreadyHere : Result
        data object UnknownChapter : Result
        data class BadBody(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    // One upload at a time: they're big, and two for the same chapter must not race on the .part
    private val mutex = Mutex()

    suspend fun receive(target: LanSyncUpload.Target, length: Long, body: InputStream): Result = mutex.withLock {
        val (manga, chapter) = resolve(target) ?: return Result.UnknownChapter
        val source = sourceManager.getOrStub(manga.source)

        val existing = downloadProvider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            source,
        )
        if (existing != null) {
            // Keep the copy already here; the PC can drop its own
            return Result.AlreadyHere
        }

        val mangaDir = downloadProvider.getMangaDir(manga.title, source).getOrElse {
            return Result.Failed(it.message ?: "no download folder")
        }
        val partName = target.fileName + LanSyncUpload.PART_SUFFIX
        mangaDir.findFile(partName)?.delete()
        val part = mangaDir.createFile(partName) ?: return Result.Failed("could not create the file")

        val copy = try {
            part.openOutputStream().use { LanSyncUpload.copyExactly(body, it, length) }
        } catch (e: Exception) {
            // Most often the PC went away mid-transfer
            part.delete()
            logcat(LogPriority.WARN, e) { "LAN sync upload of ${target.fileName} cut off" }
            return Result.BadBody("connection ended")
        }
        if (copy != LanSyncUpload.Copy.COMPLETE) {
            part.delete()
            val what = if (copy == LanSyncUpload.Copy.SHORT) "shorter" else "longer"
            return Result.BadBody("body $what than Content-Length")
        }
        if (!part.renameTo(target.fileName)) {
            part.delete()
            return Result.Failed("could not rename the file")
        }

        downloadCache.addChapter(target.chapterDirName, mangaDir, manga)
        // Here now, so the phone mustn't download it again
        downloadManager.getQueuedDownloadOrNull(chapter.id)
            ?.let { downloadManager.cancelQueuedDownloads(listOf(it)) }
        logcat(LogPriority.INFO) { "LAN sync received ${manga.title} / ${chapter.name}" }
        Result.Written
    }

    /** The series and chapter whose download names are exactly [target]'s. */
    private suspend fun resolve(target: LanSyncUpload.Target): Pair<Manga, Chapter>? {
        val mangas = (getFavorites.await() + mangaRepository.getReadMangaNotInLibrary()).distinctBy { it.id }
        for (manga in mangas) {
            if (downloadProvider.getMangaDirName(manga.title) != target.mangaDir) continue
            if (downloadProvider.getSourceDirName(sourceManager.getOrStub(manga.source)) != target.sourceDir) continue
            val chapter = chapterRepository.getChapterByMangaId(manga.id).firstOrNull { chapter ->
                val names = downloadProvider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                target.fileName in names
            }
            if (chapter != null) return manga to chapter
        }
        return null
    }
}
