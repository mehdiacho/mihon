package mihon.data.lansync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What this phone offers a puller: its downloaded chapters and a fresh backup.
 *
 * Only a file listed in the manifest last built here can be fetched. A request is matched against
 * that list by its exact path, never resolved against the storage, so a crafted path has nothing
 * to walk.
 */
@Inject
@SingleIn(AppScope::class)
class LanSyncCatalog(
    private val context: Context,
    private val preferences: LanSyncPreferences,
    private val getFavorites: GetFavorites,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val sourceManager: SourceManager,
    private val downloadProvider: DownloadProvider,
    private val backupCreatorFactory: BackupCreator.Factory,
) {

    private class Entry(val file: LanSyncProtocol.ManifestFile, val source: UniFile)

    private val mutex = Mutex()
    private var entries: Map<String, Entry> = emptyMap()

    private var backupFile: File? = null
    private var backupBuiltAt = 0L

    suspend fun manifest(deviceName: String): LanSyncProtocol.Manifest = mutex.withLock {
        val backup = freshBackup()
        entries = scanDownloads()
        LanSyncProtocol.Manifest(
            deviceId = preferences.deviceId,
            deviceName = deviceName,
            backupSize = backup.length(),
            files = entries.values.map { it.file },
        )
    }

    suspend fun backup(): File = mutex.withLock { freshBackup() }

    /** The manifest entry for [path], building the manifest first if nobody has asked for one yet. */
    suspend fun find(path: String): Pair<LanSyncProtocol.ManifestFile, UniFile>? {
        if (!LanSyncProtocol.isSafePath(path)) return null
        val known = mutex.withLock {
            if (entries.isEmpty()) entries = scanDownloads()
            entries[path]
        } ?: return null
        return known.file to known.source
    }

    /** Writes a chapter: an archive as it is, a folder of images zipped as it goes. */
    fun write(file: LanSyncProtocol.ManifestFile, source: UniFile, out: OutputStream) {
        if (!file.packed) {
            source.openInputStream().use { it.copyTo(out) }
            return
        }
        // Images are already compressed, so storing them costs nothing and saves the phone the work
        val zip = ZipOutputStream(out)
        zip.setLevel(Deflater.NO_COMPRESSION)
        source.listFiles().orEmpty()
            .filter { it.isFile && it.name != null }
            .sortedBy { it.name }
            .forEach { image ->
                zip.putNextEntry(ZipEntry(image.name!!))
                image.openInputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        zip.finish()
    }

    /**
     * A backup at most [BACKUP_MAX_AGE_MS] old. A puller asks for the manifest and then the backup,
     * and building one takes long enough that doing it twice in a row would be noticed.
     */
    private suspend fun freshBackup(): File {
        backupFile?.takeIf { it.exists() && System.currentTimeMillis() - backupBuiltAt < BACKUP_MAX_AGE_MS }
            ?.let { return it }

        val dir = File(context.cacheDir, "lansync").apply { mkdirs() }
        val file = File(dir, "backup.tachibk")
        file.delete()
        file.createNewFile()
        // Private settings stay out: this goes to whoever has the pairing code
        backupCreatorFactory.create(isAutoBackup = false).backup(Uri.fromFile(file), BackupOptions())
        backupFile = file
        backupBuiltAt = System.currentTimeMillis()
        return file
    }

    private suspend fun scanDownloads(): Map<String, Entry> {
        val start = System.currentTimeMillis()
        val sourceDirs = downloadProvider.listSourceDirs()
        val mangaDirsBySource = HashMap<String, Map<String, UniFile>>()
        val result = LinkedHashMap<String, Entry>()

        val mangas = getFavorites.await() + mangaRepository.getReadMangaNotInLibrary()
        for (manga in mangas.distinctBy { it.id }) {
            val sourceDirName = downloadProvider.getSourceDirName(sourceManager.getOrStub(manga.source))
            val mangaDirs = mangaDirsBySource.getOrPut(sourceDirName) {
                sourceDirs[sourceDirName]?.listFiles()
                    ?.mapNotNull { dir -> dir.name?.let { it to dir } }
                    ?.toMap()
                    .orEmpty()
            }
            val mangaDirName = downloadProvider.getMangaDirName(manga.title)
            val mangaDir = mangaDirs[mangaDirName] ?: continue
            val onDisk = describeChildren(mangaDir).takeIf { it.isNotEmpty() } ?: continue

            for (chapter in chapterRepository.getChapterByMangaId(manga.id)) {
                val names = downloadProvider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                val child = names.firstNotNullOfOrNull { onDisk[it] } ?: continue

                val path = "${LanSyncProtocol.DOWNLOADS_ROOT}$sourceDirName/$mangaDirName/" +
                    if (child.isDirectory) "${child.name}.cbz" else child.name
                // A name the path check would refuse can't be fetched, so don't offer it
                if (!LanSyncProtocol.isSafePath(path)) continue

                result[path] = Entry(
                    LanSyncProtocol.ManifestFile(
                        path = path,
                        // Unknown for a folder: adding up its images costs one storage query each
                        size = if (child.isDirectory) 0 else child.size,
                        lastModified = child.lastModified,
                        sourceId = manga.source.toString(),
                        mangaUrl = manga.url,
                        chapterUrl = chapter.url,
                        packed = child.isDirectory,
                    ),
                    child.file,
                )
            }
        }
        logcat(LogPriority.INFO) {
            "LAN sync manifest: ${result.size} chapters in ${System.currentTimeMillis() - start} ms"
        }
        return result
    }

    private class Child(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val file: UniFile,
    )

    /**
     * A folder's children with their type, size and date. On a storage tree each of those is a
     * separate query per file when asked through UniFile, which for a library of image-folder
     * chapters is minutes; asking the provider for the columns once per folder is one query.
     */
    private fun describeChildren(dir: UniFile): Map<String, Child> {
        val files = dir.listFiles().orEmpty().mapNotNull { file -> file.name?.let { it to file } }.toMap()
        if (files.isEmpty()) return emptyMap()

        val uri = dir.uri
        if (uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                uri,
                DocumentsContract.getDocumentId(uri),
            )
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val described = runCatching {
                context.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
                    buildMap {
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(0) ?: continue
                            val file = files[name] ?: continue
                            put(
                                name,
                                Child(
                                    name = name,
                                    isDirectory = cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR,
                                    size = cursor.getLong(2),
                                    lastModified = cursor.getLong(3),
                                    file = file,
                                ),
                            )
                        }
                    }
                }
            }.getOrNull()
            if (described != null) return described
        }

        return files.mapValues { (name, file) ->
            Child(name, file.isDirectory, file.length(), file.lastModified(), file)
        }
    }

    private companion object {
        const val BACKUP_MAX_AGE_MS = 10 * 60_000L
    }
}
