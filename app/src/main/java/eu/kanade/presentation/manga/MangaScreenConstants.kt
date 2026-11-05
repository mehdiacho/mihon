package eu.kanade.presentation.manga

enum class DownloadAction {
    NEXT_1_CHAPTER,
    NEXT_5_CHAPTERS,
    NEXT_10_CHAPTERS,
    NEXT_25_CHAPTERS,
    UNREAD_CHAPTERS,

    /**
     * Unread chapters the remote server does not already hold.
     *
     * "Download everything I have not read" over a whole library re-downloads
     * from the source what is already sitting on the server, which costs
     * bandwidth twice and puts load on the source for nothing. Only offered
     * when mirroring is on, since otherwise it is the same as
     * [UNREAD_CHAPTERS].
     */
    UNREAD_NOT_ON_SERVER,
    BOOKMARKED_CHAPTERS,
}

enum class EditCoverAction {
    EDIT,
    DELETE,
}

enum class MangaScreenItem {
    INFO_BOX,
    ACTION_ROW,
    DESCRIPTION_WITH_TAG,
    CHAPTER_HEADER,
    CHAPTER,
}
