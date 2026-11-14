package mihon.data.lansync.changes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.model.NewCategory
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import kotlin.time.Instant

/**
 * Writes a winning `changes/1` value into the library. Goes through the upstream repositories,
 * not the journaled ones, so an applied entry is journaled once, under its original id, by
 * [ChangeJournal], and not again as if the person had made it here.
 *
 * A target this phone doesn't have (a chapter not fetched yet, a manga not in the database) is
 * not an error: the entry is still held and passed on, and the value arrives with the next full
 * merge.
 */
@Inject
@SingleIn(AppScope::class)
class ChangeApplier(
    private val mangas: MangaRepositoryImpl,
    private val chapters: ChapterRepositoryImpl,
    private val categories: CategoryRepositoryImpl,
    private val history: HistoryRepositoryImpl,
) {

    suspend fun apply(change: Change, value: JsonObject): Boolean = try {
        when (change.kind) {
            ChangeKind.MANGA_LIBRARY -> library(change.target, value)
            ChangeKind.MANGA_CATEGORIES -> mangaCategories(change.target, value)
            ChangeKind.MANGA_VIEWER -> manga(change.target)?.let { manga ->
                value.long("viewer")?.let { flags -> mangas.update(MangaUpdate(manga.id) { viewerFlags = flags }) }
            }
            ChangeKind.CATEGORY_SET -> placeCategory(change.target.str("name"), value.long("order"))
            ChangeKind.CATEGORY_RENAME -> renameCategory(change.target.str("name"), value.str("name"))
            ChangeKind.CATEGORY_DELETE -> categoryNamed(change.target.str("name"))?.let { categories.delete(it.id) }
            ChangeKind.CHAPTER_READ -> chapter(change.target)?.let { chapter ->
                chapters.update(
                    ChapterUpdate(chapter.id) {
                        value.bool("read")?.let { read = it }
                        value.int("lastPageRead")?.let { lastPageRead = it }
                    },
                )
            }
            ChangeKind.CHAPTER_BOOKMARK -> chapter(change.target)?.let { chapter ->
                value.bool("bookmark")?.let { chapters.update(ChapterUpdate(chapter.id) { bookmark = it }) }
            }
            ChangeKind.HISTORY_READ -> readHistory(change)
        }
        true
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "LAN sync: couldn't apply ${change.kind}" }
        false
    }

    private suspend fun library(target: JsonObject, value: JsonObject) {
        val inLibrary = value.bool("inLibrary") ?: return
        val existing = manga(target)
        if (!inLibrary) {
            if (existing?.favorite == true) mangas.update(MangaUpdate(existing.id) { favoriteAt = null })
            return
        }
        val manga = existing ?: insert(target, value["manga"] as? JsonObject ?: return) ?: return
        val added = Instant.fromEpochMilliseconds(value.long("dateAdded") ?: System.currentTimeMillis())
        if (manga.favoriteAt != added) mangas.update(MangaUpdate(manga.id) { favoriteAt = added })
    }

    /** A manga this phone has never seen, from what the entry says about it; the next refresh fills in the rest. */
    private suspend fun insert(target: JsonObject, info: JsonObject): Manga? {
        val sourceId = target.str("sourceId")?.toLongOrNull() ?: return null
        val url = target.str("mangaUrl") ?: return null
        val manga = Manga.create().copy(
            source = sourceId,
            url = url,
            title = info.str("title").orEmpty(),
            author = info.str("author"),
            artist = info.str("artist"),
            description = info.str("description"),
            genre = (info["genres"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull },
            status = info.long("status") ?: 0L,
            thumbnailUrl = info.str("thumbnailUrl"),
        )
        return mangas.insertNetworkManga(listOf(manga)).firstOrNull()
    }

    private suspend fun mangaCategories(target: JsonObject, value: JsonObject) {
        val manga = manga(target) ?: return
        val names = (value["categories"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: return
        val missing = names.filter { name -> categoryNamed(name) == null }
        if (missing.isNotEmpty()) categories.insertAll(missing.map { NewCategory(it, 0L) })
        val ids = names.mapNotNull { categoryNamed(it)?.id }
        mangas.setMangaCategories(manga.id, ids)
    }

    private suspend fun placeCategory(name: String?, order: Long?) {
        if (name.isNullOrBlank()) return
        if (categoryNamed(name) == null) categories.insert(NewCategory(name, 0L))
        if (order == null) return
        val user = categories.getAll().filterNot { it.isSystemCategory }.sortedBy { it.order }
        val moving = user.firstOrNull { it.name == name } ?: return
        val rest = user.filterNot { it.id == moving.id }.map { it.id }.toMutableList()
        rest.add(order.toInt().coerceIn(0, rest.size), moving.id)
        categories.updateAllOrders(rest)
    }

    private suspend fun renameCategory(old: String?, new: String?) {
        if (old == null || new.isNullOrBlank() || categoryNamed(new) != null) return
        categoryNamed(old)?.let { categories.updateName(it.id, new) }
    }

    /** Adds the session's reading time once; keeps the later of the two read times. */
    private suspend fun readHistory(change: Change) {
        val chapter = chapter(change.target) ?: return
        val incoming = change.value.long("lastRead") ?: return
        val held = history.getHistoryByMangaId(chapter.mangaId).firstOrNull { it.chapterId == chapter.id }
        val readAt = maxOf(incoming, held?.readAt?.toEpochMilliseconds() ?: 0L)
        history.upsertHistory(
            HistoryUpdate(chapter.id, Instant.fromEpochMilliseconds(readAt), change.value.long("timeRead") ?: 0L),
        )
    }

    private suspend fun manga(target: JsonObject): Manga? {
        val sourceId = target.str("sourceId")?.toLongOrNull() ?: return null
        return mangas.getMangaByUrlAndSourceId(target.str("mangaUrl") ?: return null, sourceId)
    }

    private suspend fun chapter(target: JsonObject): Chapter? {
        val manga = manga(target) ?: return null
        return chapters.getChapterByUrlAndMangaId(target.str("chapterUrl") ?: return null, manga.id)
    }

    private suspend fun categoryNamed(name: String?) =
        name?.let { categories.getAll().firstOrNull { c -> !c.isSystemCategory && c.name == it } }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}
