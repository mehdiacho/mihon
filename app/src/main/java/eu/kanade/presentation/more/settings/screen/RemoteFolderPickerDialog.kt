package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.data.remote.WebDavClient
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.KeyboardArrowLeft
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Picks the folder on the server that chapters go into.
 *
 * Browsing rather than typing a path, because a typo looks exactly like an
 * empty folder until a few gigabytes have gone into the wrong one.
 */
@Composable
fun RemoteFolderPickerDialog(
    client: WebDavClient?,
    initialPath: String,
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var path by remember { mutableStateOf(initialPath.splitPath()) }
    var folders by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var reloads by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path, reloads) {
        loading = true
        val listing = client?.let { withContext(Dispatchers.IO) { it.list(path) } }
        // Null is a request that failed. Empty is a folder with nothing in it,
        // which is a perfectly good answer and often the one being looked for.
        failed = listing == null
        folders = listing.orEmpty().filter { it.isDirectory }.map { it.name }.sorted()
        loading = false
    }

    if (creating) {
        CreateFolderDialog(
            onDismissRequest = { creating = false },
            onCreate = { name ->
                creating = false
                scope.launch {
                    withContext(Dispatchers.IO) { client?.createCollections(path + name) }
                    path = path + name
                    reloads++
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.pref_remote_storage_folder)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                item {
                    Text(
                        text = "/" + path.joinToString("/"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                when {
                    loading -> item {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    failed -> item {
                        Text(
                            text = stringResource(MR.strings.pref_remote_storage_folder_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {
                        if (path.isNotEmpty()) {
                            item {
                                FolderRow(
                                    icon = MaterialSymbols.Rounded.KeyboardArrowLeft,
                                    label = "..",
                                    onClick = { path = path.dropLast(1) },
                                )
                            }
                        }
                        items(folders) { name ->
                            FolderRow(
                                icon = MaterialSymbols.Rounded.Folder,
                                label = name,
                                onClick = { path = path + name },
                            )
                        }
                        if (folders.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(MR.strings.pref_remote_storage_folder_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { creating = true }, enabled = client != null) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Add,
                        contentDescription = stringResource(MR.strings.pref_remote_storage_folder_create),
                        modifier = Modifier.size(18.dp),
                    )
                }
                TextButton(onClick = { onConfirm(path.joinToString("/")) }) {
                    Text(stringResource(MR.strings.action_ok))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

private fun String.splitPath(): List<String> = trim().trim('/').split('/').filter { it.isNotEmpty() }

@Composable
private fun FolderRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(text = label)
    }
}

@Composable
private fun CreateFolderDialog(onDismissRequest: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.pref_remote_storage_folder_create)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace("/", "") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(MR.strings.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
