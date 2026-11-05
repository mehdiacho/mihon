package mihon.data.lansync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * The phone's record of remote downloads, read by the downloader (to skip what a PC claimed) and
 * by the queue screen. Holds no other dependencies, so the downloader can use it without a cycle.
 */
@Inject
@SingleIn(AppScope::class)
class RemoteQueueStore(
    private val preferences: LanSyncPreferences,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<RemoteQueue.State> = _state.asStateFlow()

    /** The live claim on [download], if a linked PC has it. */
    fun claimOn(download: Download, state: RemoteQueue.State = _state.value): RemoteQueue.Claim? {
        if (state.claims.isEmpty()) return null
        val ref = refOf(download)
        return state.claims.firstOrNull { it.ref == ref }
    }

    fun isClaimed(download: Download, state: RemoteQueue.State = _state.value): Boolean =
        claimOn(download, state) != null

    @Synchronized
    fun <R> update(change: (RemoteQueue.State) -> Pair<RemoteQueue.State, R>): R {
        val (next, result) = change(_state.value)
        if (next != _state.value) {
            preferences.remoteQueue.set(json.encodeToString(next))
            _state.value = next
        }
        return result
    }

    private fun load(): RemoteQueue.State {
        val raw = preferences.remoteQueue.get()
        if (raw.isBlank()) return RemoteQueue.State()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Unreadable remote queue state" }
            RemoteQueue.State()
        }
    }

    companion object {
        fun refOf(download: Download) = RemoteQueue.ChapterRef(
            sourceId = download.source.id.toString(),
            mangaUrl = download.manga.url,
            chapterUrl = download.chapter.url,
        )
    }
}
