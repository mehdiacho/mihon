package eu.kanade.tachiyomi.data.backup.create.creators

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.toBackupChapter
import eu.kanade.tachiyomi.data.backup.models.toBackupTracking
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import logcat.LogPriority
import mihon.core.common.extensions.toByteArray
import mihon.core.common.extensions.toEpochMillisOrZero
import mihon.data.remote.RemoteMirror
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.repository.TrackRepository

@Inject
class MangaBackupCreator(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val categoryRepository: CategoryRepository,
    private val historyRepository: HistoryRepository,
    private val trackRepository: TrackRepository,
    private val downloadProvider: DownloadProvider,
    private val remoteMirror: RemoteMirror,
    private val sourceManager: SourceManager,
) {

    /**
     * Builds the entries a chunk at a time, so only one chunk's chapters are held at once.
     */
    operator fun invoke(mangas: List<Manga>, options: BackupOptions): Flow<BackupManga> = flow {
        val downloadState = DownloadState()
        mangas.chunked(CHUNK_SIZE).forEach { chunk ->
            backupMangas(chunk, options, downloadState).forEach { emit(it) }
        }
        logcat(LogPriority.INFO) {
            "Read download state for ${mangas.size} entries in ${downloadState.elapsedMs} ms"
        }
    }

    private suspend fun backupMangas(
        mangas: List<Manga>,
        options: BackupOptions,
        downloadState: DownloadState,
    ): List<BackupManga> {
        val mangaIds = mangas.map { it.id }

        val excludedScanlatorsByMangaId = mangaRepository.getExcludedScanlatorsByMangaIds(mangaIds)
        // History is backed up by chapter URL, so it needs the chapters too
        val chaptersByMangaId = if (options.chapters || options.history) {
            chapterRepository.getChaptersByMangaIds(mangaIds)
        } else {
            emptyMap()
        }
        val categoriesByMangaId = if (options.categories) {
            categoryRepository.getCategoriesByMangaIds(mangaIds)
        } else {
            emptyMap()
        }
        val tracksByMangaId = if (options.tracking) trackRepository.getTracksByMangaIds(mangaIds) else emptyMap()
        val historyByMangaId = if (options.history) historyRepository.getHistoryByMangaIds(mangaIds) else emptyMap()

        return mangas.map { manga ->
            val mangaObject = manga.toBackupManga()
            val chapters = chaptersByMangaId[manga.id].orEmpty()

            mangaObject.excludedScanlators = excludedScanlatorsByMangaId[manga.id].orEmpty()

            if (options.chapters) {
                mangaObject.chapters = chapters.map { it.toBackupChapter() }
                    .also { if (it.isNotEmpty()) downloadState.mark(manga, it) }
            }

            if (options.categories) {
                mangaObject.categories = categoriesByMangaId[manga.id].orEmpty().map { it.order }
            }

            if (options.tracking) {
                mangaObject.tracking = tracksByMangaId[manga.id].orEmpty().map { it.toBackupTracking() }
            }

            if (options.history) {
                val chapterUrlsById = chapters.associate { it.id to it.url }
                mangaObject.history = historyByMangaId[manga.id].orEmpty().mapNotNull { history ->
                    // A chapter removed since its history was read takes that history with it
                    val url = chapterUrlsById[history.chapterId] ?: return@mapNotNull null
                    BackupHistory(url, history.readAt.toEpochMillisOrZero(), history.readDuration)
                }
            }

            mangaObject
        }
    }

    /**
     * Where each chapter is kept, worked out once per series rather than once per chapter. Most of
     * a large library has nothing downloaded and nothing on the server, and asking about each of
     * those chapters anyway is what made a backup take minutes.
     *
     * Reads the downloads folder rather than the download cache: the cache fills in the background
     * and a backup job can easily run before it has, which would record nothing as downloaded.
     * Each folder is listed at most once per backup.
     *
     * The server copy comes from the saved server index only. A backup never asks the server: an
     * automatic backup asking once per series would be hundreds of requests, so a series the index
     * hasn't listed yet is recorded as not on the server.
     */
    private inner class DownloadState {
        private val sourceDirs by lazy { downloadProvider.listSourceDirs() }
        private val mangaDirs = HashMap<String, Map<String, UniFile>>()

        var elapsedMs = 0L
            private set

        suspend fun mark(manga: Manga, chapters: List<BackupChapter>) {
            val start = System.currentTimeMillis()
            val source = sourceManager.getOrStub(manga.source)
            val sourceDirName = downloadProvider.getSourceDirName(source)
            val mangaDirName = downloadProvider.getMangaDirName(manga.title)

            val onDisk = mangaDirsOf(sourceDirName)[mangaDirName]
                ?.listFiles()
                ?.mapNotNullTo(HashSet()) { it.name }
                .orEmpty()
            val onServer = remoteMirror.knownFor(sourceDirName, mangaDirName).orEmpty()

            if (onDisk.isNotEmpty() || onServer.isNotEmpty()) {
                chapters.forEach { chapter ->
                    val names = downloadProvider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                    chapter.downloaded = names.any { it in onDisk }
                    // Only archives are uploaded, so only an archive name can be on the server
                    chapter.onServer = names.any { it.endsWith(".cbz") && it in onServer }
                }
            }
            elapsedMs += System.currentTimeMillis() - start
        }

        private fun mangaDirsOf(sourceDirName: String): Map<String, UniFile> =
            mangaDirs.getOrPut(sourceDirName) {
                sourceDirs[sourceDirName]?.listFiles()
                    ?.mapNotNull { dir -> dir.name?.let { it to dir } }
                    ?.toMap()
                    .orEmpty()
            }
    }

    private companion object {
        const val CHUNK_SIZE = 100
    }
}

private fun Manga.toBackupManga() =
    BackupManga(
        url = this.url,
        title = this.title,
        artist = this.artist,
        author = this.author,
        description = this.description,
        genre = this.genre.orEmpty(),
        status = this.status.toInt(),
        thumbnailUrl = this.thumbnailUrl,
        favorite = this.favorite,
        source = this.source,
        dateAdded = this.favoriteAt.toEpochMillisOrZero(),
        viewer = (this.viewerFlags.toInt() and ReadingMode.MASK),
        viewer_flags = this.viewerFlags.toInt(),
        chapterFlags = this.chapterFlags.toInt(),
        updateStrategy = this.updateStrategy,
        notes = this.notes,
        initialized = this.initialized,
        memo = this.memo.toByteArray(),
        nextUpdate = this.nextUpdate.toEpochMillisOrZero(),
    )
