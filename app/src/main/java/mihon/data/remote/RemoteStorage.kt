package mihon.data.remote

import java.io.File
import java.io.InputStream

/**
 * A remote filesystem that chapter archives can be mirrored to.
 *
 * Paths are passed as a list of segments rather than a string so that a manga
 * title containing a separator cannot escape its directory, and so that each
 * implementation escapes them in whatever way its protocol requires.
 *
 * Implementations are blocking. Callers are expected to already be on an IO
 * dispatcher; wrapping every call in a coroutine here would only hide that.
 *
 * WebDAV is the only implementation today. The interface exists because it will
 * not be the last -- SMB is the obvious next one -- and because nothing above
 * this line should have to care which is in use.
 */
interface RemoteStorage {

    /** Human-readable name of the protocol, for error messages and logs. */
    val protocolName: String

    /**
     * Checks that the server is reachable and the credentials are accepted.
     * The failure carries a message fit to show the user.
     */
    fun testConnection(): Result<Unit>

    /** Size in bytes, or null if the path does not exist. */
    fun sizeOf(segments: List<String>): Long?

    /**
     * Names of the immediate children of [segments], or null if the directory
     * does not exist or cannot be listed.
     *
     * One call per directory is what makes a remote index affordable: listing a
     * manga directory costs one request instead of one per chapter.
     */
    fun list(segments: List<String>): List<RemoteEntry>?

    /** Uploads [length] bytes read from [openStream]. Creates parent directories. */
    fun put(segments: List<String>, length: Long, openStream: () -> InputStream): Result<Unit>

    /**
     * Downloads to [target]. [onProgress] is called with bytes transferred and
     * the total when known, so a caller can show something other than a frozen
     * screen during an 80 MB fetch.
     */
    fun get(
        segments: List<String>,
        target: File,
        onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<Unit>

    /** Deletes a file or directory. A path that is already absent counts as success. */
    fun delete(segments: List<String>): Result<Unit>

    /**
     * Moves [from] to [to], creating the destination's parents. Used when a
     * series is renamed, so the remote copy follows instead of being orphaned.
     */
    fun move(from: List<String>, to: List<String>): Result<Unit>
}

/** One child of a remote directory. */
data class RemoteEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
)
