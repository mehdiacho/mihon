package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import mihon.app.di.appGraph
import mihon.data.lansync.LanSyncLink
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
        val links = remember { context.appGraph.lanSyncLinks }
        val linked by remember { links.changes() }.collectAsStateWithLifecycle(links.all())
        val profileName by prefs.profileName.collectAsState()

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
                visible = enabled,
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
            // Fork: pairing v2, linking this install to a TachiMana profile on a computer
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_lan_sync_links),
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.profileName,
                        title = stringResource(MR.strings.pref_lan_sync_profile),
                        subtitle = profileName.ifBlank { links.profileName },
                    ),
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.pref_lan_sync_link_computer),
                    ) {
                        var linking by remember { mutableStateOf(false) }
                        TextPreferenceWidget(
                            title = stringResource(MR.strings.pref_lan_sync_link_computer),
                            subtitle = stringResource(MR.strings.pref_lan_sync_link_computer_summary),
                            onPreferenceClick = { linking = true },
                        )
                        if (linking) LinkComputerDialog(onDismissRequest = { linking = false })
                    },
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.pref_lan_sync_linked_devices),
                    ) {
                        var unlinking by remember { mutableStateOf<LanSyncLink.Link?>(null) }
                        // The settings wrapper stacks its children, so the rows need their own column
                        Column {
                            if (linked.isEmpty()) {
                                TextPreferenceWidget(
                                    title = stringResource(MR.strings.pref_lan_sync_linked_devices),
                                    subtitle = stringResource(MR.strings.pref_lan_sync_no_links),
                                )
                            }
                            linked.forEach { link ->
                                TextPreferenceWidget(
                                    title = link.peerName,
                                    subtitle = listOfNotNull(link.platform, link.lastAddress).joinToString(" · "),
                                    onPreferenceClick = { unlinking = link },
                                )
                            }
                        }
                        unlinking?.let { link ->
                            UnlinkDialog(
                                link = link,
                                onDismissRequest = { unlinking = null },
                                onUnlink = {
                                    links.remove(link.peerDeviceId)
                                    unlinking = null
                                },
                            )
                        }
                    },
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(MR.strings.pref_lan_sync_links_info),
                    ),
                ),
            ),
        )
    }
}
