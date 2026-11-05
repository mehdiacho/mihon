package mihon.data.lansync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Pairing v2: linking this install to the same profile on another device, so the two can talk in
 * the background with a key instead of the 6-digit code. The contract is TachiMana's
 * `docs/rust-port/profiles-and-remote-downloads.md`, §2.
 *
 * Kept free of Android, like [LanSyncProtocol], so what decides who gets a key is unit tested.
 */
object LanSyncLink {

    const val LINK_KEY_HEADER = "X-Link-Key"

    const val PLATFORM_ANDROID = "android"
    const val PLATFORM_DESKTOP = "desktop"

    const val INVITE_SCHEME = "tachimana"

    /** A `/link` body is a handful of short strings; anything bigger isn't one. */
    const val MAX_BODY_BYTES = 4 * 1024

    const val MAX_FIELD_CHARS = 128

    private const val KEY_BYTES = 32

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class LinkRequest(
        val deviceId: String,
        val deviceName: String,
        val profileId: String,
        val profileName: String,
        val platform: String,
        val port: Int? = null,
    )

    /** `key` is left out of a 409, which only says whose profile the other side is. */
    @Serializable
    data class LinkResponse(
        val deviceId: String,
        val deviceName: String,
        val profileId: String,
        val profileName: String,
        val key: String? = null,
    )

    /** One linked device, as both sides keep it. */
    @Serializable
    data class Link(
        val peerDeviceId: String,
        val peerName: String,
        val profileId: String,
        val key: String,
        val lastAddress: String? = null,
        val platform: String? = null,
    )

    /** What a "Link a device" QR code carries. */
    data class Invite(
        val host: String,
        val port: Int,
        val deviceId: String?,
        val profileId: String?,
        val profileName: String?,
        val code: String?,
    ) {
        fun toUri(): String = buildString {
            append("$INVITE_SCHEME://link?h=").append(encode("$host:$port"))
            deviceId?.let { append("&d=").append(encode(it)) }
            profileId?.let { append("&p=").append(encode(it)) }
            profileName?.let { append("&n=").append(encode(it)) }
            code?.let { append("&c=").append(encode(it)) }
        }
    }

    /**
     * Reads exactly [contentLength] bytes after a request head. Null when the length is missing,
     * too big, or the connection ends early, so a short body is never parsed as if it were whole.
     */
    fun readBody(input: InputStream, contentLength: String?, max: Int = MAX_BODY_BYTES): ByteArray? {
        val length = contentLength?.trim()?.toIntOrNull() ?: return null
        if (length !in 0..max) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return body
    }

    /** Null for anything that isn't a well-formed link request, which the server answers with 400. */
    fun parseRequest(body: ByteArray): LinkRequest? {
        val request = try {
            json.decodeFromString<LinkRequest>(body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        val fields = listOf(request.deviceId, request.deviceName, request.profileId, request.profileName)
        if (fields.any { it.isBlank() || it.length > MAX_FIELD_CHARS }) return null
        if (request.platform != PLATFORM_ANDROID && request.platform != PLATFORM_DESKTOP) return null
        if (request.port != null && request.port !in 1..65535) return null
        return request
    }

    /** 32 random bytes as base64url without padding: 43 characters. */
    fun newKey(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(KEY_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * The link whose key is [given], comparing against every link without stopping at the first
     * match, so the response time says nothing about which keys exist.
     */
    fun findByKey(links: List<Link>, given: String?): Link? {
        val candidate = given?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var found: Link? = null
        for (link in links) {
            if (MessageDigest.isEqual(link.key.toByteArray(), candidate.toByteArray())) found = link
        }
        return found
    }

    /**
     * Reads a pasted or scanned invite. Also takes a bare `host:port` (port optional) so the same
     * field works for typing an address.
     */
    fun parseInvite(text: String): Invite? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        if (!trimmed.startsWith("$INVITE_SCHEME://", ignoreCase = true)) {
            val (host, port) = splitHostPort(trimmed) ?: return null
            return Invite(host, port, null, null, null, null)
        }

        val afterScheme = trimmed.substring(INVITE_SCHEME.length + 3)
        if (!afterScheme.startsWith("link")) return null
        val params = afterScheme.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val value = runCatching { decode(pair.substringAfter('=', "")) }.getOrNull() ?: return null
                pair.substringBefore('=') to value
            }
        val (host, port) = splitHostPort(params["h"] ?: return null) ?: return null
        return Invite(
            host = host,
            port = port,
            deviceId = params["d"]?.takeIf { it.isNotBlank() },
            profileId = params["p"]?.takeIf { it.isNotBlank() },
            profileName = params["n"]?.takeIf { it.isNotBlank() },
            code = params["c"]?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() },
        )
    }

    private fun splitHostPort(value: String): Pair<String, Int>? {
        val text = value.trim().removePrefix("http://").trimEnd('/')
        if (text.isEmpty() || text.any { it.isWhitespace() || it == '/' || it == '@' }) return null
        val colon = text.lastIndexOf(':')
        if (colon < 0) return text to LanSyncProtocol.DEFAULT_PORT
        val host = text.substring(0, colon)
        val port = text.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return if (host.isEmpty()) null else host to port
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
}
