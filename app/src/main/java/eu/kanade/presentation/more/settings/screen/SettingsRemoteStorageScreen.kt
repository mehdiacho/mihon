package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.data.remote.EvictionTiming
import mihon.data.remote.ManualDownloadKeep
import mihon.data.remote.MirrorExistingDownloads
import mihon.data.remote.RedownloadSource
import mihon.data.remote.RemoteHealth
import mihon.data.remote.RemoteRole
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsRemoteStorageScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_remote_storage

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val prefs = remember { context.appGraph.remoteStoragePreferences }
        val clientProvider = remember { context.appGraph.remoteClientProvider }
        val health = remember { context.appGraph.remoteHealth }
        val mirror = remember { context.appGraph.remoteMirror }
        val migrator = remember { context.appGraph.mirrorExistingDownloads }

        val enabled by prefs.enabled.collectAsState()
        val url by prefs.url.collectAsState()
        val username by prefs.username.collectAsState()
        val password by prefs.password.collectAsState()
        val readAhead by prefs.readAheadChapters.collectAsState()
        val cacheSizeMb by prefs.cacheSizeMb.collectAsState()
        val evicts by prefs.evictAfterUpload.collectAsState()
        val evictWhen by prefs.evictWhen.collectAsState()
        val evictAfterDays by prefs.evictAfterDays.collectAsState()

        val status by health.status.collectAsStateWithLifecycle()
        val pending by mirror.pendingUploads.collectAsStateWithLifecycle()
        val migrationState by migrator.state.collectAsStateWithLifecycle()

        // Check as soon as the screen is opened rather than waiting out the
        // poll interval; arriving here usually means something is wrong.
        LaunchedEffect(enabled, url) {
            if (enabled && url.isNotBlank()) {
                health.check()
                // Opening this screen is usually a reaction to something not
                // having happened, so nudge a queue that may be stalled.
                mirror.retryPendingUploads()
            }
        }

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = prefs.enabled,
                title = stringResource(MR.strings.pref_remote_storage_enable),
                subtitle = stringResource(MR.strings.pref_remote_storage_enable_summary),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_remote_storage_connection),
                enabled = enabled,
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.url,
                        title = stringResource(MR.strings.pref_remote_storage_url),
                        subtitle = url.ifEmpty { stringResource(MR.strings.pref_remote_storage_url_hint) },
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.username,
                        title = stringResource(MR.strings.pref_remote_storage_username),
                        subtitle = username.ifEmpty { stringResource(MR.strings.pref_remote_storage_url_hint) },
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.password,
                        title = stringResource(MR.strings.pref_remote_storage_password),
                        // Never render the value: this subtitle sits in plain sight
                        // on the settings list, and the default is "%s".
                        subtitle = if (password.isEmpty()) {
                            stringResource(MR.strings.pref_remote_storage_password_unset)
                        } else {
                            stringResource(MR.strings.pref_remote_storage_password_set)
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_remote_storage_test),
                        subtitle = statusLabel(status),
                        widget = { StatusDot(status) },
                        onClick = {
                            if (url.isBlank()) {
                                context.toast(MR.strings.pref_remote_storage_test_no_url)
                                return@TextPreference
                            }
                            context.toast(MR.strings.pref_remote_storage_testing)
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    val client = clientProvider.build(url, username, password)
                                    client?.testConnection()
                                        ?: Result.failure(IllegalStateException("Invalid server URL"))
                                }
                                // Keep the dot honest about what was just observed,
                                // rather than leaving it stale until the next poll.
                                health.check()
                                result.fold(
                                    onSuccess = {
                                        context.toast(MR.strings.pref_remote_storage_test_success)
                                    },
                                    onFailure = { error ->
                                        context.toast(
                                            context.stringResource(
                                                MR.strings.pref_remote_storage_test_failure,
                                                error.message ?: error::class.simpleName.orEmpty(),
                                            ),
                                        )
                                    },
                                )
                            }
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_remote_storage_behaviour),
                enabled = enabled,
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.role,
                        entries = persistentRoleEntries(),
                        title = stringResource(MR.strings.pref_remote_storage_role),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.redownloadFrom,
                        entries = redownloadEntries(),
                        title = stringResource(MR.strings.pref_remote_storage_redownload),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.manualDownloadKeep,
                        entries = manualKeepEntries(),
                        title = stringResource(MR.strings.pref_remote_storage_manual_keep),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_remote_storage_local),
                enabled = enabled,
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.onlyOverWifi,
                        title = stringResource(MR.strings.pref_remote_storage_wifi_only),
                        subtitle = stringResource(MR.strings.pref_remote_storage_wifi_only_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.evictAfterUpload,
                        title = stringResource(MR.strings.pref_remote_storage_evict),
                        subtitle = stringResource(MR.strings.pref_remote_storage_evict_summary),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.evictWhen,
                        entries = evictionEntries(),
                        title = stringResource(MR.strings.pref_remote_storage_evict_when),
                        enabled = evicts,
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = evictAfterDays,
                        valueRange = 1..90,
                        title = stringResource(MR.strings.pref_remote_storage_evict_days),
                        subtitle = pluralStringResource(
                            MR.plurals.pref_remote_storage_evict_days_summary,
                            evictAfterDays,
                            evictAfterDays,
                        ),
                        // Only means anything for the grace-period policy, and
                        // a slider that changes nothing is worse than no slider.
                        enabled = evicts && evictWhen == EvictionTiming.AFTER_DAYS,
                        onValueChanged = { prefs.evictAfterDays.set(it) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = readAhead,
                        valueRange = 0..5,
                        title = stringResource(MR.strings.pref_remote_storage_read_ahead),
                        subtitle = stringResource(MR.strings.pref_remote_storage_read_ahead_summary),
                        onValueChanged = { prefs.readAheadChapters.set(it) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = cacheSizeMb,
                        valueRange = 256..8192,
                        steps = 30,
                        title = stringResource(MR.strings.pref_remote_storage_cache_size),
                        subtitle = stringResource(MR.strings.pref_remote_storage_cache_size_summary, cacheSizeMb),
                        onValueChanged = { prefs.cacheSizeMb.set(it) },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_remote_storage_migrate),
                enabled = enabled,
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_remote_storage_mirror_existing),
                        subtitle = migrationSubtitle(migrationState),
                        onClick = { scope.launch { migrator.run() } },
                    ),
                ),
            ),
        ) + pendingUploadsInfo(pending)
    }

    /**
     * Only shown when there is something to say. A row reading "0 waiting" is
     * noise on every visit.
     */
    @Composable
    private fun pendingUploadsInfo(pending: Int): List<Preference> = if (pending > 0) {
        listOf(
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_remote_storage_pending, pending)),
        )
    } else {
        emptyList()
    }

    @Composable
    private fun migrationSubtitle(state: MirrorExistingDownloads.State): String = when (state) {
        is MirrorExistingDownloads.State.Idle ->
            stringResource(MR.strings.pref_remote_storage_mirror_existing_summary)
        is MirrorExistingDownloads.State.Scanning ->
            stringResource(MR.strings.pref_remote_storage_mirror_existing_scanning, state.found)
        is MirrorExistingDownloads.State.Running ->
            stringResource(
                MR.strings.pref_remote_storage_mirror_existing_running,
                state.uploaded + state.skipped + state.failed,
                state.total,
                state.current,
            )
        is MirrorExistingDownloads.State.Done ->
            stringResource(
                MR.strings.pref_remote_storage_mirror_existing_done,
                state.uploaded,
                state.skipped,
                state.failed,
            )
    }

    @Composable
    private fun statusLabel(status: RemoteHealth.Status): String = when (status) {
        RemoteHealth.Status.UNKNOWN -> stringResource(MR.strings.pref_remote_storage_status_unknown)
        RemoteHealth.Status.OK -> stringResource(MR.strings.pref_remote_storage_status_ok)
        RemoteHealth.Status.DEGRADED -> stringResource(MR.strings.pref_remote_storage_status_degraded)
        RemoteHealth.Status.FAILED -> stringResource(MR.strings.pref_remote_storage_status_failed)
    }

    @Composable
    private fun persistentRoleEntries() = mapOf(
        RemoteRole.ARCHIVE to stringResource(MR.strings.pref_remote_storage_role_archive),
        RemoteRole.MIRROR to stringResource(MR.strings.pref_remote_storage_role_mirror),
    )

    @Composable
    private fun evictionEntries() = mapOf(
        EvictionTiming.AFTER_UPLOAD to stringResource(MR.strings.pref_remote_storage_evict_when_upload),
        EvictionTiming.AFTER_READING to stringResource(MR.strings.pref_remote_storage_evict_when_read),
        EvictionTiming.AFTER_DAYS to stringResource(MR.strings.pref_remote_storage_evict_when_days),
    )

    @Composable
    private fun manualKeepEntries() = mapOf(
        ManualDownloadKeep.CHAPTER to stringResource(MR.strings.pref_remote_storage_manual_keep_chapter),
        ManualDownloadKeep.SERIES to stringResource(MR.strings.pref_remote_storage_manual_keep_series),
        ManualDownloadKeep.NONE to stringResource(MR.strings.pref_remote_storage_manual_keep_none),
    )

    @Composable
    private fun redownloadEntries() = mapOf(
        RedownloadSource.REMOTE to stringResource(MR.strings.pref_remote_storage_redownload_remote),
        RedownloadSource.SOURCE to stringResource(MR.strings.pref_remote_storage_redownload_source),
    )
}

/**
 * Green / amber / red, plus grey for "nothing known yet". Grey matters: a dot
 * that is green before anything has been checked is worse than no dot.
 */
@Composable
private fun StatusDot(status: RemoteHealth.Status) {
    val color = when (status) {
        RemoteHealth.Status.UNKNOWN -> Color(0xFF9E9E9E)
        RemoteHealth.Status.OK -> Color(0xFF4CAF50)
        RemoteHealth.Status.DEGRADED -> Color(0xFFFF9800)
        RemoteHealth.Status.FAILED -> Color(0xFFF44336)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Text carries the meaning for anyone who cannot distinguish the
        // colours; the dot is the at-a-glance version, not the only version.
        Text(text = "", fontSize = 0.sp)
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, CircleShape),
        )
    }
}
