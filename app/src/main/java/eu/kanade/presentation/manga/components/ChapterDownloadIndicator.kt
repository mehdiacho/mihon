package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.Cloud
import mihon.icons.materialsymbols.rounded.Error
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.IconButtonTokens
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

enum class ChapterDownloadAction {
    START,
    START_NOW,
    CANCEL,
    DELETE,
    DELETE_REMOTE,
    KEEP_ON_DEVICE,
    ALLOW_REMOVAL,
}

/**
 * The chapter's download control, optionally preceded by a cloud badge saying
 * the configured remote server holds a copy.
 *
 * The two are deliberately separate icons rather than one combined symbol. The
 * download control keeps exactly the meaning it has always had -- an arrow
 * means "not on this device, tap to get it", a check means "on this device" --
 * so a chapter that lives only on the server still shows the arrow the user
 * already knows how to tap. The cloud answers a different question, and it is
 * additional information rather than a replacement for the answer to the first.
 *
 * @param isOnRemote whether the server is known to hold this chapter.
 * @param isKeptOnDevice true or false when the eviction policy could remove
 * this chapter and the user has or has not pinned it; null when nothing would
 * evict it anyway, in which case pinning is not offered.
 */
@Composable
fun ChapterDownloadIndicator(
    enabled: Boolean,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    onClick: (ChapterDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
    isOnRemote: Boolean = false,
    isKeptOnDevice: Boolean? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isOnRemote) {
            RemoteCopyBadge(
                enabled = enabled,
                isKeptOnDevice = isKeptOnDevice,
                onClick = onClick,
            )
        }
        when (val downloadState = downloadStateProvider()) {
            Download.State.NOT_DOWNLOADED -> NotDownloadedIndicator(
                enabled = enabled,
                onClick = onClick,
            )
            Download.State.QUEUE, Download.State.DOWNLOADING -> DownloadingIndicator(
                enabled = enabled,
                downloadState = downloadState,
                downloadProgressProvider = downloadProgressProvider,
                onClick = onClick,
            )
            Download.State.DOWNLOADED -> DownloadedIndicator(
                enabled = enabled,
                onClick = onClick,
            )
            Download.State.ERROR -> ErrorIndicator(
                enabled = enabled,
                onClick = onClick,
            )
        }
    }
}

/**
 * Sits to the left of the download control and means one thing: the server has
 * this chapter. Narrower than the control it sits beside but the same height,
 * so a column of chapter rows still lines up.
 */
@Composable
private fun RemoteCopyBadge(
    enabled: Boolean,
    isKeptOnDevice: Boolean?,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(RemoteBadgeWidth)
            .height(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { isMenuExpanded = true },
                onClick = { isMenuExpanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Cloud,
            contentDescription = stringResource(MR.strings.remote_storage_chapter_on_server),
            modifier = Modifier.size(RemoteBadgeIconSize),
            // The one coloured icon in the row. Everything else here is
            // onSurfaceVariant, so tinting the cloud with the scheme's accent
            // separates "where this chapter is" from "what tapping does" at a
            // glance, and follows the user's theme rather than hard-coding a
            // colour that would clash with half of them. Not secondary alpha,
            // for the same reason: a muted accent reads as disabled.
            tint = MaterialTheme.colorScheme.tertiary,
        )
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            if (isKeptOnDevice == false) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_keep_on_device)) },
                    onClick = {
                        onClick(ChapterDownloadAction.KEEP_ON_DEVICE)
                        isMenuExpanded = false
                    },
                )
            }
            if (isKeptOnDevice == true) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_allow_removal)) },
                    onClick = {
                        onClick(ChapterDownloadAction.ALLOW_REMOVAL)
                        isMenuExpanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_delete_remote_copy)) },
                onClick = {
                    onClick(ChapterDownloadAction.DELETE_REMOTE)
                    isMenuExpanded = false
                },
            )
        }
    }
}

@Composable
private fun NotDownloadedIndicator(
    enabled: Boolean,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterDownloadAction.START_NOW) },
                onClick = { onClick(ChapterDownloadAction.START) },
            )
            .secondaryItemAlpha(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_download_chapter_24dp),
            contentDescription = stringResource(MR.strings.manga_download),
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadingIndicator(
    enabled: Boolean,
    downloadState: Download.State,
    downloadProgressProvider: () -> Int,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterDownloadAction.CANCEL) },
                onClick = { isMenuExpanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val arrowColor: Color
        val strokeColor = MaterialTheme.colorScheme.onSurfaceVariant
        val downloadProgress = downloadProgressProvider()
        val indeterminate = downloadState == Download.State.QUEUE ||
            (downloadState == Download.State.DOWNLOADING && downloadProgress == 0)
        if (indeterminate) {
            arrowColor = strokeColor
            CircularProgressIndicator(
                modifier = IndicatorModifier,
                color = strokeColor,
                strokeWidth = IndicatorStrokeWidth,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
            )
        } else {
            val animatedProgress by animateFloatAsState(
                targetValue = downloadProgress / 100f,
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "progress",
            )
            arrowColor = if (animatedProgress < 0.5f) {
                strokeColor
            } else {
                MaterialTheme.colorScheme.background
            }
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = IndicatorModifier,
                color = strokeColor,
                strokeWidth = IndicatorSize / 2,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
            )
        }
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_start_downloading_now)) },
                onClick = {
                    onClick(ChapterDownloadAction.START_NOW)
                    isMenuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_cancel)) },
                onClick = {
                    onClick(ChapterDownloadAction.CANCEL)
                    isMenuExpanded = false
                },
            )
        }
        Icon(
            imageVector = MaterialSymbols.Rounded.ArrowDownward,
            contentDescription = null,
            modifier = ArrowModifier,
            tint = arrowColor,
        )
    }
}

@Composable
private fun DownloadedIndicator(
    enabled: Boolean,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { isMenuExpanded = true },
                onClick = { isMenuExpanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.RoundedFilled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_delete)) },
                onClick = {
                    onClick(ChapterDownloadAction.DELETE)
                    isMenuExpanded = false
                },
            )
        }
    }
}

@Composable
private fun ErrorIndicator(
    enabled: Boolean,
    onClick: (ChapterDownloadAction) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterDownloadAction.START) },
                onClick = { onClick(ChapterDownloadAction.START) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Error,
            contentDescription = stringResource(MR.strings.chapter_error),
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

private fun Modifier.commonClickable(
    enabled: Boolean,
    hapticFeedback: HapticFeedback,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) = this.combinedClickable(
    enabled = enabled,
    onLongClick = {
        onLongClick()
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    },
    onClick = onClick,
    role = Role.Button,
    interactionSource = null,
    indication = ripple(
        bounded = false,
        radius = IconButtonTokens.StateLayerSize / 2,
    ),
)

private val IndicatorSize = 26.dp
private val RemoteBadgeWidth = 28.dp
private val RemoteBadgeIconSize = 22.dp
private val IndicatorPadding = 2.dp

// To match composable parameter name when used later
private val IndicatorStrokeWidth = IndicatorPadding

private val IndicatorModifier = Modifier
    .size(IndicatorSize)
    .padding(IndicatorPadding)
private val ArrowModifier = Modifier
    .size(IndicatorSize - 7.dp)
