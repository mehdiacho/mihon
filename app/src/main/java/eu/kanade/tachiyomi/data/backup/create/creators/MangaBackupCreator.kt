package eu.kanade.tachiyomi.data.backup.create.creators

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.backupChapterMapper
import eu.kanade.tachiyomi.data.backup.models.backupTrackMapper
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import logcat.LogPriority
import mihon.data.remote.RemoteMirror
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

@Inject
class MangaBackupCreator(
    private val database: Database,
    private val getCategories: GetCategories,
    private val getHistory: GetHistory,
    private val downloadProvider: DownloadProvider,
    private val remoteMirror: RemoteMirror,
    private val sourceManager: SourceManager,
) {

    suspend operator fun invoke(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        val downloadState = DownloadState()
        return mangas.map {
            backupManga(it, options, downloadState)
        }.also {
            logcat(LogPriority.INFO) {
                "Read download state for ${mangas.size} entries in ${downloadState.elapsedMs} ms"
            }
        }
    }

    private suspend fun backupManga(manga: Manga, options: BackupOptions, downloadState: DownloadState): BackupManga {
        // Entry for this manga
        val mangaObject = manga.toBackupManga()

        mangaObject.excludedScanlators = database.excluded_scanlatorsQueries
            .getExcludedScanlatorsByMangaId(manga.id)
            .awaitAsList()

        if (options.chapters) {
            // Backup all the chapters
            database.chaptersQueries
                .getChaptersByMangaId(
                    mangaId = manga.id,
                    applyScanlatorFilter = 0, // false
                    mapper = backupChapterMapper,
                )
                .awaitAsList()
                .takeUnless(List<BackupChapter>::isEmpty)
                ?.also { downloadState.mark(manga, it) }
                ?.let { mangaObject.chapters = it }
        }

        if (options.categories) {
            // Backup categories for this manga
            val categoriesForManga = getCategories.await(manga.id)
            if (categoriesForManga.isNotEmpty()) {
                mangaObject.categories = categoriesForManga.map { it.order }
            }
        }

        if (options.tracking) {
            val tracks = database.manga_syncQueries
                .getTracksByMangaId(manga.id, backupTrackMapper)
                .awaitAsList()
            if (tracks.isNotEmpty()) {
                mangaObject.tracking = tracks
            }
        }

        if (options.history) {
            val historyByMangaId = getHistory.await(manga.id)
            if (historyByMangaId.isNotEmpty()) {
                val history = historyByMangaId.map { history ->
                    val chapter = database.chaptersQueries
                        .getChapterById(history.chapterId)
                        .awaitAsOne()
                    BackupHistory(chapter.url, history.readAt?.time ?: 0L, history.readDuration)
                }
                if (history.isNotEmpty()) {
                    mangaObject.history = history
                }
            }
        }

        return mangaObject
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
        dateAdded = this.dateAdded,
        viewer = (this.viewerFlags.toInt() and ReadingMode.MASK),
        viewer_flags = this.viewerFlags.toInt(),
        chapterFlags = this.chapterFlags.toInt(),
        updateStrategy = this.updateStrategy,
        lastModifiedAt = this.lastModifiedAt,
        favoriteModifiedAt = this.favoriteModifiedAt,
        version = this.version,
        notes = this.notes,
        initialized = this.initialized,
        memo = MemoColumnAdapter.encode(this.memo),
    )
