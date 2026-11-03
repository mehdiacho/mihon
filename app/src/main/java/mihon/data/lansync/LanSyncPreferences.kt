package mihon.data.lansync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.security.SecureRandom
import java.util.UUID

/**
 * Settings for letting other devices pull from this phone over the LAN.
 *
 * All of them are app state, which backups never carry: a restore on another device must not
 * switch sharing on, copy this phone's pairing code, or claim to be this phone.
 */
@Inject
@SingleIn(AppScope::class)
class LanSyncPreferences(
    preferenceStore: PreferenceStore,
) {

    /** Off by default. Serving the library to the LAN is something to choose, not discover. */
    val enabled: Preference<Boolean> = preferenceStore.getBoolean(Preference.appStateKey("lan_sync_enabled"), false)

    val pairCode: Preference<String> = preferenceStore.getString(Preference.appStateKey("lan_sync_pair_code"), "")

    /** Stable across launches, so a puller can tell this phone is the one that made its backup. */
    private val deviceIdPref: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("lan_sync_device_id"), "")

    val deviceId: String
        get() = deviceIdPref.get().ifEmpty { UUID.randomUUID().toString().also(deviceIdPref::set) }

    /** The current code, making one the first time it's needed. */
    fun currentPairCode(): String = pairCode.get().ifEmpty { newPairCode() }

    fun newPairCode(): String {
        val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        pairCode.set(code)
        return code
    }
}
