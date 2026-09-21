package mihon.data.remote

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import java.io.File
import java.security.MessageDigest

/**
 * Mirrors finished chapter archives to a remote server, and fetches them back.
 *
 * Uploads run on their own single-consumer queue rather than inline in
 * [eu.kanade.tachiyomi.data.download.Downloader]. A slow or unreachable server
 * must never stall the download queue, and an upload that fails is not a
 * download that failed -- the local copy is already good.
 */
@Inject
@SingleIn(AppScope::class)
class RemoteMirror(
    private val context: Context,
    private val preferences: RemoteStoragePreferences,
    private val clientProvider: RemoteClientProvider,
    private val index: RemoteIndex,
    private val uploadQueue: RemoteUploadQueue,
    private val keepStore: RemoteKeepStore,
    private val downloadCache: DownloadCache,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("RemoteMirror"))

    /**
     * Pre-fetches run in the process, on a queue of their own: they are only
     * worth doing while the user is actually reading, so unlike an upload there
     * is nothing to salvage if the process dies.
     */
    private val prefetchQueue = Channel<List<String>>(Channel.UNLIMITED)

    /** How many uploads are outstanding, for the settings screen. */
    val pendingUploads = uploadQueue.size

    private val _activeFetch = MutableStateFlow<FetchProgress?>(null)

    /** The fetch currently in progress, if any, so the reader can show it. */
    val activeFetch = _activeFetch.asStateFlow()

    data class FetchProgress(val name: String, val bytesRead: Long, val total: Long)

    private val cacheDir: File
        get() = File(context.cacheDir, CACHE_DIR_NAME)

    init {
        // Anything left in the queue outlived the process that created it, so
        // nothing else is going to pick it up.
        retryPendingUploads()
        RemoteMaintenanceJob.setupTask(context)

        scope.launch {
            for (segments in prefetchQueue) {
                runCatching { fetch(segments) }
                    .onFailure { logcat(LogPriority.WARN, it) { "Mirror prefetch failed: $segments" } }
            }
        }
    }

    val isEnabled: Boolean
        get() = preferences.enabled.get() && preferences.isConfigured

    /** Whether any policy would remove local copies of mirrored chapters. */
    val evictsLocalCopies: Boolean
        get() = preferences.evictAfterUpload.get()

    /** Whether a local delete should take the remote copy with it. */
    val deletesPropagate: Boolean
        get() = preferences.role.get() == RemoteRole.MIRROR

    val redownloadFromRemote: Boolean
        get() = preferences.redownloadFrom.get() == RedownloadSource.REMOTE

    /**
     * Builds the remote path for a chapter. Mirrors the local layout
     * (`<source>/<manga>/<chapter>.cbz`) so that a Komga library pointed at the
     * remote root sees one series per manga directory, which is how Komga
     * decides what a series is.
     */
    fun segmentsFor(sourceDirName: String, mangaDirName: String, chapterFileName: String): List<String> =
        listOf(sourceDirName, mangaDirName, chapterFileName)

    /**
     * Same, but preferring the name the remote already holds the chapter under.
     * Falls back to the first candidate, which is the name an upload writes.
     */
    fun segmentsFor(sourceDirName: String, mangaDirName: String, chapterFileNames: List<String>): List<String> {
        val name = index.resolve(sourceDirName, mangaDirName, chapterFileNames)
            ?: chapterFileNames.firstOrNull()
            ?: return emptyList()
        return listOf(sourceDirName, mangaDirName, name)
    }

    /**
     * Records [file] as needing upload and asks WorkManager to drain the queue.
     *
     * Returns immediately and the caller is not told whether the upload
     * succeeded, by design: the download is already complete and correct.
     *
     * Note that the Wi-Fi preference is deliberately not checked here. It is a
     * constraint on the worker instead, so a chapter downloaded on mobile data
     * is still queued and uploads when the phone next reaches Wi-Fi, rather
     * than being silently dropped.
     */
    fun enqueue(file: UniFile, segments: List<String>, chapterId: Long, mangaId: Long) {
        if (!isEnabled) return
        uploadQueue.add(
            RemoteUploadQueue.Entry(
                segments = segments,
                fileUri = file.uri.toString(),
                chapterId = chapterId,
                mangaId = mangaId,
            ),
        )
        RemoteUploadJob.start(context, preferences.onlyOverWifi.get())
    }

    /**
     * Kicks the upload worker. Used when the queue may have stalled -- opening
     * the settings screen, or switching mirroring back on.
     */
    fun retryPendingUploads() {
        if (!isEnabled || uploadQueue.isEmpty()) return
        RemoteUploadJob.start(context, preferences.onlyOverWifi.get())
    }

    /** Whether a chapter is exempt from eviction because the user pinned it. */
    fun isKept(segments: List<String>): Boolean = keepStore.isKept(segments)

    fun keep(segments: List<String>) = keepStore.keepChapter(segments)

    fun release(segments: List<String>) = keepStore.release(segments)

    /**
     * Applies [RemoteStoragePreferences.manualDownloadKeep] to a chapter the
     * user asked for by hand.
     */
    fun onManualDownload(segments: List<String>) {
        if (!isEnabled || !preferences.evictAfterUpload.get()) return
        if (segments.size < 3) return
        when (preferences.manualDownloadKeep.get()) {
            ManualDownloadKeep.CHAPTER -> keepStore.keepChapter(segments)
            ManualDownloadKeep.SERIES -> keepStore.keepSeries(segments[0], segments[1])
            ManualDownloadKeep.NONE -> Unit
        }
    }

    /**
     * Asks for [segments] to be in the local cache soon. Used for read-ahead, so
     * it is deliberately best-effort and silent.
     */
    fun prefetch(segments: List<String>) {
        if (!isEnabled || !backgroundTransfersAllowed()) return
        if (cacheFileFor(segments).length() > 0L) return
        prefetchQueue.trySend(segments)
    }

    /** How many chapters ahead of the one being read to pre-fetch. */
    val readAheadCount: Int
        get() = preferences.readAheadChapters.get()

    /** Whether the remote is known to hold this chapter. See [RemoteIndex.contains]. */
    fun holds(segments: List<String>): Boolean {
        if (segments.size < 3) return false
        return index.contains(segments[0], segments[1], listOf(segments[2]))
    }

    /**
     * Downloads the chapter at [segments] into the local cache and returns it,
     * or null if the remote does not have it. Blocking: callers are already on
     * an IO dispatcher.
     *
     * A user opening a chapter is a deliberate act, so this ignores the Wi-Fi
     * preference -- that gate is for work the user did not ask for.
     */
    fun fetch(segments: List<String>): File? {
        if (!isEnabled) return null

        val cached = cacheFileFor(segments)
        if (cached.length() > 0L) {
            cached.setLastModified(System.currentTimeMillis())
            return cached
        }

        val client = clientProvider.get() ?: return null
        val remoteSize = client.sizeOf(segments) ?: return null

        cacheDir.mkdirs()
        // Download to a scratch name and rename on success, so a cancelled or
        // failed fetch can never leave a truncated CBZ that looks complete.
        val partial = File(cached.parentFile, cached.name + ".part")
        val label = segments.last()
        client.get(segments, partial) { read, total ->
            _activeFetch.value = FetchProgress(label, read, if (total > 0) total else remoteSize)
        }.getOrElse {
            _activeFetch.value = null
            partial.delete()
            logcat(LogPriority.WARN, it) { "Mirror fetch failed: $segments" }
            return null
        }
        _activeFetch.value = null

        if (remoteSize > 0L && partial.length() != remoteSize) {
            logcat(LogPriority.WARN) { "Short fetch of $segments: got ${partial.length()} of $remoteSize" }
            partial.delete()
            return null
        }

        if (!partial.renameTo(cached)) {
            partial.delete()
            return null
        }

        trimCache()
        return cached
    }

    /**
     * Deletes the remote copy. Used both by the explicit "delete remote copy"
     * action and, when the remote is a mirror rather than an archive, by an
     * ordinary download delete.
     */
    fun deleteRemote(segments: List<String>) {
        if (!isEnabled) return
        scope.launch {
            val client = clientProvider.get() ?: return@launch
            client.delete(segments)
                .onSuccess {
                    index.onDeleted(segments)
                    cacheFileFor(segments).delete()
                }
                .onFailure { logcat(LogPriority.WARN, it) { "Could not delete remote copy: $segments" } }
        }
    }

    /**
     * Follows a series rename, so the remote copy moves with it instead of
     * being stranded under the old title.
     */
    fun renameManga(sourceDirName: String, oldMangaDirName: String, newMangaDirName: String) {
        if (!isEnabled) return
        scope.launch {
            val client = clientProvider.get() ?: return@launch
            client.move(listOf(sourceDirName, oldMangaDirName), listOf(sourceDirName, newMangaDirName))
                .onFailure { logcat(LogPriority.WARN, it) { "Could not move remote copy of $oldMangaDirName" } }
            index.forget(sourceDirName, oldMangaDirName)
            index.forget(sourceDirName, newMangaDirName)
        }
    }

    /**
     * Uploads one queued chapter and, on success, removes it from the queue.
     *
     * Returns false to mean "try again later" -- an unreachable server or a
     * transfer that did not verify. Returns true when there is nothing left to
     * do for this entry, including the case where the local file has since
     * disappeared because the user deleted the download.
     */
    suspend fun uploadOne(entry: RemoteUploadQueue.Entry): Boolean {
        val client = clientProvider.get() ?: return false
        val file = UniFile.fromUri(context, entry.fileUri.toUri())

        if (file == null || !file.exists()) {
            // Nothing to upload and nothing to wait for. Dropping the entry is
            // correct: the user deleted the download before it reached the
            // server, and re-creating it is not this queue's job.
            logcat(LogPriority.INFO) { "Dropping queued upload, file is gone: ${entry.segments}" }
            uploadQueue.remove(entry.segments)
            return true
        }

        val length = file.length()
        if (length <= 0L) {
            logcat(LogPriority.WARN) { "Refusing to mirror empty file: ${entry.segments}" }
            uploadQueue.remove(entry.segments)
            return true
        }

        // Skip the transfer if the server already holds a file of the same
        // size. Cheap, and makes the queue idempotent: a retry after a crash
        // costs one HEAD rather than re-sending a hundred megabytes.
        if (client.sizeOf(entry.segments) != length) {
            val put = client.put(entry.segments, length) { file.openInputStream() }
            if (put.isFailure) {
                logcat(LogPriority.WARN, put.exceptionOrNull()) { "Upload failed: ${entry.segments}" }
                return false
            }

            // Only verify-then-evict. Deleting a local copy on the strength of
            // a 2xx alone would trust the server's word over an observation.
            val remoteSize = client.sizeOf(entry.segments)
            if (remoteSize != length) {
                logcat(LogPriority.WARN) {
                    "Size mismatch after upload of ${entry.segments}: local=$length remote=$remoteSize"
                }
                return false
            }
        }

        index.onUploaded(entry.segments)
        uploadQueue.remove(entry.segments)
        maybeEvict(file, entry)
        return true
    }

    /**
     * Deletes the local copy if the policy says so, and tells [DownloadCache]
     * about it.
     *
     * The cache notification is not optional. That cache is derived from the
     * filesystem but only re-walks it hourly, so without this a chapter carries
     * on claiming to be downloaded long after its file is gone -- which is how
     * a working feature comes to look like a broken one.
     */
    private suspend fun maybeEvict(file: UniFile, entry: RemoteUploadQueue.Entry) {
        if (!preferences.evictAfterUpload.get()) return
        if (preferences.evictWhen.get() != EvictionTiming.AFTER_UPLOAD) return
        if (keepStore.isKept(entry.segments)) return

        evictLocalCopy(file, entry.chapterId, entry.mangaId)
    }

    /**
     * Removes a local archive and de-registers it. Shared by the immediate path
     * and the periodic sweep in [RemoteMaintenance].
     */
    suspend fun evictLocalCopy(file: UniFile, chapterId: Long, mangaId: Long) {
        if (!file.delete()) {
            logcat(LogPriority.WARN) { "Could not evict local copy: ${file.name}" }
            return
        }

        val manga = getManga.await(mangaId) ?: return
        val chapter = getChapter.await(chapterId) ?: return
        downloadCache.removeChapter(chapter, manga)
    }

    /**
     * Fetched chapters are named by a hash of their remote path rather than by
     * the path itself: chapter names routinely exceed what a filename can hold,
     * and the cache is never browsed by a human.
     */
    private fun cacheFileFor(segments: List<String>): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(segments.joinToString("/").toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$digest.cbz")
    }

    /** Keeps the fetch cache under budget, deleting least recently used first. */
    private fun trimCache() {
        val budget = preferences.cacheSizeMb.get().toLong() * 1024 * 1024
        val files = cacheDir.listFiles()?.takeIf { it.isNotEmpty() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= budget) return

        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= budget) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun backgroundTransfersAllowed(): Boolean =
        !preferences.onlyOverWifi.get() || context.isConnectedToWifi()

    companion object {
        private const val CACHE_DIR_NAME = "remote_mirror"

        fun chapterFileName(chapterDirName: String): String = "$chapterDirName.cbz"
    }
}

/**
 * Archive names the remote may hold this chapter under, current scheme first.
 *
 * Mihon started hashing the chapter URL into the name partway through its life
 * and [DownloadProvider.getValidChapterDirNames] knows both spellings, so reuse
 * it rather than only ever asking for the current one.
 */
fun DownloadProvider.remoteChapterFileNames(name: String, scanlator: String?, url: String): List<String> =
    getValidChapterDirNames(name, scanlator, url).filter { it.endsWith(".cbz") }
