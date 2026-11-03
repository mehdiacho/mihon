package mihon.data.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import rikka.shizuku.Shizuku
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.i18n.MR
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * What a restore leaves for afterwards: the parts of a backup that can't simply be written to the
 * database, because they need an apk installed, a download started, or the user to sign in.
 *
 * Anything that would take space or change what is installed is asked first, once, from a
 * notification. The one exception is installing extensions through Shizuku, which the user already
 * chose as a way to install without being asked.
 *
 * What was offered is kept in files rather than memory. The notification can sit in the shade for
 * hours, and the process that posted it is long gone by the time anyone taps it.
 */
@Inject
@SingleIn(AppScope::class)
class RestoreFollowUp(
    private val context: Context,
    private val extensionManager: ExtensionManager,
    private val basePreferences: BasePreferences,
    private val trackerManager: TrackerManager,
    private val downloadManager: DownloadManager,
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val json: Json,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dir get() = File(context.filesDir, "restore_follow_up").apply { mkdirs() }
    private val pendingExtensionsFile get() = File(dir, "extensions.json")
    private val pendingDownloadsFile get() = File(dir, "downloads.json")

    // region Extensions

    /**
     * Installs what the backup had and this device lacks, from the stores the restore just added.
     *
     * Returns one line per extension that can't be installed, for the restore's error log.
     */
    suspend fun restoreExtensions(extensions: List<BackupExtension>, sources: List<BackupSource>): List<String> {
        extensionManager.findAvailableExtensions()
        val available = extensionManager.availableExtensionsFlow.value
        if (available.isEmpty()) return emptyList()

        val installed = extensionManager.getLoadedExtensions() + extensionManager.getNotLoadedExtensions()
        val installedPackages = installed.mapTo(HashSet()) { it.pkgName }

        val skipped = mutableListOf<String>()
        val toInstall = if (extensions.isNotEmpty()) {
            val byPackage = available.associateBy { it.pkgName }
            extensions
                .filter { it.pkgName !in installedPackages }
                .mapNotNull { wanted ->
                    val label = wanted.name ?: wanted.pkgName
                    val candidate = byPackage[wanted.pkgName]
                    when {
                        candidate == null -> {
                            skipped += context.stringResource(MR.strings.restore_extension_not_in_stores, label)
                            null
                        }
                        // A store vouches for one signing key. Installing a different signer's
                        // build under the same name would swap the code behind the restored
                        // library for someone else's.
                        wanted.signatureHash != null && wanted.signatureHash != candidate.store.signingKey -> {
                            skipped += context.stringResource(MR.strings.restore_extension_signer_differs, label)
                            null
                        }
                        else -> candidate
                    }
                }
        } else {
            // Backups from before tag 900 only name sources. Find whichever listed extension
            // provides each one this device can't already serve.
            val servedHere = installed
                .filterIsInstance<Extension.Loaded>()
                .flatMapTo(HashSet()) { ext -> ext.sources.map { it.id } }
            val wantedIds = sources.map { it.sourceId }.filterTo(HashSet()) { it !in servedHere }
            available
                .filter { it.pkgName !in installedPackages }
                .filter { ext -> ext.sources.any { it.id in wantedIds } }
        }

        if (toInstall.isEmpty()) return skipped

        if (canInstallSilently()) {
            toInstall.forEach { install(it) }
        } else {
            writePending(pendingExtensionsFile, PendingExtensions(toInstall.map { it.pkgName }))
            offerExtensions(toInstall.size)
        }
        return skipped
    }

    private fun canInstallSilently(): Boolean {
        if (basePreferences.extensionInstaller.get() != BasePreferences.ExtensionInstaller.SHIZUKU) return false
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Shizuku not reachable" }
            false
        }
    }

    /** One at a time, so a store that's slow to serve one apk doesn't fail the rest. */
    private suspend fun install(extension: Extension.Available) {
        val step = withTimeoutOrNull(INSTALL_TIMEOUT) {
            extensionManager.installExtension(extension).first { it.isCompleted() }
        }
        logcat(LogPriority.INFO) { "Restore installed ${extension.pkgName}: $step" }
    }

    /** The "Install all" button. Started from the app so the installer's own prompts can show. */
    fun installPending() {
        val pending = readPending<PendingExtensions>(pendingExtensionsFile) ?: return
        pendingExtensionsFile.delete()
        context.cancelNotification(ID_EXTENSIONS)

        scope.launch {
            if (extensionManager.availableExtensionsFlow.value.isEmpty()) {
                extensionManager.findAvailableExtensions()
            }
            val byPackage = extensionManager.availableExtensionsFlow.value.associateBy { it.pkgName }
            pending.packages.mapNotNull { byPackage[it] }.forEach { install(it) }
        }
    }

    private fun offerExtensions(count: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_INSTALL_EXTENSIONS
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val install = PendingIntent.getActivity(context, ID_EXTENSIONS, intent, PENDING_FLAGS)

        notify(
            id = ID_EXTENSIONS,
            title = context.stringResource(MR.strings.restore_extensions_title),
            text = context.pluralStringResource(MR.plurals.restore_extensions_missing, count, count),
        ) {
            setContentIntent(install)
            addAction(
                R.drawable.ic_download_chapter_24dp,
                context.stringResource(MR.strings.restore_install_all),
                install,
            )
        }
    }

    // endregion

    // region Downloads

    /**
     * Asks whether to download here what was downloaded on the other device. Only asks: a library
     * restored onto a phone with less space than the one it came from shouldn't start filling it.
     */
    fun offerDownloads(mangas: List<BackupManga>) {
        val series = mangas.mapNotNull { manga ->
            manga.chapters
                .filter { it.downloaded }
                .map { it.url }
                .takeIf { it.isNotEmpty() }
                ?.let { PendingSeries(manga.source, manga.url, it) }
        }
        val count = series.sumOf { it.chapterUrls.size }
        if (count == 0) return

        writePending(pendingDownloadsFile, PendingDownloads(series))

        notify(
            id = ID_DOWNLOADS,
            title = context.stringResource(MR.strings.restore_downloads_title),
            text = context.pluralStringResource(MR.plurals.restore_downloads_offer, count, count),
        ) {
            addAction(
                R.drawable.ic_download_chapter_24dp,
                context.stringResource(MR.strings.all),
                RestoreFollowUpReceiver.broadcast(context, RestoreFollowUpReceiver.ACTION_DOWNLOAD_ALL),
            )
            addAction(
                R.drawable.ic_download_chapter_24dp,
                context.stringResource(MR.strings.restore_downloads_unread_only),
                RestoreFollowUpReceiver.broadcast(context, RestoreFollowUpReceiver.ACTION_DOWNLOAD_UNREAD),
            )
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.restore_downloads_no),
                RestoreFollowUpReceiver.broadcast(context, RestoreFollowUpReceiver.ACTION_DOWNLOAD_NONE),
            )
        }
    }

    suspend fun downloadOffered(unreadOnly: Boolean) {
        val pending = readPending<PendingDownloads>(pendingDownloadsFile)
        declineDownloads()
        pending ?: return

        pending.series.forEach { series ->
            val manga = getMangaByUrlAndSourceId.await(series.mangaUrl, series.source) ?: return@forEach
            val wanted = series.chapterUrls.toHashSet()
            val chapters = getChaptersByMangaId.await(manga.id)
                .filter { it.url in wanted && (!unreadOnly || !it.read) }
            if (chapters.isNotEmpty()) {
                downloadManager.downloadChapters(manga, chapters, autoStart = false)
            }
        }
        downloadManager.startDownloads()
    }

    fun declineDownloads() {
        pendingDownloadsFile.delete()
        context.cancelNotification(ID_DOWNLOADS)
    }

    // endregion

    // region Trackers

    /**
     * Tracking rows come back with the library, but the logins behind them deliberately don't.
     * Without this, tracking would silently stop at the restore until someone noticed.
     */
    fun promptTrackerSignIn(mangas: List<BackupManga>) {
        val ids = mangas.flatMapTo(HashSet()) { manga -> manga.tracking.map { it.syncId.toLong() } }
        val signedOut = trackerManager.getAll(ids).filterNot { it.isLoggedIn }
        if (signedOut.isEmpty()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_SIGN_IN_TRACKERS
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        notify(
            id = ID_TRACKERS,
            title = context.stringResource(MR.strings.restore_trackers_title),
            text = context.stringResource(
                MR.strings.restore_trackers_sign_in,
                signedOut.joinToString { it.name },
            ),
        ) {
            setContentIntent(PendingIntent.getActivity(context, ID_TRACKERS, intent, PENDING_FLAGS))
        }
    }

    fun dismissTrackerPrompt() = context.cancelNotification(ID_TRACKERS)

    // endregion

    private fun notify(id: Int, title: String, text: String, block: NotificationCompat.Builder.() -> Unit) {
        val builder = context.notificationBuilder(Notifications.CHANNEL_BACKUP_RESTORE_COMPLETE) {
            setSmallIcon(R.drawable.ic_mihon)
            setAutoCancel(true)
            setContentTitle(title)
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            block()
        }
        context.notify(id, builder.build())
    }

    private inline fun <reified T> writePending(file: File, value: T) {
        file.writeText(json.encodeToString(value))
    }

    private inline fun <reified T> readPending(file: File): T? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString<T>(file.readText())
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Unreadable ${file.name}" }
            null
        }
    }

    @Serializable
    private data class PendingExtensions(val packages: List<String>)

    @Serializable
    private data class PendingDownloads(val series: List<PendingSeries>)

    @Serializable
    private data class PendingSeries(val source: Long, val mangaUrl: String, val chapterUrls: List<String>)

    companion object {
        const val ACTION_INSTALL_EXTENSIONS = "mihon.restore.INSTALL_EXTENSIONS"
        const val ACTION_SIGN_IN_TRACKERS = "mihon.restore.SIGN_IN_TRACKERS"

        private const val ID_EXTENSIONS = -505
        private const val ID_DOWNLOADS = -506
        private const val ID_TRACKERS = -507

        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private val INSTALL_TIMEOUT = 3.minutes
    }
}
