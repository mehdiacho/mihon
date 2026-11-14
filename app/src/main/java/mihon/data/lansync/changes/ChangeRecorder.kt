package mihon.data.lansync.changes

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import mihon.data.lansync.LanSyncPreferences
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes the person's changes into the `changes/1` journal. Called by the journaled repositories
 * right after a write, so every screen that changes the library is covered: the reader, the
 * chapter list, the library, the category editor, a restore of categories.
 *
 * Reads the stored rows through the plain repositories, after the write, so each entry holds the
 * whole value. Nothing is journaled while this install has no linked device: the first sync after
 * linking is a full merge anyway.
 */
@Inject
@SingleIn(AppScope::class)
class ChangeRecorder(
    context: Context,
    private val preferences: LanSyncPreferences,
    private val mangas: MangaRepositoryImpl,
    private val chapters: ChapterRepositoryImpl,
    private val categories: CategoryRepositoryImpl,
) {

    val journal = ChangeJournal(
        store = runCatching<JournalStore> { SqliteJournalStore(context) }
            .getOrElse { MemoryJournalStore() },
        deviceId = { preferences.deviceId },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _written = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires after each journal write, for the push a couple of seconds later. */
    val written: SharedFlow<Unit> = _written.asSharedFlow()

    /** Page turns, held back per chapter so a chapter read through is one entry, not one per page. */
    private val pendingProgress = ConcurrentHashMap<Long, Job>()

    private val enabled: Boolean get() = preferences.links.get().isNotBlank() && preferences.links.get() != "[]"

    // ---- From the repositories ----

    fun mangaUpdated(updates: List<MangaUpdate>) {
        if (!enabled) return
        val at = now()
        updates.forEach { update ->
            val library = update.isSet(update::favoriteAt)
            val viewer = update.viewerFlags != null
            if (!library && !viewer) return@forEach
            later {
                val manga = mangas.getMangaById(update.id)
                if (library) record(ChangeKind.MANGA_LIBRARY, mangaTarget(manga), libraryValue(manga), at)
                if (viewer) {
                    record(
                        ChangeKind.MANGA_VIEWER,
                        mangaTarget(manga),
                        buildJsonObject {
                            put("viewer", manga.viewerFlags)
                        },
                        at,
                    )
                }
            }
        }
    }

    fun chaptersUpdated(updates: List<ChapterUpdate>) {
        if (!enabled) return
        val at = now()
        updates.forEach { update ->
            if (update.bookmark != null) later { chapterEntry(update.id, ChangeKind.CHAPTER_BOOKMARK, at) }
            when {
                update.read != null -> {
                    pendingProgress.remove(update.id)?.cancel()
                    later { chapterEntry(update.id, ChangeKind.CHAPTER_READ, at) }
                }
                update.lastPageRead != null -> {
                    pendingProgress.put(
                        update.id,
                        scope.launch {
                            delay(PROGRESS_QUIET_MS)
                            pendingProgress.remove(update.id)
                            guarded { chapterEntry(update.id, ChangeKind.CHAPTER_READ, at) }
                        },
                    )?.cancel()
                }
            }
        }
    }

    fun historyUpserted(update: HistoryUpdate) {
        if (!enabled) return
        val at = now()
        later {
            val chapter = chapters.getChapterById(update.chapterId) ?: return@later
            val manga = mangas.getMangaById(chapter.mangaId)
            record(
                ChangeKind.HISTORY_READ,
                chapterTarget(manga, chapter),
                buildJsonObject {
                    put("lastRead", update.readAt.toEpochMilliseconds())
                    put("timeRead", update.sessionReadDuration)
                },
                at,
            )
        }
    }

    fun mangaCategoriesSet(mangaIds: List<Long>) {
        if (!enabled) return
        val at = now()
        later {
            mangaIds.forEach { id ->
                val manga = mangas.getMangaById(id)
                record(ChangeKind.MANGA_CATEGORIES, mangaTarget(manga), categoriesValue(id), at)
            }
        }
    }

    /** New categories and moves: one `category.set` per user category whose order is given. */
    fun categoriesPlaced(names: Set<String>?) {
        if (!enabled) return
        val at = now()
        later {
            categories.getAll()
                .filter { !it.isSystemCategory && (names == null || it.name in names) }
                .forEach {
                    record(ChangeKind.CATEGORY_SET, nameTarget(it.name), buildJsonObject { put("order", it.order) }, at)
                }
        }
    }

    fun categoryRenamed(old: String, new: String) {
        if (!enabled || old == new) return
        val at = now()
        later { record(ChangeKind.CATEGORY_RENAME, nameTarget(old), buildJsonObject { put("name", new) }, at) }
    }

    fun categoryDeleted(name: String) {
        if (!enabled) return
        val at = now()
        later { record(ChangeKind.CATEGORY_DELETE, nameTarget(name), JsonObject(emptyMap()), at) }
    }

    // ---- Shared ----

    private suspend fun chapterEntry(chapterId: Long, kind: String, at: Long) {
        val chapter = chapters.getChapterById(chapterId) ?: return
        val manga = mangas.getMangaById(chapter.mangaId)
        val value = if (kind == ChangeKind.CHAPTER_BOOKMARK) {
            buildJsonObject { put("bookmark", chapter.bookmark) }
        } else {
            buildJsonObject {
                put("read", chapter.read)
                put("lastPageRead", chapter.lastPageRead)
            }
        }
        record(kind, chapterTarget(manga, chapter), value, at)
    }

    private suspend fun categoriesValue(mangaId: Long): JsonObject = buildJsonObject {
        put(
            "categories",
            JsonArray(
                categories.getCategoriesByMangaId(mangaId).filterNot(Category::isSystemCategory).map {
                    JsonPrimitive(it.name)
                },
            ),
        )
    }

    private fun record(kind: String, target: JsonObject, value: JsonObject, at: Long) {
        journal.record(kind, target, value, at)
        _written.tryEmit(Unit)
    }

    private fun later(block: suspend () -> Unit) {
        scope.launch { guarded(block) }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // The library change itself went through; only its journal entry is lost
            logcat(LogPriority.WARN, e) { "LAN sync: couldn't journal a change" }
        }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        /** A chapter's page progress is journaled once the reader has been on it this long without turning. */
        const val PROGRESS_QUIET_MS = 15_000L

        fun mangaTarget(manga: Manga) = buildJsonObject {
            put("sourceId", manga.source.toString())
            put("mangaUrl", manga.url)
        }

        fun chapterTarget(manga: Manga, chapter: Chapter) = buildJsonObject {
            put("sourceId", manga.source.toString())
            put("mangaUrl", manga.url)
            put("chapterUrl", chapter.url)
        }

        fun nameTarget(name: String) = buildJsonObject { put("name", name) }

        fun libraryValue(manga: Manga) = buildJsonObject {
            put("inLibrary", manga.favorite)
            if (manga.favorite) {
                manga.favoriteAt?.let { put("dateAdded", it.toEpochMilliseconds()) }
                put(
                    "manga",
                    buildJsonObject {
                        put("title", manga.title)
                        put("author", manga.author?.let(::JsonPrimitive) ?: JsonNull)
                        put("artist", manga.artist?.let(::JsonPrimitive) ?: JsonNull)
                        put("description", manga.description?.let(::JsonPrimitive) ?: JsonNull)
                        put("genres", JsonArray(manga.genre.orEmpty().map(::JsonPrimitive)))
                        put("status", manga.status)
                        put("thumbnailUrl", manga.thumbnailUrl?.let(::JsonPrimitive) ?: JsonNull)
                    },
                )
            }
        }
    }
}
