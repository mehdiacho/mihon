package eu.kanade.tachiyomi.ui.library

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import mihon.domain.extension.model.ContentWarning
import tachiyomi.domain.library.model.CoverBlur
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The live [CoverBlur.Rules] for the library's privacy blur, and the setters behind its menus.
 *
 * A source counts as NSFW when its extension is rated [ContentWarning.NSFW], from the installed
 * apk's metadata or from a store listing, so a series still counts when its extension is filtered
 * out, uninstalled or not loaded yet. [ContentWarning.MIXED] doesn't count: most of those
 * sources are mainly safe.
 */
@Inject
@SingleIn(AppScope::class)
class CoverBlurRules(
    private val libraryPreferences: LibraryPreferences,
    private val extensionManager: ExtensionManager,
) {

    private val nsfwSourceIds: Flow<Set<Long>> = combine(
        extensionManager.loadedExtensionsFlow.onStart { emit(emptyList()) },
        extensionManager.availableExtensionsFlow,
    ) { loaded, available ->
        buildSet {
            loaded.filter { it.contentWarning == ContentWarning.NSFW }
                .forEach { ext -> ext.sources.forEach { add(it.id) } }
            available.filter { it.contentWarning == ContentWarning.NSFW }
                .forEach { ext -> ext.sources.forEach { add(it.id) } }
        }
    }.distinctUntilChanged()

    val rules: Flow<CoverBlur.Rules> = combine(
        libraryPreferences.blurAdultCovers.changes(),
        libraryPreferences.coverBlurForced.changes(),
        libraryPreferences.coverBlurExempt.changes(),
        combine(
            libraryPreferences.coverBlurSourcesForced.changes(),
            libraryPreferences.coverBlurSourcesExempt.changes(),
            ::Pair,
        ),
        nsfwSourceIds,
    ) { enabled, forced, exempt, (sourcesForced, sourcesExempt), nsfw ->
        CoverBlur.Rules(enabled, forced, exempt, sourcesForced, sourcesExempt, nsfw)
    }.distinctUntilChanged()

    /** Sets the blur by hand for each series in [mangaIds]. */
    fun setManga(mangaIds: Collection<Long>, blur: Boolean) {
        val (forced, exempt) = CoverBlur.override(
            ids = mangaIds,
            blur = blur,
            forced = libraryPreferences.coverBlurForced.get(),
            exempt = libraryPreferences.coverBlurExempt.get(),
        )
        libraryPreferences.coverBlurForced.set(forced)
        libraryPreferences.coverBlurExempt.set(exempt)
    }

    /** Sets the blur by hand for a whole source. Series set by hand keep their own choice. */
    fun setSource(sourceId: Long, blur: Boolean) {
        val (forced, exempt) = CoverBlur.override(
            ids = listOf(sourceId),
            blur = blur,
            forced = libraryPreferences.coverBlurSourcesForced.get(),
            exempt = libraryPreferences.coverBlurSourcesExempt.get(),
        )
        libraryPreferences.coverBlurSourcesForced.set(forced)
        libraryPreferences.coverBlurSourcesExempt.set(exempt)
    }
}
