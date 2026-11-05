package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * An installed extension, so that a restore on another device can put it back.
 *
 * [BackupSource] only names the sources a library uses, which is enough to show a stub but not
 * to find the apk that provides them. This carries the package and where it came from.
 *
 * The tag numbers are shared with TachiMana, which reads and writes the same message, so they are
 * fixed rather than picked to fit this file. Everything past [pkgName] is optional so that a
 * partial record from either side still decodes.
 */
@Serializable
class BackupExtension(
    @ProtoNumber(1) var pkgName: String,
    @ProtoNumber(2) var name: String? = null,
    @ProtoNumber(3) var versionCode: Long? = null,
    @ProtoNumber(4) var versionName: String? = null,
    @ProtoNumber(5) var lang: String? = null,
    // Same form as BackupExtensionStore.indexUrl; null when sideloaded
    @ProtoNumber(6) var repoIndexUrl: String? = null,
    // Unpacked on purpose: that is what TachiMana writes
    @ProtoNumber(7) var sourceIds: List<Long> = emptyList(),
    // SHA-256 of the signing certificate, as compared against ExtensionStore.signingKey
    @ProtoNumber(8) var signatureHash: String? = null,
)
