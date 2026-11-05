package mihon.data.lansync

import android.content.Context
import android.net.Uri
import androidx.work.WorkManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** This phone asking another device (normally a PC running TachiMana) for a link. */
@Inject
@SingleIn(AppScope::class)
class LanSyncLinker(
    private val context: Context,
    private val preferences: LanSyncPreferences,
    private val links: LanSyncLinks,
    private val server: LanSyncServer,
    private val getFavorites: GetFavorites,
) {

    sealed interface Result {
        data class Linked(val link: LanSyncLink.Link) : Result

        /** The other device is a different profile. Nothing was stored on either side. */
        data class OtherProfile(val peer: LanSyncLink.LinkResponse) : Result
        data object WrongCode : Result
        data object LockedOut : Result
        data class Failed(val reason: String) : Result
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // Plain LAN HTTP. The other side may be building a backup for /backup, which takes a while
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    suspend fun link(host: String, port: Int, code: String): Result = withContext(Dispatchers.IO) {
        val ownPort = (server.state.value as? LanSyncServer.State.Running)?.port
        val body = LanSyncLink.LinkRequest(
            deviceId = preferences.deviceId,
            deviceName = server.deviceName.take(LanSyncLink.MAX_FIELD_CHARS),
            profileId = links.profileId,
            profileName = links.profileName.take(LanSyncLink.MAX_FIELD_CHARS),
            platform = LanSyncLink.PLATFORM_ANDROID,
            port = ownPort,
        )
        val request = Request.Builder()
            .url("http://$host:$port/link")
            .header(LanSyncProtocol.PAIR_CODE_HEADER, code.filter(Char::isDigit))
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                when (response.code) {
                    200 -> {
                        val answer = json.decodeFromString<LanSyncLink.LinkResponse>(text)
                        val key = answer.key ?: return@withContext Result.Failed("no key in the answer")
                        val link = LanSyncLink.Link(
                            peerDeviceId = answer.deviceId,
                            peerName = answer.deviceName,
                            profileId = answer.profileId,
                            key = key,
                            lastAddress = "$host:$port",
                            platform = LanSyncLink.PLATFORM_DESKTOP,
                        )
                        links.save(link)
                        Result.Linked(link)
                    }
                    409 -> Result.OtherProfile(json.decodeFromString(text))
                    401 -> Result.WrongCode
                    429 -> Result.LockedOut
                    else -> Result.Failed("HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "LAN sync link to $host:$port failed" }
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Only an install with nothing in its library may join another profile. Taking on a computer's
     * profile with a library of its own would be merging two libraries without saying so.
     */
    suspend fun canJoinOtherProfile(): Boolean = getFavorites.await().isEmpty()

    fun joinProfile(peer: LanSyncLink.LinkResponse) {
        preferences.adoptProfile(peer.profileId, peer.profileName)
    }

    /** Pulls the linked device's backup and starts the normal restore with it. */
    suspend fun restoreBackupFrom(link: LanSyncLink.Link) = withContext(Dispatchers.IO) {
        val address = link.lastAddress ?: throw IOException("No address for ${link.peerName}")
        val request = Request.Builder()
            .url("http://$address/backup")
            .header(LanSyncLink.LINK_KEY_HEADER, link.key)
            .build()
        val file = File(File(context.cacheDir, "lansync").apply { mkdirs() }, "linked-backup.tachibk")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            file.outputStream().use { response.body.byteStream().copyTo(it) }
        }
        BackupRestoreJob.start(WorkManager.getInstance(context), Uri.fromFile(file), RestoreOptions())
    }
}
