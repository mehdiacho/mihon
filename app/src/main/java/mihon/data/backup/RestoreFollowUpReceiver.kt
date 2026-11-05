package mihon.data.backup

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import eu.kanade.tachiyomi.BuildConfig.APPLICATION_ID as ID

/**
 * The buttons on [RestoreFollowUp]'s download offer. Separate from the app's NotificationReceiver
 * so the fork's additions stay in files of their own.
 */
class RestoreFollowUpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val followUp = context.appGraph.restoreFollowUp
        when (intent.action) {
            ACTION_DOWNLOAD_NONE -> followUp.declineDownloads()
            ACTION_DOWNLOAD_ALL, ACTION_DOWNLOAD_UNREAD -> {
                // Queuing reads the database for every series, which is too long for onReceive
                val result = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        followUp.downloadOffered(unreadOnly = intent.action == ACTION_DOWNLOAD_UNREAD)
                    } finally {
                        result.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val NAME = "RestoreFollowUpReceiver"

        const val ACTION_DOWNLOAD_ALL = "$ID.$NAME.DOWNLOAD_ALL"
        const val ACTION_DOWNLOAD_UNREAD = "$ID.$NAME.DOWNLOAD_UNREAD"
        const val ACTION_DOWNLOAD_NONE = "$ID.$NAME.DOWNLOAD_NONE"

        internal fun broadcast(context: Context, action: String): PendingIntent {
            val intent = Intent(context, RestoreFollowUpReceiver::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
