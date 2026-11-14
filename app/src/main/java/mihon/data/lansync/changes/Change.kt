package mihon.data.lansync.changes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * TLP `changes/1`: one entry in a device's change journal. The contract is TachiMana's
 * `docs/protocol/capabilities/changes-1.md`.
 *
 * Kept free of Android, like the rest of the wire code, so the rules are unit tested against the
 * protocol's test files.
 */
@Serializable
data class Change(
    val changeId: String,
    val deviceId: String,
    val at: Long,
    val seq: Long = 0,
    val kind: String,
    val target: JsonObject,
    val value: JsonObject,
)

object ChangeKind {
    const val MANGA_LIBRARY = "manga.library"
    const val MANGA_CATEGORIES = "manga.categories"
    const val CATEGORY_SET = "category.set"
    const val CATEGORY_RENAME = "category.rename"
    const val CATEGORY_DELETE = "category.delete"
    const val CHAPTER_READ = "chapter.read"
    const val CHAPTER_BOOKMARK = "chapter.bookmark"
    const val HISTORY_READ = "history.read"
    const val MANGA_VIEWER = "manga.viewer"

    val KNOWN = setOf(
        MANGA_LIBRARY,
        MANGA_CATEGORIES,
        CATEGORY_SET,
        CATEGORY_RENAME,
        CATEGORY_DELETE,
        CHAPTER_READ,
        CHAPTER_BOOKMARK,
        HISTORY_READ,
        MANGA_VIEWER,
    )
}

/** The wire messages of `changes/1` on the direct carrier. */
object ChangesWire {

    const val CAPABILITY = "changes/1"

    const val DEFAULT_LIMIT = 500
    const val MAX_LIMIT = 2000

    /** At most this many entries in one `POST /changes`; more is a 413. */
    const val MAX_PUSH = 2000

    /** 2000 entries with a manga's description each fit well inside this. */
    const val MAX_BODY_BYTES = 16L * 1024 * 1024

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Serializable
    data class Page(
        val deviceId: String,
        val changes: List<Change>,
        val next: Long,
        val more: Boolean,
    )

    @Serializable
    data class Push(
        val deviceId: String,
        val changes: List<Change>,
    )

    @Serializable
    data class PushResult(val applied: Int, val ignored: Int)

    @Serializable
    data class TooOld(val error: String = "cursor-too-old", val oldest: Long)

    @Serializable
    data class Error(val error: String, val message: String? = null)

    /** `limit` defaults to 500 and is capped at 2000; anything unreadable is the default. */
    fun limitOf(raw: String?): Int =
        raw?.toIntOrNull()?.takeIf { it > 0 }?.coerceAtMost(MAX_LIMIT) ?: DEFAULT_LIMIT

    /** A missing `after` is the start of the journal; a malformed one is refused. */
    fun afterOf(raw: String?): Result<Long?> = when {
        raw.isNullOrEmpty() -> Result.success(null)
        else -> raw.toLongOrNull()?.takeIf { it >= 0 }
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("after must be a seq"))
    }

    fun parsePush(body: String): Push? = runCatching { json.decodeFromString<Push>(body) }.getOrNull()
}
