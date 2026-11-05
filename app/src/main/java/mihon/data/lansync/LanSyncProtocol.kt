package mihon.data.lansync

import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * The wire format TachiMana's desktop apps already speak, served from the phone so a device that
 * restored this phone's backup can pull the downloaded chapters straight from it.
 *
 * Kept free of Android so the parts that decide what a stranger on the LAN gets are unit tested.
 */
object LanSyncProtocol {

    const val SERVICE_TYPE = "_tachimana._tcp"
    const val DEFAULT_PORT = 27193

    /** Ports tried upward from [DEFAULT_PORT], the same as the desktop apps. */
    const val PORT_ATTEMPTS = 20

    const val PAIR_CODE_HEADER = "X-Pair-Code"

    /** Every manifest path for a downloaded chapter starts here. */
    const val DOWNLOADS_ROOT = "downloads/"

    private const val MAX_HEADER_BYTES = 8 * 1024

    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /**
     * Reads one request head. Only what a GET needs: there is no body to read, and anything that
     * doesn't look like HTTP/1.x, or runs past [MAX_HEADER_BYTES], is null.
     */
    fun readRequest(input: InputStream): Request? {
        val lines = readHead(input) ?: return null
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null

        val target = parts[1]
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()

        return Request(
            method = parts[0],
            path = percentDecode(target.substringBefore('?'), plusIsSpace = false) ?: return null,
            query = parseQuery(target.substringAfter('?', "")) ?: return null,
            headers = headers,
        )
    }

    private fun readHead(input: InputStream): List<String>? {
        val bytes = java.io.ByteArrayOutputStream()
        var matched = 0
        val end = "\r\n\r\n"
        while (matched < end.length) {
            val b = input.read()
            if (b < 0 || bytes.size() >= MAX_HEADER_BYTES) return null
            bytes.write(b)
            matched = when {
                b.toChar() == end[matched] -> matched + 1
                b.toChar() == end[0] -> 1
                else -> 0
            }
        }
        return bytes.toString(Charsets.ISO_8859_1.name()).removeSuffix(end).split("\r\n")
    }

    private fun parseQuery(raw: String): Map<String, String>? {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val key = percentDecode(pair.substringBefore('='), plusIsSpace = true) ?: return null
            val value = percentDecode(pair.substringAfter('=', ""), plusIsSpace = true) ?: return null
            key to value
        }
    }

    /**
     * Strict: a malformed escape is null rather than passed through, so it can't smuggle anything.
     * `+` is a space only in a query; chapter file names contain real plus signs.
     */
    fun percentDecode(raw: String, plusIsSpace: Boolean): String? {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '%' -> {
                    if (i + 2 >= raw.length) return null
                    val value = raw.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                    out.write(value)
                    i += 3
                }
                c == '+' && plusIsSpace -> {
                    out.write(' '.code)
                    i++
                }
                else -> {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Whether [path] is shaped like something this server could hand out. This is only the first
     * gate: [LanSyncCatalog] then serves nothing that isn't in the manifest it last built.
     */
    fun isSafePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith('/') || path.contains('\\') || path.contains('\u0000')) return false
        if (!path.startsWith(DOWNLOADS_ROOT)) return false
        // A drive letter, a URI scheme or a home reference has no business in a manifest path
        if (path.contains(':') || path.startsWith('~')) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** Compared without an early exit, so the response time says nothing about how close a guess was. */
    fun codeMatches(expected: String, given: String?): Boolean {
        if (given == null || expected.isEmpty()) return false
        return MessageDigest.isEqual(expected.toByteArray(), given.trim().toByteArray())
    }

    fun writeHead(out: OutputStream, status: Int, contentType: String, contentLength: Long?) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            429 -> "Too Many Requests"
            503 -> "Service Unavailable"
            else -> "Internal Server Error"
        }
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            if (contentLength != null) append("Content-Length: $contentLength\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    /** `profileId` and `profileName` are pairing v2's additions; older readers ignore them. */
    @Serializable
    data class Ping(
        val deviceId: String,
        val deviceName: String,
        val profileId: String? = null,
        val profileName: String? = null,
    )

    @Serializable
    data class Manifest(
        val deviceId: String,
        val deviceName: String,
        val backupSize: Long,
        val files: List<ManifestFile>,
    )

    /**
     * One downloaded chapter. `kind`, `sourceId`, `mangaUrl` and `chapterUrl` are the additive
     * fields from the brief; `packed` is ours: the chapter is a folder of images on this phone and
     * is zipped as it is sent, so `size` is 0 (unknown) and the response carries no Content-Length.
     * The puller reads to the end of the connection.
     */
    @Serializable
    data class ManifestFile(
        val path: String,
        val size: Long,
        val lastModified: Long,
        val kind: String = "download",
        val sourceId: String,
        val mangaUrl: String,
        val chapterUrl: String,
        val packed: Boolean = false,
    )

    @Serializable
    data class Error(val error: String)
}

/**
 * Turns away a guesser. A 6-digit code over plain HTTP is a million tries, so after [limit] wrong
 * codes inside [windowMs] every request that needs a code is refused until [lockMs] has passed
 * since the last wrong one. One counter for the whole server, not per address, so changing
 * address doesn't reset it.
 */
class PairCodeLockout(
    private val limit: Int = 5,
    private val windowMs: Long = 60_000,
    private val lockMs: Long = 5 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val failures = ArrayDeque<Long>()
    private var lockedUntil = 0L

    @Synchronized
    fun isLocked(): Boolean = clock() < lockedUntil

    @Synchronized
    fun recordFailure() {
        val now = clock()
        failures.addLast(now)
        while (failures.isNotEmpty() && now - failures.first() > windowMs) failures.removeFirst()
        if (failures.size >= limit) lockedUntil = now + lockMs
    }

    @Synchronized
    fun reset() {
        failures.clear()
        lockedUntil = 0L
    }
}
