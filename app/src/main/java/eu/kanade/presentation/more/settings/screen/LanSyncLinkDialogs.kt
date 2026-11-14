package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.data.lansync.LanSyncLink
import mihon.data.lansync.LanSyncLinker
import mihon.data.lansync.LanSyncProtocol
import mihon.data.lansync.LanSyncRoles
import mihon.data.lansync.LanSyncSync
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * "Link to a computer": address and code typed, pasted as a `tachimana://link` URI, or scanned
 * from the computer's QR code. Walks through a profile mismatch, then `roles/1`'s questions.
 */
@Composable
internal fun LinkComputerDialog(onDismissRequest: () -> Unit, initialInvite: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val linker = remember { context.appGraph.lanSyncLinker }

    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // The steps after the first answer
    var otherProfile by remember { mutableStateOf<LanSyncLink.LinkResponse?>(null) }
    var canJoin by remember { mutableStateOf(false) }
    // roles/1's questions, once linked; the first only when this phone already has a library
    var setUp by remember { mutableStateOf<Pair<LanSyncLink.Link, Boolean>?>(null) }
    val sync = remember { context.appGraph.lanSyncSync }

    val wrongCode = stringResource(MR.strings.pref_lan_sync_link_wrong_code)
    val lockedOut = stringResource(MR.strings.pref_lan_sync_link_locked)
    val badAddress = stringResource(MR.strings.pref_lan_sync_link_bad_address)

    fun applyInvite(text: String) {
        val invite = LanSyncLink.parseInvite(text) ?: return
        address = "${invite.host}:${invite.port}"
        invite.code?.let { code = it }
    }

    // An invite the app was opened with fills the fields; linking still waits for the user to tap Link
    LaunchedEffect(initialInvite) { initialInvite?.let(::applyInvite) }

    fun attempt(afterJoin: Boolean = false) {
        val invite = LanSyncLink.parseInvite(address)
        if (invite == null) {
            error = badAddress
            return
        }
        busy = true
        error = null
        scope.launch {
            when (val result = linker.link(invite.host, invite.port, code)) {
                is LanSyncLinker.Result.Linked -> {
                    context.toast(context.stringResource(MR.strings.pref_lan_sync_linked, result.link.peerName))
                    setUp = result.link to (!afterJoin && sync.hasLibrary())
                }
                is LanSyncLinker.Result.OtherProfile -> {
                    canJoin = linker.canJoinOtherProfile()
                    otherProfile = result.peer
                }
                LanSyncLinker.Result.WrongCode -> error = wrongCode
                LanSyncLinker.Result.LockedOut -> error = lockedOut
                is LanSyncLinker.Result.Failed -> error = result.reason
            }
            busy = false
        }
    }

    val peer = otherProfile
    val setting = setUp
    when {
        setting != null -> SyncSetupDialog(
            link = setting.first,
            askStart = setting.second,
            changing = false,
            onDismissRequest = onDismissRequest,
        )

        peer != null && canJoin -> AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(MR.strings.pref_lan_sync_link_other_profile_title)) },
            text = { Text(stringResource(MR.strings.pref_lan_sync_link_other_profile_join, peer.profileName)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        linker.joinProfile(peer)
                        otherProfile = null
                        attempt(afterJoin = true)
                    },
                ) { Text(stringResource(MR.strings.pref_lan_sync_link_use_profile)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )

        peer != null -> AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(MR.strings.pref_lan_sync_link_other_profile_title)) },
            text = { Text(stringResource(MR.strings.pref_lan_sync_link_other_profile_refused, peer.profileName)) },
            confirmButton = {
                TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_ok)) }
            },
        )

        else -> AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(MR.strings.pref_lan_sync_link_computer)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(MR.strings.pref_lan_sync_link_computer_text))
                    OutlinedButton(
                        onClick = {
                            val options = GmsBarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                .build()
                            GmsBarcodeScanning.getClient(context, options).startScan()
                                .addOnSuccessListener { barcode -> barcode.rawValue?.let(::applyInvite) }
                                .addOnFailureListener { error = it.message }
                        },
                    ) { Text(stringResource(MR.strings.pref_lan_sync_link_scan)) }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { text ->
                            // A pasted link fills both fields
                            if (text.startsWith(LanSyncLink.INVITE_SCHEME, ignoreCase = true)) {
                                applyInvite(text)
                            } else {
                                address = text
                            }
                        },
                        label = { Text(stringResource(MR.strings.pref_lan_sync_address)) },
                        placeholder = { Text("192.168.0.10:${LanSyncProtocol.DEFAULT_PORT}") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(MR.strings.pref_lan_sync_code)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && address.isNotBlank() && code.length == 6,
                    onClick = { attempt() },
                ) {
                    Text(stringResource(if (busy) MR.strings.pref_lan_sync_linking else MR.strings.pref_lan_sync_link))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}

