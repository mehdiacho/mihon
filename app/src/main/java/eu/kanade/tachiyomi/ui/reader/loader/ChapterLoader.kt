package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import mihon.data.remote.RemoteMirror
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterCache: ChapterCache,
    private val manga: Manga,
    private val source: Source,
    private val remoteMirror: RemoteMirror,
) {

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter) {
        if (chapterIsReady(chapter)) {
            return
        }

        chapter.state = ReaderChapter.State.Loading
        withIOContext {
            logcat { "Loading pages for ${chapter.chapter.name}" }
            try {
                val loader = getPageLoader(chapter)
                chapter.pageLoader = loader

                val pages = loader.getPages()
                    .onEach { it.chapter = chapter }

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }

                // If the chapter is partially read, set the starting page to the last the user read
                // otherwise use the requested page.
                if (!chapter.chapter.read) {
                    chapter.requestedPage = chapter.chapter.last_page_read
                }

                chapter.state = ReaderChapter.State.Loaded(pages)
            } catch (e: Throwable) {
                chapter.state = ReaderChapter.State.Error(e)
                throw e
            }
        }
    }

    /**
     * Checks [chapter] to be loaded based on present pages and loader in addition to state.
     */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean {
        return chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null
    }

    /**
     * Returns the page loader to use for this [chapter].
     */
    private fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val dbChapter = chapter.chapter
        val isDownloaded = downloadManager.isChapterDownloadedOnDisk(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            source,
        )
        val mirrored = if (isDownloaded) null else fetchFromMirror(chapter)
        return when {
            isDownloaded -> DownloadPageLoader(
                chapter,
                manga,
                source,
                downloadManager,
                downloadProvider,
            )
            // A chapter that was evicted after being mirrored is still a
            // downloaded chapter as far as the reader is concerned; it just
            // lives on the server until it is opened.
            mirrored != null -> ArchivePageLoader(mirrored.archiveReader(context))
            source is LocalSource -> source.getFormat(chapter.chapter).let { format ->
                when (format) {
                    is Format.Directory -> DirectoryPageLoader(format.file)
                    is Format.Archive -> ArchivePageLoader(format.file.archiveReader(context))
                    is Format.Epub -> EpubPageLoader(format.file.epubReader(context))
                }
            }
            source is HttpSource -> HttpPageLoader(chapter, source, chapterCache)
            source is StubSource -> error(context.stringResource(MR.strings.source_not_installed, source.toString()))
            else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
        }
    }

    /**
     * Pulls [chapter] back from the remote mirror if it is held there. Returns
     * null when mirroring is off, the path cannot be built, or the server does
     * not have the file -- in every one of those cases the caller falls through
     * to the normal online loader.
     */
    private fun fetchFromMirror(chapter: ReaderChapter): UniFile? {
        if (!remoteMirror.isEnabled) return null

        val segments = segmentsFor(chapter) ?: return null
        val file = remoteMirror.fetch(segments) ?: return null

        return UniFile.fromFile(file)
    }

    private fun segmentsFor(chapter: ReaderChapter): List<String>? {
        val dbChapter = chapter.chapter
        return runCatching {
            remoteMirror.segmentsFor(
                sourceDirName = downloadProvider.getSourceDirName(source),
                mangaDirName = downloadProvider.getMangaDirName(manga.title),
                chapterFileName = RemoteMirror.chapterFileName(
                    downloadProvider.getChapterDirName(dbChapter.name, dbChapter.scanlator, dbChapter.url),
                ),
            )
        }.getOrNull()
    }
}
