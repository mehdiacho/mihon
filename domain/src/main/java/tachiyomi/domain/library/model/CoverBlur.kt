package tachiyomi.domain.library.model

/**
 * Decides which library covers are blurred for privacy.
 *
 * A series counts as 18+ when one of its genres is in [ADULT_GENRES]. Each series can be
 * overridden by hand: [forced] ids are always blurred and [exempt] ids never are. When the
 * global toggle is off, nothing is blurred, overrides included.
 */
object CoverBlur {

    /** Lower-cased genre names that mark a series as 18+. Ecchi is left out on purpose. */
    val ADULT_GENRES = setOf(
        "adult",
        "hentai",
        "smut",
        "mature",
        "18+",
        "r-18",
        "r18",
        "explicit",
        "pornographic",
        "erotica",
        "nsfw",
    )

    fun isAdult(genres: List<String>?): Boolean =
        genres.orEmpty().any { it.trim().lowercase() in ADULT_GENRES }

    fun shouldBlur(
        enabled: Boolean,
        mangaId: Long,
        genres: List<String>?,
        forced: Set<String>,
        exempt: Set<String>,
    ): Boolean {
        if (!enabled) return false
        val id = mangaId.toString()
        return when (id) {
            in forced -> true
            in exempt -> false
            else -> isAdult(genres)
        }
    }

    /** The override sets after choosing [blur] for one series, as (forced, exempt). */
    fun override(
        mangaId: Long,
        blur: Boolean,
        forced: Set<String>,
        exempt: Set<String>,
    ): Pair<Set<String>, Set<String>> {
        val id = mangaId.toString()
        return if (blur) (forced + id) to (exempt - id) else (forced - id) to (exempt + id)
    }
}
