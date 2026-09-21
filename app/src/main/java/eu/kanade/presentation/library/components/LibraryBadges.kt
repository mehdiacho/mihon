package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Cloud
import mihon.icons.materialsymbols.rounded.Folder
import tachiyomi.presentation.core.components.Badge

@Composable
internal fun DownloadsBadge(count: Int) {
    if (count > 0) {
        Badge(
            text = "$count",
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

/**
 * Chapters the server holds. Shown next to the download badge rather than
 * merged with it: the two answer different questions, and a series that is
 * entirely on the server and entirely off the device is the normal state once
 * eviction is on.
 *
 * The inverse surface pair rather than an accent role, because the accents are
 * taken. Mihon pins the unread badge to `secondary` and the downloaded badge to
 * `tertiary`, and most themes then set `primary` to the same value as
 * `secondary`, so reaching for primary would have reproduced the clash this is
 * fixing. `tertiaryContainer` is no better -- Nord sets it equal to `tertiary`.
 * The inverse pair is defined with guaranteed contrast in every theme, dynamic
 * colour included, and is neutral rather than a third competing accent.
 *
 * Monochrome and Yin Yang are two-tone by design and cannot give three distinct
 * badge colours; there the cloud icon is what tells this badge apart, which is
 * why it carries one and the other two do not.
 */
@Composable
internal fun RemoteBadge(count: Int) {
    if (count > 0) {
        Badge(
            text = "$count",
            imageVector = MaterialSymbols.Rounded.Cloud,
            color = MaterialTheme.colorScheme.inverseSurface,
            textColor = MaterialTheme.colorScheme.inverseOnSurface,
            iconColor = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

@Composable
internal fun UnreadBadge(count: Long) {
    if (count > 0) {
        Badge(text = "$count")
    }
}

@Composable
internal fun LanguageBadge(
    isLocal: Boolean,
    sourceLanguage: String,
) {
    if (isLocal) {
        Badge(
            imageVector = MaterialSymbols.Rounded.Folder,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
    } else if (sourceLanguage.isNotEmpty()) {
        Badge(
            text = sourceLanguage.uppercase(),
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@PreviewLightDark
@Composable
private fun BadgePreview() {
    TachiyomiPreviewTheme {
        Column {
            DownloadsBadge(count = 10)
            RemoteBadge(count = 10)
            UnreadBadge(count = 10)
            LanguageBadge(isLocal = true, sourceLanguage = "EN")
            LanguageBadge(isLocal = false, sourceLanguage = "EN")
        }
    }
}
