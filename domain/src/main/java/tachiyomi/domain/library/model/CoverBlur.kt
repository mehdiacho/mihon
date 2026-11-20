package tachiyomi.domain.library.model

/**
 * Decides which library covers are blurred for privacy.
 *
 * In order, the first that applies wins:
 * 1. the global toggle is off: nothing is blurred;
 * 2. the series was set by hand ([Rules.forced] / [Rules.exempt]);
 * 3. its source was set by hand ([Rules.sourcesForced] / [Rules.sourcesExempt]);
 * 4. its source is rated NSFW, or one of its genres is in [ADULT_GENRES]. Many adult series
 *    carry no such tag, so the source rating matters most.
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

    data class Rules(
        val enabled: Boolean = false,
        val forced: Set<String> = emptySet(),
        val exempt: Set<String> = emptySet(),
        val sourcesForced: Set<String> = emptySet(),
        val sourcesExempt: Set<String> = emptySet(),
        val nsfwSourceIds: Set<Long> = emptySet(),
    ) {
        /** Whether a source's series are blurred unless set by hand; null while the toggle is off. */
        fun sourceBlurred(sourceId: Long): Boolean? {
            if (!enabled) return null
            return when (sourceId.toString()) {
                in sourcesForced -> true
                in sourcesExempt -> false
                else -> sourceId in nsfwSourceIds
            }
        }

        fun shouldBlur(mangaId: Long, sourceId: Long, genres: List<String>?): Boolean {
            if (!enabled) return false
            when (mangaId.toString()) {
                in forced -> return true
                in exempt -> return false
            }
            when (sourceId.toString()) {
                in sourcesForced -> return true
                in sourcesExempt -> return false
            }
            return sourceId in nsfwSourceIds || isAdult(genres)
        }
    }

    /** The (forced, exempt) sets after choosing [blur] for every id in [ids]. */
    fun override(
        ids: Collection<Long>,
        blur: Boolean,
        forced: Set<String>,
        exempt: Set<String>,
    ): Pair<Set<String>, Set<String>> {
        val keys = ids.map(Long::toString).toSet()
        return if (blur) (forced + keys) to (exempt - keys) else (forced - keys) to (exempt + keys)
    }
}
