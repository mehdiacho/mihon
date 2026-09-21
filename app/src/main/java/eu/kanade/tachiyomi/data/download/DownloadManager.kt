package eu.kanade.tachiyomi.data.download

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import mihon.data.remote.RemoteMirror
import mihon.data.remote.remoteChapterFileNames
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR

/**
 * This class is used to manage chapter downloads in the application. It must be instantiated once
 * and retrieved through dependency injection. You can use this class to queue new chapters or query
 * downloaded chapters.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadManager(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val getCategories: GetCategories,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val sourceManager: SourceManager,
    private val downloadPreferences: DownloadPreferences,
    private val downloader: Downloader,
    private val pendingDeleter: DownloadPendingDeleter,
    private val remoteMirror: RemoteMirror,
) {

    val isRunning: Boolean
        get() = downloader.isRunning

    val queueState
        get() = downloader.queueState

    // For use by DownloadService only
    fun downloaderStart() = downloader.start()
    fun downloaderStop(reason: String? = null) = downloader.stop(reason)

    val isDownloaderRunning
        get() = DownloadJob.isRunningFlow(context)

    /**
     * Tells the downloader to begin downloads.
     */
    fun startDownloads() {
        if (downloader.isRunning) return

        if (DownloadJob.isRunning(context)) {
            downloader.start()
        } else {
            DownloadJob.start(context)
        }
    }

    /**
     * Tells the downloader to pause downloads.
     */
    fun pauseDownloads() {
        downloader.pause()
        downloader.stop()
    }

    /**
     * Empties the download queue.
     */
    fun clearQueue() {
        downloader.clearQueue()
        downloader.stop()
    }

    /**
     * Returns the download from queue if the chapter is queued for download
     * else it will return null which means that the chapter is not queued for download
     *
     * @param chapterId the chapter to check.
     */
    fun getQueuedDownloadOrNull(chapterId: Long): Download? {
        return queueState.value.find { it.chapter.id == chapterId }
    }

    fun startDownloadNow(chapterId: Long) {
        val existingDownload = getQueuedDownloadOrNull(chapterId)
        // If not in queue try to start a new download
        val toAdd = existingDownload ?: runBlocking { downloadFromChapterId(chapterId) } ?: return
        queueState.value.toMutableList().apply {
            existingDownload?.let { remove(it) }
            add(0, toAdd)
            reorderQueue(this)
        }
        startDownloads()
    }

    private suspend fun downloadFromChapterId(chapterId: Long): Download? {
        val chapter = getChapter.await(chapterId) ?: return null
        val manga = getManga.await(chapter.mangaId) ?: return null
        val source = sourceManager.get(manga.source) as? HttpSource ?: return null

        return Download(source, manga, chapter)
    }

    /**
     * Reorders the download queue.
     *
     * @param downloads value to set the download queue to
     */
    fun reorderQueue(downloads: List<Download>) {
        downloader.updateQueue(downloads)
    }

    /**
     * Tells the downloader to enqueue the given list of chapters.
     *
     * @param manga the manga of the chapters.
     * @param chapters the list of chapters to enqueue.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    suspend fun downloadChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean = true) {
        downloader.queueChapters(manga, chapters, autoStart)
    }

    /**
     * Tells the downloader to enqueue the given list of downloads at the start of the queue.
     *
     * @param downloads the list of downloads to enqueue.
     */
    fun addDownloadsToStartOfQueue(downloads: List<Download>) {
        if (downloads.isEmpty()) return
        queueState.value.toMutableList().apply {
            addAll(0, downloads)
            reorderQueue(this)
        }
        if (!DownloadJob.isRunning(context)) startDownloads()
    }

    /**
     * Builds the page list of a downloaded chapter.
     *
     * @param source the source of the chapter.
     * @param manga the manga of the chapter.
     * @param chapter the downloaded chapter.
     * @return the list of pages from the chapter.
     */
    fun buildPageList(source: Source, manga: Manga, chapter: Chapter): List<Page> {
        val chapterDir = provider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)
        val files = chapterDir?.listFiles().orEmpty()
            .filter { it.isFile && ImageUtil.isImage(it.name) { it.openInputStream() } }

        if (files.isEmpty()) {
            throw Exception(context.stringResource(MR.strings.page_list_empty_error))
        }

        return files.sortedBy { it.name }
            .mapIndexed { i, file ->
                Page(i, uri = file.uri).apply { status = Page.State.Ready }
            }
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapter.
     */
    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean {
        return cache.isChapterDownloaded(chapterName, chapterScanlator, chapterUrl, mangaTitle, sourceId)
    }

    /**
     * Returns true if the chapter is present on disk, bypassing the directory cache.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the chapter.
     */
    fun isChapterDownloadedOnDisk(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        source: Source,
    ): Boolean {
        return provider.findChapterDir(chapterName, chapterScanlator, chapterUrl, mangaTitle, source) != null
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getDownloadCount(): Int {
        return cache.getTotalDownloadCount()
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        return cache.getDownloadCount(manga)
    }

    fun cancelQueuedDownloads(downloads: List<Download>) {
        removeFromDownloadQueue(downloads.map { it.chapter })
    }

    /**
     * Deletes the directories of a list of downloaded chapters.
     *
     * @param chapters the list of chapters to delete.
     * @param manga the manga of the chapters.
     * @param source the source of the chapters.
     */
    fun deleteChapters(chapters: List<Chapter>, manga: Manga, source: Source) {
        launchIO {
            val filteredChapters = getChaptersToDelete(chapters, manga)
            if (filteredChapters.isEmpty()) {
                return@launchIO
            }

            removeFromDownloadQueue(filteredChapters)

            val (mangaDir, chapterDirs) = provider.findChapterDirs(filteredChapters, manga, source)
            chapterDirs.forEach { it.delete() }
            cache.removeChapters(filteredChapters, manga)

            // Only when the remote is configured as a mirror of the device. An
            // archive keeps what it has been given; that is the whole
            // difference between the two roles.
            if (remoteMirror.deletesPropagate) {
                deleteRemoteChapters(filteredChapters, manga, source)
            }

            // Delete manga directory if empty
            if (mangaDir?.listFiles()?.isEmpty() == true) {
                deleteManga(manga, source, removeQueued = false)
            }
        }
    }

    /**
     * Deletes the directory of a downloaded manga.
     *
     * @param manga the manga to delete.
     * @param source the source of the manga.
     * @param removeQueued whether to also remove queued downloads.
     */
    fun deleteManga(manga: Manga, source: Source, removeQueued: Boolean = true) {
        launchIO {
            if (removeQueued) {
                downloader.removeFromQueue(manga)
            }
            provider.findMangaDir(manga.title, source)?.delete()
            cache.removeManga(manga)

            // Delete source directory if empty
            val sourceDir = provider.findSourceDir(source)
            if (sourceDir?.listFiles()?.isEmpty() == true) {
                sourceDir.delete()
                cache.removeSource(source)
            }
        }
    }

    /**
     * Deletes the remote copies of [chapters], regardless of the mirror/archive
     * role. This is the explicit "delete remote copy" action, as opposed to the
     * propagation that [deleteChapters] may do on its own.
     */
    fun deleteRemoteChapters(chapters: List<Chapter>, manga: Manga, source: Source) {
        if (!remoteMirror.isEnabled) return
        val sourceDirName = provider.getSourceDirName(source)
        val mangaDirName = provider.getMangaDirName(manga.title)
        chapters.forEach { chapter ->
            val fileNames = provider.remoteChapterFileNames(chapter.name, chapter.scanlator, chapter.url)
            remoteMirror.deleteRemote(remoteMirror.segmentsFor(sourceDirName, mangaDirName, fileNames))
        }
    }

    /**
     * Drops the chapters the server already holds.
     *
     * Asks the server about the series first: the index is lazily filled, so
     * without this a library the user has never opened would look entirely
     * absent from the server and every chapter would download. If the server
     * cannot be reached the cached index is used as-is, which errs towards
     * downloading rather than towards silently doing nothing.
     */
    suspend fun filterNotOnRemote(chapters: List<Chapter>, manga: Manga, source: Source): List<Chapter> {
        if (!remoteMirror.isEnabled || chapters.isEmpty()) return chapters
        val sourceDirName = provider.getSourceDirName(source)
        val mangaDirName = provider.getMangaDirName(manga.title)
        if (!remoteMirror.refreshHolds(sourceDirName, mangaDirName)) {
            logcat(LogPriority.WARN) { "Could not ask the server about ${manga.title}; using what is cached" }
        }
        return chapters.filterNot { isChapterOnRemote(it, manga, source) }
    }

    /** Whether the remote is known to hold this chapter. */
    fun isChapterOnRemote(chapter: Chapter, manga: Manga, source: Source): Boolean {
        if (!remoteMirror.isEnabled) return false
        return remoteMirror.holds(remoteSegments(chapter, manga, source))
    }

    /**
     * Whether this chapter is exempt from automatic eviction.
     *
     * Null means the question does not arise -- nothing is set to remove local
     * copies -- so the UI can leave the option out rather than offering a pin
     * against a policy that does not exist.
     */
    fun isChapterKeptOnDevice(chapter: Chapter, manga: Manga, source: Source): Boolean? {
        if (!remoteMirror.isEnabled || !remoteMirror.evictsLocalCopies) return null
        return remoteMirror.isKept(remoteSegments(chapter, manga, source))
    }

    fun keepChaptersOnDevice(chapters: List<Chapter>, manga: Manga, source: Source) {
        if (!remoteMirror.isEnabled) return
        chapters.forEach { remoteMirror.keep(remoteSegments(it, manga, source)) }
    }

    fun allowChapterRemoval(chapters: List<Chapter>, manga: Manga, source: Source) {
        if (!remoteMirror.isEnabled) return
        chapters.forEach { remoteMirror.release(remoteSegments(it, manga, source)) }
    }

    /**
     * Records that the user asked for these chapters by hand, so the configured
     * manual-download policy can spare them from eviction.
     */
    fun onManualDownload(chapters: List<Chapter>, manga: Manga, source: Source) {
        if (!remoteMirror.isEnabled) return
        chapters.forEach { remoteMirror.onManualDownload(remoteSegments(it, manga, source)) }
    }

    private fun remoteSegments(chapter: Chapter, manga: Manga, source: Source): List<String> {
        return remoteMirror.segmentsFor(
            provider.getSourceDirName(source),
            provider.getMangaDirName(manga.title),
            provider.remoteChapterFileNames(chapter.name, chapter.scanlator, chapter.url),
        )
    }

    private fun removeFromDownloadQueue(chapters: List<Chapter>) {
        val wasRunning = downloader.isRunning
        if (wasRunning) {
            downloader.pause()
        }

        downloader.removeFromQueue(chapters)

        if (wasRunning) {
            if (queueState.value.isEmpty()) {
                downloader.stop()
            } else if (queueState.value.isNotEmpty()) {
                downloader.start()
            }
        }
    }

    /**
     * Adds a list of chapters to be deleted later.
     *
     * @param chapters the list of chapters to delete.
     * @param manga the manga of the chapters.
     */
    suspend fun enqueueChaptersToDelete(chapters: List<Chapter>, manga: Manga) {
        pendingDeleter.addChapters(getChaptersToDelete(chapters, manga), manga)
    }

    /**
     * Triggers the execution of the deletion of pending chapters.
     */
    suspend fun deletePendingChapters() {
        val pendingChapters = pendingDeleter.getPendingChapters()
        for ((manga, chapters) in pendingChapters) {
            val source = sourceManager.get(manga.source) ?: continue
            deleteChapters(chapters, manga, source)
        }
    }

    /**
     * Renames source download folder
     *
     * @param oldSource the old source.
     * @param newSource the new source.
     */
    fun renameSource(oldSource: Source, newSource: Source) {
        val oldFolder = provider.findSourceDir(oldSource) ?: return
        val newName = provider.getSourceDirName(newSource)

        if (oldFolder.name == newName) return

        val capitalizationChanged = oldFolder.name.equals(newName, ignoreCase = true)
        if (capitalizationChanged) {
            val tempName = newName + Downloader.TMP_DIR_SUFFIX
            if (!oldFolder.renameTo(tempName)) {
                logcat(LogPriority.ERROR) { "Failed to rename source download folder: ${oldFolder.name}" }
                return
            }
        }

        if (!oldFolder.renameTo(newName)) {
            logcat(LogPriority.ERROR) { "Failed to rename source download folder: ${oldFolder.name}" }
        }
    }

    /**
     * Renames manga download folder
     *
     * @param manga the manga
     * @param newTitle the new manga title.
     */
    suspend fun renameManga(manga: Manga, newTitle: String) {
        val source = sourceManager.getOrStub(manga.source)
        val oldFolder = provider.findMangaDir(manga.title, source) ?: return
        val newName = provider.getMangaDirName(newTitle)

        if (oldFolder.name == newName) return

        // just to be safe, don't allow downloads for this manga while renaming it
        downloader.removeFromQueue(manga)

        val capitalizationChanged = oldFolder.name.equals(newName, ignoreCase = true)
        if (capitalizationChanged) {
            val tempName = newName + Downloader.TMP_DIR_SUFFIX
            if (!oldFolder.renameTo(tempName)) {
                logcat(LogPriority.ERROR) { "Failed to rename manga download folder: ${oldFolder.name}" }
                return
            }
        }

        if (oldFolder.renameTo(newName)) {
            cache.renameManga(manga, oldFolder, newTitle)
            // Otherwise the remote copy is stranded under the old title and
            // nothing will ever look for it again.
            remoteMirror.renameManga(provider.getSourceDirName(source), oldFolder.name.orEmpty(), newName)
        } else {
            logcat(LogPriority.ERROR) { "Failed to rename manga download folder: ${oldFolder.name}" }
        }
    }

    /**
     * Renames an already downloaded chapter
     *
     * @param source the source of the manga.
     * @param manga the manga of the chapter.
     * @param oldChapter the existing chapter with the old name.
     * @param newChapter the target chapter with the new name.
     */
    suspend fun renameChapter(source: Source, manga: Manga, oldChapter: Chapter, newChapter: Chapter) {
        val oldNames = provider.getValidChapterDirNames(oldChapter.name, oldChapter.scanlator, oldChapter.url)
        val mangaDir = provider.getMangaDir(manga.title, source).getOrElse { e ->
            logcat(LogPriority.ERROR, e) { "Manga download folder doesn't exist. Skipping renaming after source sync" }
            return
        }

        // Assume there's only 1 version of the chapter name formats present
        val oldDownload = oldNames.asSequence()
            .mapNotNull { mangaDir.findFile(it) }
            .firstOrNull() ?: return

        var newName = provider.getChapterDirName(newChapter.name, newChapter.scanlator, newChapter.url)
        if (oldDownload.isFile && oldDownload.extension == "cbz") {
            newName += ".cbz"
        }

        if (oldDownload.name == newName) return

        if (oldDownload.renameTo(newName)) {
            cache.removeChapter(oldChapter, manga)
            cache.addChapter(newName, mangaDir, manga)
        } else {
            logcat(LogPriority.ERROR) { "Could not rename downloaded chapter: ${oldNames.joinToString()}" }
        }
    }

    private suspend fun getChaptersToDelete(chapters: List<Chapter>, manga: Manga): List<Chapter> {
        // Retrieve the categories that are set to exclude from being deleted on read
        val categoriesToExclude = downloadPreferences.removeExcludeCategories.get().map(String::toLong)

        val categoriesForManga = getCategories.await(manga.id)
            .map { it.id }
            .ifEmpty { listOf(0) }
        val filteredCategoryManga = if (categoriesForManga.intersect(categoriesToExclude).isNotEmpty()) {
            chapters.filterNot { it.read }
        } else {
            chapters
        }

        return if (!downloadPreferences.removeBookmarkedChapters.get()) {
            filteredCategoryManga.filterNot { it.bookmark }
        } else {
            filteredCategoryManga
        }
    }

    fun statusFlow(): Flow<Download> = queueState
        .flatMapLatest { downloads ->
            downloads
                .map { download ->
                    download.statusFlow.drop(1).map { download }
                }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value.filter { download -> download.status == Download.State.DOWNLOADING }.asFlow(),
            )
        }

    fun progressFlow(): Flow<Download> = queueState
        .flatMapLatest { downloads ->
            downloads
                .map { download ->
                    download.progressFlow.drop(1).map { download }
                }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value.filter { download -> download.status == Download.State.DOWNLOADING }
                    .asFlow(),
            )
        }
}
