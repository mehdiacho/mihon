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
        url: String = preferences.collectionUrl(),
        username: String = preferences.username.get(),
        password: String = preferences.password.get(),
    ): RemoteStorage? = buildAt(url, username, password)

    /**
     * A client rooted at the server itself rather than at the collection, so
     * the folder picker can list what is up there before one is chosen.
     */
    fun buildServerRoot(
        url: String = preferences.serverUrl(),
        username: String = preferences.username.get(),
        password: String = preferences.password.get(),
    ): WebDavClient? = buildAt(url, username, password)

    private fun buildAt(url: String, username: String, password: String): WebDavClient? {
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
