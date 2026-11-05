package mihon.data.lansync

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Keeps [DeviceHoldings] for the badges and the reader. Manifests are kept in a file in app state
 * (never in a backup), so the badges stay while the PC sleeps: the badge says the chapter is
 * there, and opening it finds out whether the PC is on.
 */
@Inject
@SingleIn(AppScope::class)
class DeviceHoldingsStore(
    private val context: Context,
    private val links: LanSyncLinks,
    private val remoteQueue: RemoteQueueStore,
) {

    /** What the screens read. */
    data class Index(
        val holders: Map<RemoteQueue.ChapterRef, List<DeviceHoldings.Holder>> = emptyMap(),
        val counts: Map<DeviceHoldings.MangaRef, Int> = emptyMap(),
        val names: Map<String, String> = emptyMap(),
    ) {
        fun holders(sourceId: Long, mangaUrl: String, chapterUrl: String): List<DeviceHoldings.Holder> =
            holders[RemoteQueue.ChapterRef(sourceId.toString(), mangaUrl, chapterUrl)].orEmpty()

        /** Names of the devices holding a chapter, for the badge. Empty when none does. */
        fun deviceNames(sourceId: Long, mangaUrl: String, chapterUrl: String): List<String> =
            holders(sourceId, mangaUrl, chapterUrl).map { names[it.deviceId] ?: it.deviceId }

        fun count(sourceId: Long, mangaUrl: String): Int =
            counts[DeviceHoldings.MangaRef(sourceId.toString(), mangaUrl)] ?: 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "lansync/holdings.json")
    private val fetchDir = File(context.cacheDir, "lansync/device")

    private val client = OkHttpClient.Builder()
        // A sleeping PC shouldn't hold up opening a chapter for long
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val snapshots = MutableStateFlow(load())

    val index: StateFlow<Index> = combine(snapshots, remoteQueue.state, links.changes()) { snaps, queue, linked ->
        build(snaps, queue.held, linked)
    }.stateIn(scope, SharingStarted.Eagerly, build(snapshots.value, remoteQueue.state.value.held, links.all()))

    private fun build(
        snaps: Map<String, DeviceHoldings.Snapshot>,
        held: List<RemoteQueue.Held>,
        linked: List<LanSyncLink.Link>,
    ): Index {
        val holders = DeviceHoldings.index(snaps, held, linked.mapTo(HashSet()) { it.peerDeviceId })
        return Index(
            holders = holders,
            counts = DeviceHoldings.counts(holders),
            names = linked.associate { it.peerDeviceId to it.peerName },
        )
    }

    /** Refetches the manifest of every linked computer that answers. Blocking; call off the main thread. */
    fun refresh() {
        links.all()
            .filter { it.platform == LanSyncLink.PLATFORM_DESKTOP && it.lastAddress != null }
            .forEach(::refresh)
    }

    private fun refresh(link: LanSyncLink.Link) {
        val request = Request.Builder()
            .url("http://${link.lastAddress}/manifest")
            .header(LanSyncLink.LINK_KEY_HEADER, link.key)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logcat(LogPriority.DEBUG) { "LAN sync manifest from ${link.peerName}: HTTP ${response.code}" }
                    return
                }
                val manifest = json.decodeFromString<LanSyncProtocol.Manifest>(response.body.string())
                val snapshot = DeviceHoldings.fromManifest(manifest.files, System.currentTimeMillis())
                save(snapshots.value + (link.peerDeviceId to snapshot))
                logcat(LogPriority.INFO) { "LAN sync: ${link.peerName} holds ${snapshot.files.size} chapters" }
            }
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "LAN sync manifest from ${link.peerName} unreachable: ${e.message}" }
        }
    }

    /**
     * Copies a chapter from the first holder that answers, or null if none does, in which case
     * the reader goes on to the source. [fallbackPath] is used for a holder known only from a
     * done event, and is the path this phone's own download names give.
     */
    fun fetch(holders: List<DeviceHoldings.Holder>, fallbackPath: String?): File? {
        val byId = links.all().associateBy { it.peerDeviceId }
        for (holder in holders) {
            val link = byId[holder.deviceId] ?: continue
            val address = link.lastAddress ?: continue
            val path = holder.path ?: fallbackPath ?: continue
            fetchOne(link, address, path)?.let { return it }
        }
        return null
    }

    private fun fetchOne(link: LanSyncLink.Link, address: String, path: String): File? {
        // Spaces as %20: the phone's own server takes '+' literally, and chapter names have them
        val query = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        val request = Request.Builder()
            .url("http://$address/file?path=$query")
            .header(LanSyncLink.LINK_KEY_HEADER, link.key)
            .build()
        fetchDir.mkdirs()
        val target = File(fetchDir, digest(link.peerDeviceId + "\u0000" + path) + ".cbz")
        if (target.length() > 0) return target
        val partial = File(fetchDir, target.name + LanSyncUpload.PART_SUFFIX)
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logcat(LogPriority.DEBUG) { "LAN sync fetch from ${link.peerName}: HTTP ${response.code}" }
                    return null
                }
                partial.outputStream().use { response.body.byteStream().copyTo(it) }
            }
            if (partial.length() == 0L || !partial.renameTo(target)) {
                partial.delete()
                return null
            }
            trimFetched(keep = target)
            target
        } catch (e: Exception) {
            partial.delete()
            logcat(LogPriority.DEBUG) { "LAN sync fetch from ${link.peerName} failed: ${e.message}" }
            null
        }
    }

    /** Fetched chapters are a read-through cache, not downloads. Keeps the newest few. */
    private fun trimFetched(keep: File) {
        fetchDir.listFiles()
            ?.filter { it != keep && it.name.endsWith(LanSyncUpload.ARCHIVE_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(FETCH_CACHE_FILES - 1)
            ?.forEach { it.delete() }
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }

    @Synchronized
    private fun save(next: Map<String, DeviceHoldings.Snapshot>) {
        snapshots.value = next
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(next))
            tmp.renameTo(file)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Couldn't save LAN sync holdings" }
        }
    }

    private fun load(): Map<String, DeviceHoldings.Snapshot> = try {
        if (file.exists()) json.decodeFromString(file.readText()) else emptyMap()
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Unreadable LAN sync holdings" }
        emptyMap()
    }

    private companion object {
        const val FETCH_CACHE_FILES = 4
    }
}
