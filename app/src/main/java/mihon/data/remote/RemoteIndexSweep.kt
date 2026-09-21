package mihon.data.remote

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.source.service.SourceManager

/**
 * Asks the server what it has, for the whole library at once.
 *
 * The index otherwise fills in one series at a time, when a screen happens to
 * ask about that series, which is fine for browsing and useless for the
 * question people actually have after setting this up: is any of my library
 * up there? This is the remote counterpart of "Re-index downloads", and like
 * that one it exists because a cache that only heals when you look at it
 * cannot be trusted after the thing it caches has changed underneath.
 *
 * Reads only. Nothing is uploaded, deleted or evicted here; that is
 * [MirrorExistingDownloads] and [RemoteMaintenance].
 */
@Inject
@SingleIn(AppScope::class)
class RemoteIndexSweep(
    private val mirror: RemoteMirror,
    private val index: RemoteIndex,
    private val provider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val getFavorites: GetFavorites,
    private val notifier: RemoteNotifier,
) {

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    sealed interface State {
        data object Idle : State

        data class Running(val done: Int, val total: Int, val chapters: Int) : State

        /** [unreachable] is series the server could not be asked about. */
        data class Done(val series: Int, val chapters: Int, val unreachable: Int) : State
    }

    /**
     * One request per series, on [Dispatchers.IO] -- chosen here so that a
     * caller launching this from a composable's scope cannot put a few hundred
     * blocking requests on the main thread.
     */
    suspend fun run() = withContext(Dispatchers.IO) {
        // An ongoing notification outlives the coroutine that posted it, so the
        // dismissal has to survive cancellation too.
        try {
            sweep()
        } finally {
            notifier.dismissProgress()
        }
    }

    private suspend fun sweep() {
        if (!mirror.isEnabled) return

        // Dropped rather than merged: the point of running this is to stop
        // trusting what is already in there.
        index.invalidate()

        val library = getFavorites.await()
        var chapters = 0
        var series = 0
        var unreachable = 0

        library.forEachIndexed { done, manga ->
            _state.value = State.Running(done, library.size, chapters)
            notifier.sweepProgress(done, library.size, chapters)

            val source = sourceManager.getOrStub(manga.source)
            val count = index.refreshNow(
                provider.getSourceDirName(source),
                provider.getMangaDirName(manga.title),
            )
            when {
                count == null -> unreachable++
                count > 0 -> {
                    series++
                    chapters += count
                }
            }
        }

        logcat(LogPriority.INFO) {
            "Remote sweep: $chapters chapters across $series series, $unreachable unreachable"
        }
        _state.value = State.Done(series, chapters, unreachable)
        notifier.sweepDone(series, chapters, unreachable)
    }
}
