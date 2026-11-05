package mihon.data.lansync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.data.remote.RemoteClientProvider
import mihon.data.remote.RemoteIndex
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The phone's side of a linked PC downloading its queue: answers the queue routes, applies done
 * events, and catches up on events it missed from the PC's outbox and the NAS.
 */
@Inject
@SingleIn(AppScope::class)
class LanSyncQueue(
    private val context: Context,
    private val store: RemoteQueueStore,
    private val links: LanSyncLinks,
    private val preferences: LanSyncPreferences,
    private val downloadManager: DownloadManager,
    private val downloader: Downloader,
    private val downloadProvider: DownloadProvider,
    private val downloadCache: DownloadCache,
    private val remoteIndex: RemoteIndex,
    private val remoteClients: RemoteClientProvider,
    private val sourceManager: SourceManager,
    private val getManga: GetMangaByUrlAndSourceId,
    private val holdings: DeviceHoldingsStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catchUpMutex = Mutex()

    @Volatile
    private var lastCatchUp = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun queue(): List<Download> = downloadManager.queueState.value

    // ---- Routes, called by LanSyncServer once the link key checks out ----

    fun items(): RemoteQueue.QueueResponse {
        expireClaims()
        return RemoteQueue.QueueResponse(
            queue().map {
                RemoteQueue.QueueItem(
                    sourceId = it.source.id.toString(),
                    mangaUrl = it.manga.url,
                    chapterUrl = it.chapter.url,
                    mangaTitle = it.manga.title,
                    chapterName = it.chapter.name,
                    queuedAt = it.queuedAt,
                )
            },
        )
    }

    fun claim(request: RemoteQueue.ClaimRequest, link: LanSyncLink.Link): RemoteQueue.ClaimResponse {
        val entries = queue().map {
            RemoteQueue.Entry(RemoteQueueStore.refOf(it), it.status == Download.State.DOWNLOADING)
        }
        val claimed = store.update { state ->
            val result = RemoteQueue.claim(
                state,
                request.items,
                entries,
                link.peerDeviceId,
                link.peerName,
                System.currentTimeMillis(),
            )
            result.state to result.claimed
        }
        logcat(LogPriority.INFO) { "LAN sync: ${link.peerName} claimed ${claimed.size} of ${request.items.size}" }
        return RemoteQueue.ClaimResponse(claimed)
    }

    fun release(request: RemoteQueue.ReleaseRequest, link: LanSyncLink.Link) {
        val released = store.update { RemoteQueue.release(it, request.items, link.peerDeviceId) }
        if (released.isNotEmpty()) resumeHere()
    }

    fun done(request: RemoteQueue.DoneRequest): RemoteQueue.DoneResponse =
        RemoteQueue.DoneResponse(applyEvents(request.events, cursorKey = null))

    // ---- Applying events ----

    /** Records [events], acts on the new ones, and returns every id the sender may now forget. */
    private fun applyEvents(events: List<RemoteQueue.DoneEvent>, cursorKey: String?): List<String> {
        val result = store.update { state ->
            val applied = RemoteQueue.apply(state, events, cursorKey)
            applied.state to applied
        }
        if (result.fresh.isEmpty()) return result.acked

        // Finished elsewhere, so not wanted here any more
        val done = result.fresh.mapTo(HashSet()) { it.ref }
        val finished = queue().filter { RemoteQueueStore.refOf(it) in done }
        if (finished.isNotEmpty()) scope.launch { downloadManager.cancelQueuedDownloads(finished) }

        if (result.fresh.any { it.where == RemoteQueue.WHERE_SENT }) downloadCache.invalidateCache()
        result.fresh
            .filter { it.where == RemoteQueue.WHERE_SERVER }
            .distinctBy { it.sourceId to it.mangaUrl }
            .forEach { event -> scope.launch { refreshRemote(event) } }

        logcat(LogPriority.INFO) { "LAN sync: applied ${result.fresh.size} done events" }
        return result.acked
    }

    private suspend fun refreshRemote(event: RemoteQueue.DoneEvent) {
        val sourceId = event.sourceId.toLongOrNull() ?: return
        val source = sourceManager.get(sourceId) ?: return
        val manga = getManga.await(event.mangaUrl, sourceId) ?: return
        remoteIndex.refresh(downloadProvider.getSourceDirName(source), downloadProvider.getMangaDirName(manga.title))
    }

    /** Claims a PC sat on for a day go back to downloading here. */
    fun expireClaims() {
        val expired = store.update { state ->
            val result = RemoteQueue.expire(state, System.currentTimeMillis())
            result.state to result.refs
        }
        if (expired.isNotEmpty()) {
            logcat(LogPriority.INFO) { "LAN sync: ${expired.size} claims timed out" }
            resumeHere()
        }
    }

    /** Released or expired items download here again, unless the user paused downloads. */
    private fun resumeHere() {
        if (!downloader.isPaused && queue().isNotEmpty()) downloadManager.startDownloads()
    }

    // ---- Catching up ----

    /** On app open and when a peer shows up on the network. At most once a minute. */
    fun catchUpSoon() {
        val now = System.currentTimeMillis()
        if (now - lastCatchUp < CATCH_UP_INTERVAL_MS) return
        lastCatchUp = now
        scope.launch {
            catchUpMutex.withLock {
                expireClaims()
                links.all()
                    .filter { it.platform == LanSyncLink.PLATFORM_DESKTOP && it.lastAddress != null }
                    .forEach { pullOutbox(it) }
                readNasOutbox()
                // What each computer holds for this profile, for the "on <device>" badges
                holdings.refresh()
            }
        }
    }

    private fun pullOutbox(link: LanSyncLink.Link) {
        val since = store.state.value.cursors[link.peerDeviceId]
        val url = buildString {
            append("http://${link.lastAddress}/outbox")
            if (since != null) append("?since=").append(java.net.URLEncoder.encode(since, "UTF-8"))
        }
        val request = Request.Builder().url(url).header(LanSyncLink.LINK_KEY_HEADER, link.key).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logcat(LogPriority.DEBUG) { "LAN sync outbox from ${link.peerName}: HTTP ${response.code}" }
                    return
                }
                val events = RemoteQueue.parseOutbox(response.body.string()) ?: return
                if (events.isNotEmpty()) applyEvents(events, cursorKey = link.peerDeviceId)
            }
        } catch (e: Exception) {
            // Asleep or elsewhere; the NAS outbox or the next sighting covers it
            logcat(LogPriority.DEBUG) { "LAN sync outbox from ${link.peerName} unreachable: ${e.message}" }
        }
    }

    private fun readNasOutbox() {
        val remote = remoteClients.get() ?: return
        val file = File(context.cacheDir, "lansync/nas-outbox.jsonl").apply { parentFile?.mkdirs() }
        try {
            val got = remote.get(listOf(".tachimana", "outbox", "${preferences.deviceId}.jsonl"), file) { _, _ -> }
            if (got.isFailure) return
            val all = RemoteQueue.parseOutboxLines(file.readText())
            val fresh = RemoteQueue.after(all, store.state.value.cursors[NAS_CURSOR])
            if (fresh.isNotEmpty()) applyEvents(fresh, cursorKey = NAS_CURSOR)
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "LAN sync NAS outbox unreadable: ${e.message}" }
        } finally {
            file.delete()
        }
    }

    // ---- Seeing a peer on the network ----

    private var discovery: NsdManager.DiscoveryListener? = null

    /** Watches for other TachiMana devices while the app is in front. Any sighting triggers a catch-up. */
    @Synchronized
    fun watch(on: Boolean) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        if (!on) {
            discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            discovery = null
            return
        }
        if (discovery != null || links.all().none { it.platform == LanSyncLink.PLATFORM_DESKTOP }) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                catchUpSoon()
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                logcat(LogPriority.DEBUG) { "LAN sync discovery failed: $errorCode" }
                synchronized(this@LanSyncQueue) { discovery = null }
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        runCatching {
            nsd.discoverServices(LanSyncProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discovery = listener
        }.onFailure { logcat(LogPriority.DEBUG, it) { "LAN sync discovery failed" } }
    }

    private companion object {
        const val CATCH_UP_INTERVAL_MS = 60_000L
        const val NAS_CURSOR = "nas"
    }
}
