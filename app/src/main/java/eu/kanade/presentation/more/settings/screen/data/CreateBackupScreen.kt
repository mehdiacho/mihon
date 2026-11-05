package eu.kanade.presentation.more.settings.screen.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.backup.create.BackupCreateWorker
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.data.backup.NasPasswordCipher
import mihon.data.remote.RemoteStoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class CreateBackupScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<CreateBackupViewModel>()
        val state by viewModel.state.collectAsState()
        val scope = rememberCoroutineScope()

        val chooseBackupDir = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/*"),
        ) {
            if (it != null) {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                viewModel.createBackup(context, it)
                navigator.pop()
            }
        }

        fun chooseLocation() {
            try {
                chooseBackupDir.launch(BackupCreator.getFilename())
            } catch (_: ActivityNotFoundException) {
                context.toast(MR.strings.file_picker_error)
            }
        }

        var askPassphrase by remember { mutableStateOf(false) }

        if (askPassphrase) {
            PassphraseDialog(
                onDismissRequest = { askPassphrase = false },
                onConfirm = { passphrase ->
                    askPassphrase = false
                    scope.launch {
                        viewModel.sealNasPassword(passphrase)
                        chooseLocation()
                    }
                },
            )
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_create_backup),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumnWithAction(
                contentPadding = contentPadding,
                actionLabel = stringResource(MR.strings.action_create),
                actionEnabled = state.options.canCreate(),
                onClickAction = {
                    scope.launch {
                        when {
                            BackupCreateWorker.isManualJobRunning(context) ->
                                context.toast(MR.strings.backup_in_progress)
                            viewModel.needsPassphrase() -> askPassphrase = true
                            else -> chooseLocation()
                        }
                    }
                },
            ) {
                if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                    item {
                        WarningBanner(MR.strings.restore_miui_warning)
                    }
                }

                item {
                    SectionCard(MR.strings.label_library) {
                        Options(BackupOptions.libraryOptions, state, viewModel)
                    }
                }

                item {
                    SectionCard(MR.strings.label_settings) {
                        Options(BackupOptions.settingsOptions, state, viewModel)
                    }
                }
            }
        }
    }

    @Composable
    private fun PassphraseDialog(onDismissRequest: () -> Unit, onConfirm: (String) -> Unit) {
        var passphrase by remember { mutableStateOf("") }
        var repeat by remember { mutableStateOf("") }
        val error = when {
            passphrase.isNotEmpty() && passphrase.length < MIN_PASSPHRASE -> MR.strings.backup_nas_passphrase_short
            repeat.isNotEmpty() && repeat != passphrase -> MR.strings.backup_nas_passphrase_mismatch
            else -> null
        }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(MR.strings.backup_nas_passphrase_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(MR.strings.backup_nas_passphrase_text))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(MR.strings.backup_nas_passphrase)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = { Text(stringResource(MR.strings.backup_nas_passphrase_repeat)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let { { Text(stringResource(it)) } },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.length >= MIN_PASSPHRASE && repeat == passphrase,
                    onClick = { onConfirm(passphrase) },
                ) {
                    Text(stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    @Composable
    private fun Options(
        options: List<BackupOptions.Entry>,
        state: CreateBackupViewModel.State,
        model: CreateBackupViewModel,
    ) {
        options.forEach { option ->
            LabeledCheckbox(
                label = stringResource(option.label),
                checked = option.getter(state.options),
                onCheckedChange = {
                    model.toggle(option.setter, it)
                },
                enabled = option.enabled(state.options),
            )
        }
    }
}

private const val MIN_PASSPHRASE = 8

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CreateBackupViewModel(
    private val remoteStoragePreferences: RemoteStoragePreferences,
) : ViewModel() {

    // Sealed before the job starts so only ciphertext is handed to WorkManager
    private var sealedNasPassword: ByteArray? = null

    val state: StateFlow<CreateBackupViewModel.State>
        field = MutableStateFlow<CreateBackupViewModel.State>(State())

    fun toggle(setter: (BackupOptions, Boolean) -> BackupOptions, enabled: Boolean) {
        state.update {
            it.copy(
                options = setter(it.options, enabled),
            )
        }
    }

    /** Whether to ask for a passphrase first. With no password saved there is nothing to seal. */
    fun needsPassphrase(): Boolean =
        state.value.options.let { it.remoteStorage && it.remoteStoragePassword } &&
            remoteStoragePreferences.password.get().isNotEmpty()

    /** Argon2id with 64 MiB takes a moment, so off the main thread. */
    suspend fun sealNasPassword(passphrase: String) {
        val password = remoteStoragePreferences.password.get()
        sealedNasPassword = withContext(Dispatchers.Default) { NasPasswordCipher.encrypt(password, passphrase) }
    }

    fun createBackup(context: Context, uri: Uri) {
        val options = state.value.options
        if (options.remoteStoragePassword && sealedNasPassword == null) {
            context.toast(MR.strings.backup_nas_no_password)
        }
        BackupCreateWorker.startNow(context, uri, options, sealedNasPassword.takeIf { options.remoteStoragePassword })
        sealedNasPassword = null
    }

    @Immutable
    data class State(
        val options: BackupOptions = BackupOptions(),
    )
}
