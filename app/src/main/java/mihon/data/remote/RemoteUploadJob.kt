package mihon.data.remote

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * Drains the pending-upload queue.
 *
 * Uploading from an in-process coroutine was enough to prove the idea and not
 * enough to ship it: a 100 MB transfer outlives the foreground, and Android
 * kills backgrounded processes freely. WorkManager gives the queue a retry with
 * backoff, a network constraint, and -- the point -- survival across the app
 * being killed or the phone being rebooted.
 */
class RemoteUploadJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject lateinit var mirror: RemoteMirror

    @Inject lateinit var uploadQueue: RemoteUploadQueue

    @Inject lateinit var preferences: RemoteStoragePreferences

    override suspend fun doWork(): Result {
        graph.inject(this)

        if (!preferences.enabled.get() || preferences.url.get().isBlank()) {
            // Nothing can drain while the feature is off. Keep the queue rather
            // than discarding it: switching mirroring back on should pick up
            // where it left off.
            return Result.success()
        }

        var anyFailed = false
        withIOContext {
            // Re-read the queue each pass rather than snapshotting it, so a
            // chapter that finished downloading while this was running is
            // picked up in the same run instead of waiting for the next.
            for (entry in uploadQueue.items()) {
                if (isStopped) return@withIOContext
                val done = runCatching { mirror.uploadOne(entry) }
                    .onFailure { logcat(LogPriority.ERROR, it) { "Mirror upload failed: ${entry.segments}" } }
                    .getOrDefault(false)
                if (!done) anyFailed = true
            }
        }

        return when {
            uploadQueue.isEmpty() -> Result.success()
            // Give up scheduling after enough attempts that the cause is
            // clearly not transient. The entries stay on disk, so the next
            // download -- or the settings screen -- starts the job again.
            anyFailed && runAttemptCount >= MAX_ATTEMPTS -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        private const val TAG = "RemoteUpload"
        private const val MAX_ATTEMPTS = 5

        fun start(context: Context, unmeteredOnly: Boolean) {
            val constraints = Constraints(
                requiredNetworkType = if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<RemoteUploadJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            // KEEP rather than REPLACE: a run already in progress is uploading a
            // chapter, and restarting it on every completed download would mean
            // large transfers never finish on a busy queue.
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }
    }
}
