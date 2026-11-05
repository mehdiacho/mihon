package eu.kanade.tachiyomi.data.backup.create.creators

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

@Inject
class ExtensionsBackupCreator(
    private val context: Context,
    private val extensionManager: ExtensionManager,
    private val api: ExtensionApi,
) {

    suspend operator fun invoke(): List<BackupExtension> {
        val installed = extensionManager.getLoadedExtensions() + extensionManager.getNotLoadedExtensions()
        if (installed.isEmpty()) return emptyList()

        // Which store an extension came from is only known once the store indexes have been
        // fetched, which an automatic backup can run before. Without it a restore has nowhere to
        // install from, so fetch rather than write a record that can't be acted on.
        val available = extensionManager.availableExtensionsFlow.value
            .ifEmpty {
                try {
                    api.findExtensions()
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Backing up extensions without their stores" }
                    emptyList()
                }
            }
            .associateBy { it.pkgName }

        return installed.map { it.toBackupExtension(available[it.pkgName]) }
    }

    private fun Extension.Installed.toBackupExtension(available: Extension.Available?) =
        BackupExtension(
            pkgName = pkgName,
            name = name,
            versionCode = versionCode,
            versionName = versionName,
            lang = lang ?: available?.lang,
            repoIndexUrl = (this as? Extension.Loaded)?.store?.indexUrl ?: available?.store?.indexUrl,
            // An extension that never loaded has no sources of its own to report
            sourceIds = (this as? Extension.Loaded)?.sources?.map { it.id }
                ?: available?.sources?.map { it.id }
                ?: emptyList(),
            signatureHash = ExtensionLoader.getSignatureHash(context, pkgName),
        )
}
