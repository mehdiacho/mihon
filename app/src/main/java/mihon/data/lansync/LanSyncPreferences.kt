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

    /**
     * This install's profile in the TachiMana sense: one install is one profile. Made on first use
     * and kept, except when the user chooses to join a computer's profile while linking.
     */
    private val profileIdPref: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("lan_sync_profile_id"), "")

    val profileId: String
        get() = profileIdPref.get().ifEmpty { UUID.randomUUID().toString().also(profileIdPref::set) }

    /** Blank means the app's label. */
    val profileName: Preference<String> =
        preferenceStore.getString(Preference.appStateKey("lan_sync_profile_name"), "")

    /** JSON list of [LanSyncLink.Link]. App state like the rest: keys must never travel in a backup. */
    val links: Preference<String> = preferenceStore.getString(Preference.appStateKey("lan_sync_links"), "")

    /** Joining another device's profile, from a link the user confirmed. */
    fun adoptProfile(id: String, name: String) {
        profileIdPref.set(id)
        profileName.set(name)
    }

    /** The current code, making one the first time it's needed. */
    fun currentPairCode(): String = pairCode.get().ifEmpty { newPairCode() }

    fun newPairCode(): String {
        val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        pairCode.set(code)
        return code
    }
}
