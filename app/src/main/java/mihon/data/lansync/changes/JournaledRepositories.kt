package mihon.data.lansync.changes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.model.NewCategory
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/*
 * The library's repositories, as the rest of the app gets them: the upstream ones, plus a journal
 * entry after each write a person makes. Source refreshes go through methods left alone here
 * (insertNetworkManga, updateRemote, updateFromRemote), so they are never journaled. Entries
 * that arrive from another device are applied through the upstream classes directly.
 */

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [MangaRepositoryImpl::class])
class JournaledMangaRepository(
    private val impl: MangaRepositoryImpl,
    private val recorder: ChangeRecorder,
) : MangaRepository by impl {

    override suspend fun update(update: MangaUpdate): Boolean =
        impl.update(update).also { if (it) recorder.mangaUpdated(listOf(update)) }

    override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean =
        impl.updateAll(mangaUpdates).also { if (it) recorder.mangaUpdated(mangaUpdates) }

    override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {
        impl.setMangaCategories(mangaId, categoryIds)
        recorder.mangaCategoriesSet(listOf(mangaId))
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [ChapterRepositoryImpl::class])
class JournaledChapterRepository(
    private val impl: ChapterRepositoryImpl,
    private val recorder: ChangeRecorder,
) : ChapterRepository by impl {

    override suspend fun update(chapterUpdate: ChapterUpdate) {
        impl.update(chapterUpdate)
        recorder.chaptersUpdated(listOf(chapterUpdate))
    }

    override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
        impl.updateAll(chapterUpdates)
        recorder.chaptersUpdated(chapterUpdates)
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [HistoryRepositoryImpl::class])
class JournaledHistoryRepository(
    private val impl: HistoryRepositoryImpl,
    private val recorder: ChangeRecorder,
) : HistoryRepository by impl {

    override suspend fun upsertHistory(historyUpdate: HistoryUpdate) {
        impl.upsertHistory(historyUpdate)
        recorder.historyUpserted(historyUpdate)
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [CategoryRepositoryImpl::class])
class JournaledCategoryRepository(
    private val impl: CategoryRepositoryImpl,
    private val recorder: ChangeRecorder,
) : CategoryRepository by impl {

    override suspend fun insert(category: NewCategory) {
        impl.insert(category)
        recorder.categoriesPlaced(setOf(category.name))
    }

    override suspend fun insertAll(categories: List<NewCategory>) {
        impl.insertAll(categories)
        recorder.categoriesPlaced(categories.mapTo(HashSet()) { it.name })
    }

    override suspend fun updateName(categoryId: Long, name: String) {
        val old = impl.get(categoryId)?.takeUnless { it.isSystemCategory }?.name
        impl.updateName(categoryId, name)
        if (old != null) recorder.categoryRenamed(old, name)
    }

    override suspend fun updateAllOrders(orderedIds: List<Long>) {
        impl.updateAllOrders(orderedIds)
        recorder.categoriesPlaced(null)
    }

    override suspend fun delete(categoryId: Long) {
        val old = impl.get(categoryId)?.takeUnless { it.isSystemCategory }?.name
        impl.delete(categoryId)
        if (old != null) recorder.categoryDeleted(old)
    }
}