@Composable
internal fun UnlinkDialog(link: LanSyncLink.Link, onDismissRequest: () -> Unit, onUnlink: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.pref_lan_sync_unlink_title, link.peerName)) },
        text = { Text(stringResource(MR.strings.pref_lan_sync_unlink_text)) },
        confirmButton = {
            TextButton(onClick = onUnlink) { Text(stringResource(MR.strings.pref_lan_sync_unlink)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

/**
 * `roles/1`'s two questions, in order: which library to start from (only when this phone has one),
 * then who leads. "Both ways" is selected unless the link already says otherwise.
 * [changing] is the "Change…" entry: the first question can then leave both libraries alone.
 */
@Composable
internal fun SyncSetupDialog(
    link: LanSyncLink.Link,
    askStart: Boolean,
    changing: Boolean,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val sync = remember { context.appGraph.lanSyncSync }
    var onStart by remember { mutableStateOf(askStart) }
    var start by remember {
        mutableStateOf(if (changing) null else LanSyncSync.Start.COMBINE)
    }
    var mode by remember { mutableStateOf(link.mode) }

    if (onStart) {
        val options = listOfNotNull(
            LanSyncSync.Start.MINE to stringResource(MR.strings.lan_sync_start_mine),
            LanSyncSync.Start.THEIRS to stringResource(MR.strings.lan_sync_start_theirs, link.peerName),
            LanSyncSync.Start.COMBINE to stringResource(MR.strings.lan_sync_start_combine),
            if (changing) null to stringResource(MR.strings.lan_sync_start_keep) else null,
        )
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(MR.strings.lan_sync_start_title)) },
            text = {
                Column {
                    Text(stringResource(MR.strings.lan_sync_start_text, link.peerName))
                    options.forEach { (value, label) ->
                        ChoiceRow(label = label, selected = start == value, onClick = { start = value })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onStart = false }) { Text(stringResource(MR.strings.lan_sync_next)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
        return
    }

    val modes = listOf(
        LanSyncRoles.BOTH to stringResource(MR.strings.lan_sync_mode_both),
        LanSyncRoles.I_LEAD to stringResource(MR.strings.lan_sync_mode_i_lead, link.peerName),
        LanSyncRoles.I_FOLLOW to stringResource(MR.strings.lan_sync_mode_they_lead, link.peerName),
    )
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.lan_sync_mode_title)) },
        text = {
            Column {
                modes.forEach { (value, label) ->
                    ChoiceRow(label = label, selected = mode == value, onClick = { mode = value })
                }
                Text(
                    stringResource(MR.strings.lan_sync_mode_text),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        askStart || changing -> sync.setUp(link, if (askStart) start else null, mode)
                        else -> sync.startEmpty(link, mode)
                    }
                    onDismissRequest()
                },
            ) { Text(stringResource(MR.strings.lan_sync_done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

/** What can be done with one linked device: the roles, the one-off copies, unlinking. */
@Composable
internal fun LinkedDeviceDialog(
    link: LanSyncLink.Link,
    onDismissRequest: () -> Unit,
    onChange: () -> Unit,
    onUnlink: () -> Unit,
) {
    val context = LocalContext.current
    val sync = remember { context.appGraph.lanSyncSync }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(link.peerName) },
        text = {
            Column {
                Text(modeLabel(link.mode))
                TextButton(onClick = onChange) { Text(stringResource(MR.strings.lan_sync_change)) }
                TextButton(
                    onClick = {
                        sync.sendEverythingOnce(link)
                        onDismissRequest()
                    },
                ) { Text(stringResource(MR.strings.lan_sync_send_once, link.peerName)) }
                TextButton(
                    onClick = {
                        sync.takeEverything(link)
                        onDismissRequest()
                    },
                ) { Text(stringResource(MR.strings.lan_sync_take_once, link.peerName)) }
                TextButton(onClick = onUnlink) { Text(stringResource(MR.strings.pref_lan_sync_unlink)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_close)) }
        },
    )
}

@Composable
internal fun modeLabel(mode: String): String = stringResource(
    when (mode) {
        LanSyncRoles.I_LEAD -> MR.strings.lan_sync_mode_short_lead
        LanSyncRoles.I_FOLLOW -> MR.strings.lan_sync_mode_short_follow
        else -> MR.strings.lan_sync_mode_short_both
    },
)

/** A full copy waiting for the person: asked for by the computer, or a replace started here. */
@Composable
internal fun TakeRequestDialog(take: LanSyncSync.PendingTake, onAnswer: (Boolean) -> Unit) {
    val replace = take.how == LanSyncRoles.HOW_REPLACE
    val text = when {
        !take.fromPeer -> MR.strings.lan_sync_take_replace_local
        replace -> MR.strings.lan_sync_take_replace_peer
        else -> MR.strings.lan_sync_take_merge_peer
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(MR.strings.lan_sync_take_title, take.peerName)) },
        text = { Text(stringResource(text, take.peerName, take.theirs, take.mine)) },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) {
                Text(stringResource(if (replace) MR.strings.lan_sync_take_replace else MR.strings.lan_sync_take_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) { Text(stringResource(MR.strings.lan_sync_decline)) }
        },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
    }
}
