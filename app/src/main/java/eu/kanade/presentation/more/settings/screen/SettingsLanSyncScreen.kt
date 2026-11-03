package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.more.settings.Preference
import mihon.app.di.appGraph
import mihon.data.lansync.LanSyncServer
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsLanSyncScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_lan_sync

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val prefs = remember { context.appGraph.lanSyncPreferences }
        val server = remember { context.appGraph.lanSyncServer }

        val enabled by prefs.enabled.collectAsState()
        val code by prefs.pairCode.collectAsState()
        val state by server.state.collectAsStateWithLifecycle()

        val address = when (val current = state) {
            is LanSyncServer.State.Running -> if (current.addresses.isEmpty()) {
                stringResource(MR.strings.pref_lan_sync_no_network, current.port)
            } else {
                current.addresses.joinToString { "$it:${current.port}" }
            }
            is LanSyncServer.State.Failed -> stringResource(MR.strings.pref_lan_sync_failed, current.reason)
            LanSyncServer.State.Off -> stringResource(MR.strings.pref_lan_sync_off)
        }

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = prefs.enabled,
                title = stringResource(MR.strings.pref_lan_sync_enable),
                subtitle = stringResource(MR.strings.pref_lan_sync_enable_summary),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_lan_sync_pairing),
                enabled = enabled,
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_lan_sync_code),
                        subtitle = code.chunked(3).joinToString(" ").ifEmpty { "–" },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_lan_sync_new_code),
                        subtitle = stringResource(MR.strings.pref_lan_sync_new_code_summary),
                        onClick = { prefs.newPairCode() },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_lan_sync_address),
                        subtitle = address,
                    ),
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(MR.strings.pref_lan_sync_info),
                    ),
                ),
            ),
        )
    }
}
