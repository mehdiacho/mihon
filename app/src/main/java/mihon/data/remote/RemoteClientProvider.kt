package mihon.data.remote

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper

/**
 * Builds a [RemoteStorage] from the current settings.
 *
 * A single place that knows which protocol is configured, so that adding one --
 * SMB is the obvious next -- means adding a branch here rather than touching
 * every caller. Clients are cheap and settings can change at any time, so this
 * builds on demand rather than caching one.
 */
@Inject
@SingleIn(AppScope::class)
class RemoteClientProvider(
    private val preferences: RemoteStoragePreferences,
    private val networkHelper: NetworkHelper,
) {

    /** Null when mirroring is off or the settings cannot form a valid client. */
    fun get(): RemoteStorage? {
        if (!preferences.enabled.get()) return null
        return build()
    }

    /**
     * Builds a client from the current settings regardless of the enabled flag,
     * so that "Test connection" works before the feature is switched on.
     */
    fun build(
        url: String = preferences.url.get(),
        username: String = preferences.username.get(),
        password: String = preferences.password.get(),
    ): RemoteStorage? {
        if (url.isBlank()) return null
        return runCatching {
            WebDavClient(
                client = networkHelper.client,
                baseUrl = url,
                username = username,
                password = password,
            )
        }.getOrNull()
    }
}
