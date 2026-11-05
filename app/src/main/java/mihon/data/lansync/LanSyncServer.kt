package mihon.data.lansync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Serves [LanSyncProtocol] while "Let other devices pull from this phone" is on.
 *
 * It runs for as long as the app's process does. Android may stop a backgrounded app, so the
 * Sync screen says to keep the app open while another device pulls.
 */
@Inject
@SingleIn(AppScope::class)
class LanSyncServer(
    private val context: Context,
    private val preferences: LanSyncPreferences,
    private val catalog: LanSyncCatalog,
    private val links: LanSyncLinks,
    private val queue: LanSyncQueue,
) {

    sealed interface State {
        data object Off : State
        data class Running(val port: Int, val addresses: List<String>) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    // explicitNulls off so a 409's LinkResponse leaves `key` out rather than sending null
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }
    private val lockout = PairCodeLockout()

    private var socket: ServerSocket? = null
    private var workers: ExecutorService? = null
    private var registration: NsdManager.RegistrationListener? = null

    val deviceName: String
        get() = Settings.Global.getString(context.contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    /** Follows the setting for the life of the process. Called once, from the application. */
    fun bind(scope: CoroutineScope) {
        preferences.enabled.changes()
            .distinctUntilChanged()
            .onEach { enabled -> if (enabled) start() else stop() }
            .launchIn(scope)
    }

    @Synchronized
    fun start() {
        if (socket != null) return
        preferences.currentPairCode()

        val server = (0 until LanSyncProtocol.PORT_ATTEMPTS).firstNotNullOfOrNull { offset ->
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(LanSyncProtocol.DEFAULT_PORT + offset))
                }
            } catch (_: IOException) {
                null
            }
        }
        if (server == null) {
            _state.value = State.Failed("No free port from ${LanSyncProtocol.DEFAULT_PORT}")
            return
        }
        socket = server
        lockout.reset()

        // A few pulls at once at most; past that a caller gets refused rather than queued forever
        val pool = ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue())
        workers = pool
        Thread({ acceptLoop(server, pool) }, "LanSyncAccept").apply { isDaemon = true }.start()

        advertise(server.localPort)
        _state.value = State.Running(server.localPort, localAddresses())
        logcat(LogPriority.INFO) { "LAN sync serving on port ${server.localPort}" }
    }

    @Synchronized
    fun stop() {
        registration?.let { listener ->
            runCatching { context.getSystemService(NsdManager::class.java).unregisterService(listener) }
        }
        registration = null
        runCatching { socket?.close() }
        socket = null
        workers?.shutdownNow()
        workers = null
        _state.value = State.Off
    }

    private fun acceptLoop(server: ServerSocket, pool: ExecutorService) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            try {
                pool.execute { client.use(::handle) }
            } catch (_: RejectedExecutionException) {
                runCatching {
                    client.use {
                        respondJson(it.getOutputStream(), 503, LanSyncProtocol.Error("busy"))
                    }
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val out = BufferedOutputStream(client.getOutputStream())
        try {
            val request = LanSyncProtocol.readRequest(client.getInputStream())
            val linkKey = request?.header(LanSyncLink.LINK_KEY_HEADER)
            when {
                request == null -> respondJson(out, 400, LanSyncProtocol.Error("bad request"))
                request.path == "/link" -> link(request, client, out)
                request.path in KEYED_ROUTES -> keyed(request, client, out)
                request.method != "GET" -> respondJson(out, 405, LanSyncProtocol.Error("GET only"))
                request.path == "/ping" -> respondJson(
                    out,
                    200,
                    LanSyncProtocol.Ping(preferences.deviceId, deviceName, links.profileId, links.profileName),
                )
                request.path !in AUTHED_ROUTES -> respondJson(out, 404, LanSyncProtocol.Error("not found"))
                // A linked device skips the code, and the lockout: someone guessing codes mustn't
                // cut off a device that already has a key
                linkKey != null -> {
                    val link = links.findByKey(linkKey)
                    if (link == null) {
                        respondJson(out, 401, LanSyncProtocol.Error("unknown link key"))
                    } else {
                        client.inetAddress?.hostAddress?.let { host ->
                            links.touch(link, withPort(host, link.lastAddress))
                        }
                        serve(request, out)
                    }
                }
                lockout.isLocked() -> respondJson(out, 429, LanSyncProtocol.Error("too many wrong codes, try later"))
                !LanSyncProtocol.codeMatches(
                    preferences.currentPairCode(),
                    request.header(LanSyncProtocol.PAIR_CODE_HEADER),
                ) -> {
                    lockout.recordFailure()
                    respondJson(out, 401, LanSyncProtocol.Error("wrong pairing code"))
                }
                else -> serve(request, out)
            }
            out.flush()
        } catch (e: IOException) {
            // The other side went away mid-transfer; nothing to tell it
            logcat(LogPriority.DEBUG, e) { "LAN sync connection ended" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "LAN sync request failed" }
            runCatching {
                respondJson(out, 500, LanSyncProtocol.Error("server error"))
                out.flush()
            }
        }
    }

    /**
     * Pairing v2. Needs the code, counts toward the lockout like any other wrong code, and stores
     * nothing unless the asking device is the same profile as this install.
     */
    private fun link(request: LanSyncProtocol.Request, client: Socket, out: OutputStream) {
        if (request.method != "POST") {
            respondJson(out, 405, LanSyncProtocol.Error("POST only"))
            return
        }
        if (lockout.isLocked()) {
            respondJson(out, 429, LanSyncProtocol.Error("too many wrong codes, try later"))
            return
        }
        if (!LanSyncProtocol.codeMatches(
                preferences.currentPairCode(),
                request.header(LanSyncProtocol.PAIR_CODE_HEADER),
            )
        ) {
            lockout.recordFailure()
            respondJson(out, 401, LanSyncProtocol.Error("wrong pairing code"))
            return
        }
        val body = LanSyncLink.readBody(client.getInputStream(), request.header("content-length"))
            ?.let(LanSyncLink::parseRequest)
        if (body == null) {
            respondJson(out, 400, LanSyncProtocol.Error("bad link request"))
            return
        }

        val me = LanSyncLink.LinkResponse(preferences.deviceId, deviceName, links.profileId, links.profileName)
        if (body.profileId != me.profileId) {
            respondJson(out, 409, me)
            return
        }

        val key = LanSyncLink.newKey()
        val host = client.inetAddress?.hostAddress
        links.save(
            LanSyncLink.Link(
                peerDeviceId = body.deviceId,
                peerName = body.deviceName,
                profileId = body.profileId,
                key = key,
                lastAddress = host?.let { if (body.port != null) "$it:${body.port}" else it },
                platform = body.platform,
            ),
        )
        logcat(LogPriority.INFO) { "LAN sync linked ${body.deviceName} (${body.platform})" }
        respondJson(out, 200, me.copy(key = key))
    }

    /**
     * The routes only a linked device may use: the phone's queue, downloaded on a PC. The pairing
     * code doesn't open these, so there's nothing here for someone guessing codes.
     */
    private fun keyed(request: LanSyncProtocol.Request, client: Socket, out: OutputStream) {
        val link = links.findByKey(request.header(LanSyncLink.LINK_KEY_HEADER))
        if (link == null) {
            respondJson(out, 401, LanSyncProtocol.Error("needs a link key"))
            return
        }
        client.inetAddress?.hostAddress?.let { links.touch(link, withPort(it, link.lastAddress)) }

        val expected = if (request.path == "/queue") "GET" else "POST"
        if (request.method != expected) {
            respondJson(out, 405, LanSyncProtocol.Error("$expected only"))
            return
        }
        if (request.path == "/queue") {
            respondJson(out, 200, queue.items())
            return
        }

        val body = LanSyncLink.readBody(
            client.getInputStream(),
            request.header("content-length"),
            RemoteQueue.MAX_BODY_BYTES,
        )
        if (body == null) {
            respondJson(out, 400, LanSyncProtocol.Error("bad body"))
            return
        }
        when (request.path) {
            "/queue/claim" -> {
                val claim = RemoteQueue.parse<RemoteQueue.ClaimRequest>(body)
                when {
                    claim == null -> respondJson(out, 400, LanSyncProtocol.Error("bad claim"))
                    claim.by != link.peerDeviceId ->
                        respondJson(out, 400, LanSyncProtocol.Error("by must be the linked device's id"))
                    else -> respondJson(out, 200, queue.claim(claim, link))
                }
            }
            "/queue/release" -> {
                val release = RemoteQueue.parse<RemoteQueue.ReleaseRequest>(body)
                if (release == null) {
                    respondJson(out, 400, LanSyncProtocol.Error("bad release"))
                } else {
                    queue.release(release, link)
                    respondJson(out, 200, RemoteQueue.ReleaseRequest(release.items))
                }
            }
            "/downloads/done" -> {
                val done = RemoteQueue.parse<RemoteQueue.DoneRequest>(body)
                if (done == null) {
                    respondJson(out, 400, LanSyncProtocol.Error("bad events"))
                } else {
                    respondJson(out, 200, queue.done(done))
                }
            }
        }
    }

    /** Keeps the port the device said it serves on, since the one it connects from is ephemeral. */
    private fun withPort(host: String, previous: String?): String {
        val port = previous?.substringAfterLast(':', "")?.takeIf { it.isNotEmpty() && ':' in previous }
        return if (port != null) "$host:$port" else host
    }

    private fun serve(request: LanSyncProtocol.Request, out: OutputStream) {
        when (request.path) {
            "/manifest" -> respondJson(out, 200, runBlocking { catalog.manifest(deviceName) })
            "/backup" -> {
                val file = runBlocking { catalog.backup() }
                LanSyncProtocol.writeHead(out, 200, "application/octet-stream", file.length())
                file.inputStream().use { it.copyTo(out) }
            }
            "/file" -> {
                val path = request.query["path"].orEmpty()
                val found = runBlocking { catalog.find(path) }
                if (found == null) {
                    respondJson(out, 404, LanSyncProtocol.Error("not in manifest"))
                    return
                }
                val (file, source) = found
                LanSyncProtocol.writeHead(
                    out,
                    200,
                    if (file.path.endsWith(".cbz")) "application/vnd.comicbook+zip" else "application/octet-stream",
                    if (file.packed) null else file.size,
                )
                catalog.write(file, source, out)
            }
        }
    }

    private inline fun <reified T> respondJson(out: OutputStream, status: Int, body: T) {
        val bytes = json.encodeToString(body).toByteArray()
        LanSyncProtocol.writeHead(out, status, "application/json; charset=utf-8", bytes.size.toLong())
        out.write(bytes)
    }

    private fun advertise(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = LanSyncProtocol.SERVICE_TYPE
            setPort(port)
            // So a device lists the peers of its own profile first
            setAttribute("p", links.profileId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                logcat(LogPriority.INFO) { "LAN sync advertised as ${info.serviceName}" }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                // Still reachable by address, so not worth stopping over
                logcat(LogPriority.WARN) { "LAN sync advertising failed: $errorCode" }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching {
            context.getSystemService(NsdManager::class.java)
                .registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registration = listener
        }.onFailure { logcat(LogPriority.WARN, it) { "LAN sync advertising failed" } }
    }

    /** IPv4 addresses on the LAN, for typing into the other device when discovery doesn't find it. */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_CONNECTIONS = 4
        const val READ_TIMEOUT_MS = 30_000
        val AUTHED_ROUTES = setOf("/manifest", "/backup", "/file")
        val KEYED_ROUTES = setOf("/queue", "/queue/claim", "/queue/release", "/downloads/done")
    }
}
