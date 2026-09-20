package mihon.data.remote

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Chapters and series the user has asked to keep on this device, exempt from
 * whatever the eviction policy would otherwise do to them.
 *
 * Pins are stored as remote path prefixes -- `<source>/<manga>` for a series and
 * `<source>/<manga>/<chapter>.cbz` for a single chapter -- so one containment
 * test answers both, and a pin survives the chapter row being rebuilt from the
 * filesystem.
 */
@Inject
@SingleIn(AppScope::class)
class RemoteKeepStore(
    preferenceStore: PreferenceStore,
) {

    /**
     * App state rather than a setting: it is a record of what the user did on
     * this device, not something to carry to another one, and restoring it onto
     * a device whose downloads directory is empty would pin nothing.
     */
    private val pinned: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("remote_storage_pinned"),
        emptySet(),
    )

    fun changes() = pinned.changes()

    /** Whether [segments] is pinned, directly or by its series being pinned. */
    fun isKept(segments: List<String>): Boolean {
        if (segments.isEmpty()) return false
        val current = pinned.get()
        if (current.isEmpty()) return false
        return keyOf(segments) in current || seriesKeyOf(segments) in current
    }

    fun isSeriesKept(sourceDirName: String, mangaDirName: String): Boolean =
        "$sourceDirName/$mangaDirName" in pinned.get()

    fun keepChapter(segments: List<String>) {
        pinned.set(pinned.get() + keyOf(segments))
    }

    fun keepSeries(sourceDirName: String, mangaDirName: String) {
        pinned.set(pinned.get() + "$sourceDirName/$mangaDirName")
    }

    /**
     * Drops the chapter pin and, if the whole series was pinned, the series pin
     * too. Releasing one chapter of a pinned series while leaving the series
     * pinned would be a no-op the user could not see the reason for.
     */
    fun release(segments: List<String>) {
        val current = pinned.get()
        val updated = current - keyOf(segments) - seriesKeyOf(segments)
        if (updated != current) pinned.set(updated)
    }

    fun releaseSeries(sourceDirName: String, mangaDirName: String) {
        val prefix = "$sourceDirName/$mangaDirName"
        pinned.set(pinned.get().filterNot { it == prefix || it.startsWith("$prefix/") }.toSet())
    }

    private fun keyOf(segments: List<String>) = segments.joinToString("/")

    private fun seriesKeyOf(segments: List<String>) = segments.take(2).joinToString("/")
}
