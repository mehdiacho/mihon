package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * "Link to a computer": address and code typed, pasted as a `tachimana://link` URI, or scanned
 * from the computer's QR code. Walks through a profile mismatch and the optional restore.
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
    var offerRestore by remember { mutableStateOf<LanSyncLink.Link?>(null) }

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
                    if (afterJoin) offerRestore = result.link else onDismissRequest()
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
    val restoreFrom = offerRestore
    when {
        restoreFrom != null -> AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(MR.strings.pref_lan_sync_link_restore_title)) },
            text = { Text(stringResource(MR.strings.pref_lan_sync_link_restore_text, restoreFrom.peerName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching { linker.restoreBackupFrom(restoreFrom) }
                                .onSuccess { context.toast(MR.strings.pref_lan_sync_link_restoring) }
                                .onFailure { context.toast(it.message ?: it.javaClass.simpleName) }
                            onDismissRequest()
                        }
                    },
                ) { Text(stringResource(MR.strings.action_restore)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_not_now)) }
            },
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
