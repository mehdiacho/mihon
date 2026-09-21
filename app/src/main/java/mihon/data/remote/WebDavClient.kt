package mihon.data.remote

import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

/**
 * WebDAV over the OkHttp client the app already owns.
 *
 * Deliberately not a general-purpose WebDAV implementation: no locking, no
 * arbitrary properties, no PROPPATCH. PROPFIND is supported only far enough to
 * list a directory's children, which is what a remote index needs, and is
 * parsed with the platform pull parser rather than a serialization library so
 * this stays dependency-free.
 */
class WebDavClient(
    private val client: OkHttpClient,
    baseUrl: String,
    username: String,
    password: String,
) : RemoteStorage {

    override val protocolName: String = "WebDAV"

    private val base: HttpUrl = requireNotNull(normalize(baseUrl)) {
        "Not a valid http(s) URL: $baseUrl"
    }

    private val authHeader: String? = if (username.isNotEmpty()) {
        Credentials.basic(username, password)
    } else {
        null
    }

    /**
     * Resolves [segments] under the base URL, escaping each one. Segments are
     * added individually rather than joined, so a chapter title containing `/`
     * or `?` cannot escape its directory.
     */
    private fun urlFor(segments: List<String>): HttpUrl =
        base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private fun Request.Builder.withAuth() = apply {
        // Sent preemptively rather than via an Authenticator. Mirroring a series
        // is many small requests, and waiting for a 401 on each one doubles them.
        authHeader?.let { header("Authorization", it) }
    }

    private fun execute(request: Request): Response = client.newCall(request).execute()

    override fun testConnection(): Result<Unit> = runCatching {
        val request = Request.Builder().url(base).head().withAuth().build()
        execute(request).use { response ->
            when {
                response.isSuccessful -> Unit
                response.code == 401 -> throw IOException("Authentication rejected (401)")
                response.code == 404 -> throw IOException("Path not found on server (404)")
                else -> throw IOException("Server returned ${response.code}")
            }
        }
    }

    override fun sizeOf(segments: List<String>): Long? {
        val request = Request.Builder().url(urlFor(segments)).head().withAuth().build()
        return runCatching {
            execute(request).use { response ->
                if (!response.isSuccessful) return@use null
                response.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        }.getOrNull()
    }

    fun exists(segments: List<String>): Boolean = sizeOf(segments) != null

    override fun list(segments: List<String>): List<RemoteEntry>? {
        val url = urlFor(segments)
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            // Depth 1 is children only. Depth infinity would walk the whole
            // library in one request, which servers routinely refuse and which
            // would be a denial of service against our own NAS anyway.
            .header("Depth", "1")
            .withAuth()
            .build()

        return runCatching {
            execute(request).use { response ->
                // 404 is an answer: there is no such directory, so it holds
                // nothing. Anything else that failed is not an answer at all,
                // and null is how the caller is told to ask again later.
                if (response.code == 404) return@use emptyList()
                if (!response.isSuccessful) return@use null
                parseMultiStatus(response.body.byteStream(), selfPath = url.encodedPath)
            }
        }.getOrNull()
    }

    /**
     * Pulls `<href>` plus size and collection-ness out of a `multistatus`
     * document. Namespace prefixes vary between servers, so element names are
     * matched on their local part only.
     */
    private fun parseMultiStatus(stream: InputStream, selfPath: String): List<RemoteEntry> {
        val entries = mutableListOf<RemoteEntry>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(stream, null)
        }

        var href: String? = null
        var size = 0L
        var isDirectory = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfterLast(':') ?: continue
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (name) {
                    "response" -> {
                        href = null
                        size = 0L
                        isDirectory = false
                    }
                    "href" -> href = parser.nextText()
                    "collection" -> isDirectory = true
                    "getcontentlength" -> size = parser.nextText().trim().toLongOrNull() ?: 0L
                }
                XmlPullParser.END_TAG -> if (name == "response") {
                    val path = href?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                    // Every multistatus includes the directory that was asked
                    // about. Comparing decoded paths rather than raw hrefs
                    // avoids treating it as its own child when the server
                    // escapes differently than we did.
                    val selfDecoded = runCatching { URLDecoder.decode(selfPath, "UTF-8") }.getOrNull()
                    if (path != null && path.trimEnd('/') != selfDecoded?.trimEnd('/')) {
                        val childName = path.trimEnd('/').substringAfterLast('/')
                        if (childName.isNotEmpty()) {
                            entries += RemoteEntry(childName, isDirectory, size)
                        }
                    }
                }
            }
        }
        return entries
    }

    /**
     * Creates every directory in [segments], parents first.
     *
     * MKCOL has no "if not exists" form. A 405 is the standard answer for one
     * that is already there; servers also answer 403 or 409, so anything that
     * fails is checked rather than trusted. A directory we can list is a
     * directory we do not need to create.
     */
    fun createCollections(segments: List<String>): Result<Unit> = runCatching {
        for (depth in 1..segments.size) {
            val path = segments.take(depth)
            val request = Request.Builder()
                .url(urlFor(path))
                .method("MKCOL", null)
                .withAuth()
                .build()
            val code = execute(request).use { response ->
                if (response.isSuccessful) null else response.code
            }
            if (code != null && code != 405 && list(path) == null) {
                throw IOException("MKCOL ${path.joinToString("/")} -> $code")
            }
        }
    }

    override fun put(segments: List<String>, length: Long, openStream: () -> InputStream): Result<Unit> =
        put(segments, streamingBody(length, openStream))

    private fun put(segments: List<String>, body: RequestBody): Result<Unit> = runCatching {
        createCollections(segments.dropLast(1)).getOrThrow()

        val request = Request.Builder().url(urlFor(segments)).put(body).withAuth().build()
        execute(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException("PUT ${segments.joinToString("/")} -> ${response.code}")
            }
        }
    }

    /**
     * Streams straight from [openStream] instead of buffering the archive in
     * memory. Chapters routinely run past a hundred megabytes.
     */
    private fun streamingBody(length: Long, openStream: () -> InputStream): RequestBody =
        object : RequestBody() {
            override fun contentType(): MediaType? = null

            override fun contentLength(): Long = length

            override fun writeTo(sink: BufferedSink) {
                openStream().source().use { sink.writeAll(it) }
            }
        }

    override fun get(
        segments: List<String>,
        target: File,
        onProgress: (bytesRead: Long, total: Long) -> Unit,
    ): Result<Unit> = runCatching {
        val request = Request.Builder().url(urlFor(segments)).get().withAuth().build()
        execute(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException("GET ${segments.joinToString("/")} -> ${response.code}")
            }
            val total = response.body.contentLength()
            var transferred = 0L
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        transferred += read
                        onProgress(transferred, total)
                    }
                }
            }
        }
    }

    override fun delete(segments: List<String>): Result<Unit> = runCatching {
        val request = Request.Builder().url(urlFor(segments)).delete().withAuth().build()
        execute(request).use { response ->
            // Already gone is the outcome the caller wanted.
            if (!response.isSuccessful && response.code != 404) {
                throw IOException("DELETE ${segments.joinToString("/")} -> ${response.code}")
            }
        }
    }

    override fun move(from: List<String>, to: List<String>): Result<Unit> = runCatching {
        createCollections(to.dropLast(1)).getOrThrow()

        val request = Request.Builder()
            .url(urlFor(from))
            .method("MOVE", null)
            .header("Destination", urlFor(to).toString())
            .header("Overwrite", "T")
            .withAuth()
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw IOException("MOVE ${from.joinToString("/")} -> ${response.code}")
            }
        }
    }

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        /**
         * Asks for only the two properties that are used. Servers are entitled
         * to return everything for an empty body, and some return a great deal.
         */
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:">
              <D:prop><D:resourcetype/><D:getcontentlength/></D:prop>
            </D:propfind>
        """.trimIndent()

        /**
         * Accepts what a person would actually type: with or without a scheme,
         * with or without a trailing slash.
         */
        fun normalize(raw: String): HttpUrl? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
            return withScheme.toHttpUrlOrNull()
        }
    }
}
