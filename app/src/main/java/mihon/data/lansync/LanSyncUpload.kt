package mihon.data.lansync

import java.io.InputStream
import java.io.OutputStream

/**
 * `PUT /file`: a linked PC sending a chapter it downloaded for this phone. The contract is
 * TachiMana's `docs/rust-port/profiles-and-remote-downloads.md`, §3, "Where chapters go".
 *
 * Android-free, so what may be written, and how much, is unit tested. [LanSyncReceiver] does the
 * writing.
 */
object LanSyncUpload {

    const val MAX_BYTES = 1024L * 1024 * 1024

    const val ARCHIVE_SUFFIX = ".cbz"
    const val PART_SUFFIX = ".part"

    /** `downloads/<source dir>/<manga dir>/<chapter>.cbz`, split up. */
    data class Target(val sourceDir: String, val mangaDir: String, val fileName: String) {
        val chapterDirName get() = fileName.removeSuffix(ARCHIVE_SUFFIX)
    }

    enum class Copy { COMPLETE, SHORT, TOO_LONG }

    /**
     * Only a chapter archive exactly three levels under downloads. Anything else, `..` and
     * absolute paths included, is null and gets 400.
     */
    fun parseTarget(path: String): Target? {
        if (!LanSyncProtocol.isSafePath(path)) return null
        val parts = path.removePrefix(LanSyncProtocol.DOWNLOADS_ROOT).split('/')
        if (parts.size != 3) return null
        val (sourceDir, mangaDir, fileName) = parts
        if (!fileName.endsWith(ARCHIVE_SUFFIX) || fileName.length == ARCHIVE_SUFFIX.length) return null
        // Hidden and temp names are the receiver's own, never something to be sent
        if (parts.any { it.startsWith('.') } || fileName.endsWith(PART_SUFFIX)) return null
        return Target(sourceDir, mangaDir, fileName)
    }

    /** The declared length, if there is one and it's within the limit. An empty chapter isn't one. */
    fun parseLength(header: String?, max: Long = MAX_BYTES): Long? =
        header?.trim()?.toLongOrNull()?.takeIf { it in 1..max }

    /**
     * Copies exactly [length] bytes. [Copy.SHORT] if the connection ends first, [Copy.TOO_LONG]
     * if more is already waiting after them: either way the body isn't the file it claims to be.
     */
    fun copyExactly(input: InputStream, output: OutputStream, length: Long): Copy {
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) return Copy.SHORT
            output.write(buffer, 0, n)
            remaining -= n
        }
        return if (input.available() > 0) Copy.TOO_LONG else Copy.COMPLETE
    }
}
