package mihon.data.lansync

import kotlinx.serialization.Serializable

/**
 * Which chapters a linked device holds for this profile: the "on <device>" badge. The contract is
 * TachiMana's `docs/rust-port/profiles-and-remote-downloads.md`, §5.
 *
 * Two sources feed it. A device's `/manifest`, fetched while it was reachable, is the whole truth
 * as of that moment. A `where: "device"` done event (#2) says one chapter landed there, and counts
 * only if it is newer than the last manifest from that device, which would otherwise already
 * include it, or have dropped it since.
 *
 * Android-free so the merge is unit tested. [DeviceHoldingsStore] fetches, keeps and serves it.
 */
object DeviceHoldings {

    const val KIND_DOWNLOAD = "download"

    /** One chapter in a device's manifest, with the path its `/file` serves it at. */
    @Serializable
    data class Listed(val ref: RemoteQueue.ChapterRef, val path: String)

    /** A device's manifest as last fetched. `fetchedAt` is this phone's clock. */
    @Serializable
    data class Snapshot(val fetchedAt: Long, val files: List<Listed>)

    /** A device holding a chapter. [path] is null when only a done event says so. */
    data class Holder(val deviceId: String, val path: String?)

    data class MangaRef(val sourceId: String, val mangaUrl: String)

    /** Chapters only, and only paths the phone's own path rules accept. */
    fun fromManifest(files: List<LanSyncProtocol.ManifestFile>, fetchedAt: Long): Snapshot =
        Snapshot(
            fetchedAt = fetchedAt,
            files = files
                .filter { it.kind == KIND_DOWNLOAD && LanSyncProtocol.isSafePath(it.path) }
                .map { Listed(RemoteQueue.ChapterRef(it.sourceId, it.mangaUrl, it.chapterUrl), it.path) }
                .distinctBy { it.ref },
        )

    /** Every held chapter with who holds it. Devices not in [linked] are left out. */
    fun index(
        snapshots: Map<String, Snapshot>,
        held: List<RemoteQueue.Held>,
        linked: Set<String>,
    ): Map<RemoteQueue.ChapterRef, List<Holder>> {
        val out = LinkedHashMap<RemoteQueue.ChapterRef, MutableList<Holder>>()
        snapshots.forEach { (deviceId, snapshot) ->
            if (deviceId !in linked) return@forEach
            snapshot.files.forEach { out.getOrPut(it.ref) { mutableListOf() }.add(Holder(deviceId, it.path)) }
        }
        held.forEach { event ->
            if (event.deviceId !in linked) return@forEach
            val snapshot = snapshots[event.deviceId]
            // The manifest is newer, and is the truth about this device
            if (snapshot != null && event.at <= snapshot.fetchedAt) return@forEach
            val holders = out.getOrPut(event.ref) { mutableListOf() }
            if (holders.none { it.deviceId == event.deviceId }) holders.add(Holder(event.deviceId, null))
        }
        return out
    }

    /** Held chapters per series, for the cover badge. */
    fun counts(index: Map<RemoteQueue.ChapterRef, List<Holder>>): Map<MangaRef, Int> =
        index.keys.groupingBy { MangaRef(it.sourceId, it.mangaUrl) }.eachCount()
}
