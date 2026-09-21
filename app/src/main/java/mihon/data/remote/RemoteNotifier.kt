package mihon.data.remote

import android.content.Context
import androidx.core.app.NotificationCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Notifications for the long-running remote storage actions.
 *
 * Uploading a library, asking the server what it has and reclaiming space all
 * take minutes, and all three were visible only as a line of text on the
 * settings screen -- which meant standing on that screen to watch them. The
 * library updater and the extension installer both report from the shade; so
 * should these.
 *
 * One progress notification at a time, deliberately. The three are started by
 * hand from the same screen and are not meant to overlap; three competing
 * progress bars would be worse than one that is occasionally reused.
 *
 * All the wording lives here rather than at the call sites, so the settings
 * row and the notification cannot end up describing the same run differently.
 */
@Inject
@SingleIn(AppScope::class)
class RemoteNotifier(
    private val context: Context,
) {

    private fun progress(title: String, text: String, current: Int = 0, total: Int = 0) {
        val builder = context.notificationBuilder(Notifications.CHANNEL_REMOTE_STORAGE_PROGRESS) {
            setSmallIcon(R.drawable.ic_mihon)
            setAutoCancel(false)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setContentTitle(title)
            setContentText(text)
            // An indeterminate bar for work whose length is not known yet,
            // rather than a determinate one sitting at zero.
            if (total > 0) setProgress(total, current, false) else setProgress(0, 0, true)
        }
        context.notify(Notifications.ID_REMOTE_STORAGE_PROGRESS, builder.build())
    }

    private fun complete(title: String, text: String) {
        dismissProgress()
        val builder = context.notificationBuilder(Notifications.CHANNEL_REMOTE_STORAGE_COMPLETE) {
            setSmallIcon(R.drawable.ic_mihon)
            setAutoCancel(true)
            setContentTitle(title)
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        context.notify(Notifications.ID_REMOTE_STORAGE_COMPLETE, builder.build())
    }

    fun dismissProgress() = context.cancelNotification(Notifications.ID_REMOTE_STORAGE_PROGRESS)

    // region Upload existing downloads

    fun uploadScanning(found: Int) = progress(
        title = context.stringResource(MR.strings.pref_remote_storage_mirror_existing),
        text = context.stringResource(MR.strings.pref_remote_storage_mirror_existing_scanning, found),
    )

    fun uploadProgress(done: Int, total: Int, current: String) = progress(
        title = context.stringResource(MR.strings.pref_remote_storage_mirror_existing),
        text = context.stringResource(MR.strings.pref_remote_storage_mirror_existing_running, done, total, current),
        current = done,
        total = total,
    )

    fun uploadDone(uploaded: Int, skipped: Int, failed: Int, freedBytes: Long) = complete(
        title = context.stringResource(MR.strings.pref_remote_storage_mirror_existing),
        text = if (freedBytes > 0L) {
            context.stringResource(
                MR.strings.pref_remote_storage_mirror_existing_done_freed,
                uploaded,
                skipped,
                failed,
                formatBytes(freedBytes),
            )
        } else {
            context.stringResource(MR.strings.pref_remote_storage_mirror_existing_done, uploaded, skipped, failed)
        },
    )

    // endregion

    // region Check what the server has

    fun sweepProgress(done: Int, total: Int, chapters: Int) = progress(
        title = context.stringResource(MR.strings.pref_remote_storage_sweep),
        text = context.stringResource(MR.strings.pref_remote_storage_sweep_running, done, total, chapters),
        current = done,
        total = total,
    )

    fun sweepDone(series: Int, chapters: Int, unreachable: Int) = complete(
        title = context.stringResource(MR.strings.pref_remote_storage_sweep),
        text = if (unreachable > 0) {
            context.stringResource(
                MR.strings.pref_remote_storage_sweep_done_unreachable,
                chapters,
                series,
                unreachable,
            )
        } else {
            context.stringResource(MR.strings.pref_remote_storage_sweep_done, chapters, series)
        },
    )

    // endregion

    // region Free up space

    fun reclaimProgress(evicted: Int, freedBytes: Long) = progress(
        title = context.stringResource(MR.strings.pref_remote_storage_reclaim),
        text = context.stringResource(
            MR.strings.pref_remote_storage_reclaim_running,
            formatBytes(freedBytes),
            evicted,
        ),
    )

    fun reclaimDone(evicted: Int, freedBytes: Long) = complete(
        title = context.stringResource(MR.strings.pref_remote_storage_reclaim),
        text = if (evicted == 0) {
            context.stringResource(MR.strings.pref_remote_storage_reclaim_none)
        } else {
            context.stringResource(MR.strings.pref_remote_storage_reclaim_done, formatBytes(freedBytes), evicted)
        },
    )

    // endregion
}

/** Whole GB or MB. A byte count to three decimal places is not information. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%d MB".format(bytes / (1024 * 1024))
    else -> "%d KB".format(bytes / 1024)
}
