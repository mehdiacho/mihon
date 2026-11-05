package mihon.data.remote

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds

/**
 * Periodically checks that the remote is still answering.
 *
 * Three states rather than two, because "reachable but slow" is the case that
 * actually explains a bad experience -- a chapter that takes a minute to open
 * looks like a broken app, not a slow server, unless something says otherwise.
 */
@Inject
@SingleIn(AppScope::class)
class RemoteHealth(
    private val preferences: RemoteStoragePreferences,
    private val clientProvider: RemoteClientProvider,
) {

    enum class Status {
        /** Not checked yet, or mirroring is off. Never shown as healthy. */
        UNKNOWN,

        /** Answered promptly. */
        OK,

        /** Answered, but slowly, or failed once after having worked. */
        DEGRADED,

        /** Failed twice running. */
        FAILED,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("RemoteHealth"))

    private val _status = MutableStateFlow(Status.UNKNOWN)
    val status = _status.asStateFlow()

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs = _latencyMs.asStateFlow()

    private var consecutiveFailures = 0

    init {
        scope.launch {
            while (true) {
                if (preferences.enabled.get() && preferences.isConfigured) {
                    check()
                } else {
                    _status.value = Status.UNKNOWN
                    _latencyMs.value = null
                }
                delay(POLL_INTERVAL)
            }
        }
    }

    /** Runs one check now. Safe to call from the UI; it hops to IO itself. */
    suspend fun check() {
        val client = clientProvider.build()
        if (client == null) {
            _status.value = Status.UNKNOWN
            return
        }

        var result: Result<Unit>
        val elapsed = measureTimeMillis {
            result = withContext(Dispatchers.IO) { client.testConnection() }
        }

        if (result.isSuccess) {
            consecutiveFailures = 0
            _latencyMs.value = elapsed
            _status.value = if (elapsed > SLOW_THRESHOLD_MS) Status.DEGRADED else Status.OK
        } else {
            consecutiveFailures++
            _latencyMs.value = null
            // One failure is a blip -- a phone changing networks produces them
            // routinely. Two in a row is a server that is actually gone.
            _status.value = if (consecutiveFailures >= 2) Status.FAILED else Status.DEGRADED
        }
    }

    companion object {
        private val POLL_INTERVAL = 60.seconds
        private const val SLOW_THRESHOLD_MS = 2_000L
    }
}
