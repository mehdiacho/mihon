package eu.kanade.tachiyomi.data.backup.create

import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import io.mockk.mockk
import mihon.data.remote.RemoteStoragePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Fork: the NAS password only ever leaves the phone sealed, never as a plain preference. */
class PreferenceBackupCreatorTest {

    // InMemoryPreferenceStore.getAll() answers with Preference objects, not values, so only getAll is faked
    private val store = object : PreferenceStore by InMemoryPreferenceStore() {
        override fun getAll(): Map<String, *> = mapOf(
            "remote_storage_host" to "nas.example.test",
            Preference.privateKey("tracker_token") to "token-for-tests",
            RemoteStoragePreferences.PASSWORD_KEY to "not-a-real-password",
        )
    }
    private val creator = PreferenceBackupCreator(mockk(relaxed = true), store)

    @Test
    fun `sensitive settings include private keys but never the NAS password`() {
        val keys = creator.createApp(includePrivatePreferences = true).map { it.key }.toSet()

        assertEquals(setOf("remote_storage_host", Preference.privateKey("tracker_token")), keys)
    }

    @Test
    fun `without sensitive settings neither goes in`() {
        val keys = creator.createApp(includePrivatePreferences = false).map { it.key }.toSet()

        assertEquals(setOf("remote_storage_host"), keys)
    }
}
