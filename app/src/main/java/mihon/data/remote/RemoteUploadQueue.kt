package mihon.data.remote

import android.content.Context
import androidx.core.content.edit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pending-upload queue, on disk.
 *
 * An in-memory queue loses everything the moment the process is killed, which
 * for a queue whose items are 100 MB transfers over a home network is most of
 * them. Entries live in their own SharedPreferences file and are removed only
 * once the server has been verified to hold the chapter.
 *
 * Keyed by remote path, so enqueuing the same chapter twice is one entry and
 * the queue is naturally idempotent.
 */
@Inject
@SingleIn(AppScope::class)
class RemoteUploadQueue(context: Context) {

    private val preferences = context.getSharedPreferences("remote_upload_queue", Context.MODE_PRIVATE)

    private val _size = MutableStateFlow(preferences.all.size)

    /** How many uploads are outstanding, for the settings screen. */
    val size = _size.asStateFlow()

    data class Entry(
        val segments: List<String>,
        val fileUri: String,
        val chapterId: Long,
        val mangaId: Long,
    )

    fun add(entry: Entry) {
        preferences.edit {
            putString(entry.segments.joinToString("/"), encode(entry))
        }
        _size.value = preferences.all.size
    }

    fun remove(segments: List<String>) {
        preferences.edit { remove(segments.joinToString("/")) }
        _size.value = preferences.all.size
    }

    fun items(): List<Entry> = preferences.all.mapNotNull { (key, value) ->
        decode(key, value?.toString() ?: return@mapNotNull null)
    }

    fun isEmpty(): Boolean = preferences.all.isEmpty()

    private fun encode(entry: Entry) = listOf(
        entry.fileUri,
        entry.chapterId.toString(),
        entry.mangaId.toString(),
    ).joinToString(FIELD_SEPARATOR)

    /**
     * Returns null rather than throwing on a malformed row. The queue outlives
     * upgrades, and one unreadable entry must not stop the rest from draining.
     */
    private fun decode(key: String, value: String): Entry? {
        val fields = value.split(FIELD_SEPARATOR)
        if (fields.size != 3) return null
        val segments = key.split("/")
        if (segments.size != 3) return null
        return Entry(
            segments = segments,
            fileUri = fields[0],
            chapterId = fields[1].toLongOrNull() ?: return null,
            mangaId = fields[2].toLongOrNull() ?: return null,
        )
    }

    companion object {
        /** A newline cannot appear in a content URI or in a decimal id. */
        private const val FIELD_SEPARATOR = "\n"
    }
}
