package mihon.data.lansync

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** The devices this install is linked to, and who this install is to them. */
@Inject
@SingleIn(AppScope::class)
class LanSyncLinks(
    private val context: Context,
    private val preferences: LanSyncPreferences,
) {

    private val json = Json { ignoreUnknownKeys = true }

    val profileId: String get() = preferences.profileId

    val profileName: String
        get() = preferences.profileName.get().ifBlank {
            context.applicationInfo.loadLabel(context.packageManager).toString()
        }

    fun all(): List<LanSyncLink.Link> = decode(preferences.links.get())

    fun changes(): Flow<List<LanSyncLink.Link>> = preferences.links.changes().map(::decode)

    fun findByKey(key: String?): LanSyncLink.Link? = LanSyncLink.findByKey(all(), key)

    /** A device links once; linking it again replaces the old key. */
    @Synchronized
    fun save(link: LanSyncLink.Link) {
        write(all().filterNot { it.peerDeviceId == link.peerDeviceId } + link)
    }

    @Synchronized
    fun remove(peerDeviceId: String) {
        write(all().filterNot { it.peerDeviceId == peerDeviceId })
    }

    /** Where a linked device was last heard from, so this side can reach it back. */
    @Synchronized
    fun touch(link: LanSyncLink.Link, address: String) {
        if (link.lastAddress == address) return
        write(all().map { if (it.peerDeviceId == link.peerDeviceId) it.copy(lastAddress = address) else it })
    }

    private fun write(links: List<LanSyncLink.Link>) {
        preferences.links.set(json.encodeToString(links))
    }

    private fun decode(raw: String): List<LanSyncLink.Link> {
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Unreadable LAN sync links" }
            emptyList()
        }
    }
}
