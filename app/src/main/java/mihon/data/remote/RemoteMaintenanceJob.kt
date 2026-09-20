package mihon.data.remote

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * Runs the deferred eviction sweep.
 *
 * Twice a day, not hourly: the conditions it checks are a chapter having been
 * read and a grace period having elapsed, and neither is urgent. Requires a
 * network because deciding to delete a local copy means first confirming the
 * server still has it.
 */
class RemoteMaintenanceJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject lateinit var maintenance: RemoteMaintenance

    override suspend fun doWork(): Result {
        graph.inject(this)

        return runCatching { maintenance.sweep() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = {
                    logcat(LogPriority.ERROR, it) { "Remote maintenance sweep failed" }
                    Result.failure()
                },
            )
    }

    companion object {
        private const val TAG = "RemoteMaintenance"

        fun setupTask(context: Context) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = PeriodicWorkRequestBuilder<RemoteMaintenanceJob>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag(TAG)
                .build()

            context.workManager.enqueueUniquePeriodicWork(
                TAG,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
