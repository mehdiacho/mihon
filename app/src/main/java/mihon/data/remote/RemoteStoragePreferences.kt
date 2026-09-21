package mihon.data.remote

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okhttp3.HttpUrl
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * Settings for mirroring finished downloads to a WebDAV server.
 *
 * Downloads always land on local storage first -- that path is fast, works
 * offline, and is unchanged from upstream. The remote is a *mirror*: a chapter
 * is uploaded once it has finished downloading, and may then be evicted locally
 * and fetched back on demand.
 */
@Inject
@SingleIn(AppScope::class)
class RemoteStoragePreferences(
    preferenceStore: PreferenceStore,
) {

    val enabled: Preference<Boolean> = preferenceStore.getBoolean("remote_storage_enabled", false)

    /** Host name or IP, without a scheme. */
    val host: Preference<String> = preferenceStore.getString("remote_storage_host", "")

    /** Blank for the scheme's default, 443 or 80. */
    val port: Preference<String> = preferenceStore.getString("remote_storage_port", "")

    val useHttps: Preference<Boolean> = preferenceStore.getBoolean("remote_storage_https", true)

    /** Path to the collection under the server root, e.g. `manga/preview`. */
    val folder: Preference<String> = preferenceStore.getString("remote_storage_folder", "")

    /**
     * What the settings held before they were split into the fields above.
     * Read once, at construction, and then cleared. See [adoptLegacyUrl].
     */
    private val legacyUrl: Preference<String> = preferenceStore.getString("remote_storage_url", "")

    val username: Preference<String> = preferenceStore.getString("remote_storage_username", "")

    /**
     * Stored via [Preference.privateKey] so it is excluded from backups and from
     * the settings export, the same treatment tracker passwords get.
     */
    val password: Preference<String> = preferenceStore.getString(
        Preference.privateKey("remote_storage_password"),
        "",
    )

    /**
     * Free local space by deleting the local copy of a chapter the server has
     * been verified to hold. [evictWhen] decides *when* that happens; this is
     * only the master switch.
     */
    val evictAfterUpload: Preference<Boolean> = preferenceStore.getBoolean("remote_storage_evict", false)

    /**
     * When a verified-uploaded chapter loses its local copy.
     *
     * Separate from [evictAfterUpload] because "put it on the server and keep a
     * local copy until I have read it" is a completely different product than
     * "put it on the server and drop it straight away", and both are reasonable.
     */
    val evictWhen: Preference<EvictionTiming> =
        preferenceStore.getEnum("remote_storage_evict_when", EvictionTiming.AFTER_UPLOAD)

    /** Grace period for [EvictionTiming.AFTER_DAYS], in days. */
    val evictAfterDays: Preference<Int> = preferenceStore.getInt("remote_storage_evict_days", 7)

    /**
     * What a chapter the user explicitly tapped Download on is treated as.
     *
     * Asking for a chapter by hand is a different act from the app downloading
     * it during a library update, and evicting it ten seconds later because the
     * policy says so is the kind of thing that makes a feature feel broken.
     */
    val manualDownloadKeep: Preference<ManualDownloadKeep> =
        preferenceStore.getEnum("remote_storage_manual_keep", ManualDownloadKeep.CHAPTER)

    /** How many upcoming chapters to pre-fetch while reading an evicted one. */
    val readAheadChapters: Preference<Int> = preferenceStore.getInt("remote_storage_read_ahead", 2)

    /** Only upload/fetch on unmetered connections. */
    val onlyOverWifi: Preference<Boolean> = preferenceStore.getBoolean("remote_storage_wifi_only", true)

    /**
     * Whether the remote follows local deletions.
     *
     * [RemoteRole.MIRROR] means the remote reflects the device: deleting a
     * download deletes the remote copy too. [RemoteRole.ARCHIVE] means the
     * remote keeps everything it has ever been given, and a local delete only
     * frees local space.
     */
    val role: Preference<RemoteRole> = preferenceStore.getEnum("remote_storage_role", RemoteRole.ARCHIVE)

    /** Where a chapter already held remotely is re-downloaded from. */
    val redownloadFrom: Preference<RedownloadSource> =
        preferenceStore.getEnum("remote_storage_redownload_from", RedownloadSource.REMOTE)

    /**
     * Ceiling for the on-demand fetch cache, in MB. Chapters are routinely over
     * 100 MB, so a budget that holds only two or three of them is not a cache;
     * this is a preference rather than a constant for that reason.
     */
    val cacheSizeMb: Preference<Int> = preferenceStore.getInt("remote_storage_cache_mb", 2048)

    init {
        adoptLegacyUrl()
    }

    /** Whether there is enough here to build a client. */
    val isConfigured: Boolean
        get() = host.get().isNotBlank()

    /** `scheme://host[:port]`, with no path. Blank when no host is set. */
    fun serverUrl(): String {
        val host = host.get().trim().trimEnd('/').substringAfter("://")
        if (host.isEmpty()) return ""
        val scheme = if (useHttps.get()) "https" else "http"
        val port = port.get().trim().toIntOrNull()?.takeIf { it in 1..65535 }
        return if (port == null) "$scheme://$host" else "$scheme://$host:$port"
    }

    /** [serverUrl] plus [folder]: the collection chapters are written into. */
    fun collectionUrl(): String {
        val server = serverUrl()
        if (server.isEmpty()) return ""
        val folder = folder.get().trim().trim('/')
        return if (folder.isEmpty()) server else "$server/$folder"
    }

    /**
     * Splits the old single URL field into the parts above.
     *
     * Runs once, when this is first constructed after the update, so that
     * nothing downstream has to know the old field ever existed.
     */
    private fun adoptLegacyUrl() {
        val legacy = legacyUrl.get()
        if (legacy.isBlank() || host.get().isNotBlank()) return

        val parsed = WebDavClient.normalize(legacy) ?: return
        useHttps.set(parsed.scheme == "https")
        host.set(parsed.host)
        port.set(if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) "" else parsed.port.toString())
        folder.set(parsed.pathSegments.filter { it.isNotEmpty() }.joinToString("/"))
        legacyUrl.delete()
    }
}

enum class RemoteRole {
    /** Remote reflects the device; local deletes propagate. */
    MIRROR,

    /** Remote keeps everything; local deletes are local only. */
    ARCHIVE,
}

enum class EvictionTiming {
    /** As soon as the server is verified to hold it. Frees the most space. */
    AFTER_UPLOAD,

    /** Once the chapter has been read. Keeps the backlog on the device. */
    AFTER_READING,

    /** A fixed number of days after the local copy was last written. */
    AFTER_DAYS,
}

enum class ManualDownloadKeep {
    /** Keep that one chapter on the device until the user deletes it. */
    CHAPTER,

    /** Keep every chapter of that series on the device. */
    SERIES,

    /** Treat it like any other download; the eviction policy applies. */
    NONE,
}

enum class RedownloadSource {
    /** Pull the archive back from the remote. Fast, and no load on the source. */
    REMOTE,

    /** Always re-download from the original source, as upstream does. */
    SOURCE,
}
