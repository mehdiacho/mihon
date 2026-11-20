package eu.kanade.tachiyomi.ui.library

import tachiyomi.domain.library.model.LibraryManga

data class LibraryItem(
    val libraryManga: LibraryManga,
    val downloadCount: Int,
    val remoteCount: Int,
    val deviceCount: Int,
    val unreadCount: Long,
    val isLocal: Boolean,
    val sourceName: String,
    val sourceLanguage: String,
    val badges: Badges,
    /** Blur this cover for privacy. See [tachiyomi.domain.library.model.CoverBlur]. */
    val blurCover: Boolean = false,
) {
    val id: Long = libraryManga.id

    data class Badges(
        val downloadCount: Int,
        val remoteCount: Int,
        val deviceCount: Int,
        val unreadCount: Long,
        val isLocal: Boolean,
        val sourceLanguage: String,
    )
}
