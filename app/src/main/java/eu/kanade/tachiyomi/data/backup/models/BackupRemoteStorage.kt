package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Where the NAS that mirrors downloads is, so a restore on another device can reconnect to it.
 *
 * Tag numbers come from the brief shared with TachiMana and are fixed. The password is only ever
 * present sealed by [mihon.data.backup.NasPasswordCipher], and only when the user ticked the box
 * for it and gave a passphrase.
 *
 * [stateTakenAt] and [files] are reserved for a snapshot of what the NAS held. This app writes
 * neither yet: each chapter's [BackupChapter.onServer] already records that, and the two apps have
 * still to settle which of the two they keep.
 */
@Serializable
class BackupRemoteStorage(
    @ProtoNumber(1) var host: String,
    @ProtoNumber(2) var port: Int? = null,
    @ProtoNumber(3) var useHttps: Boolean? = null,
    @ProtoNumber(4) var folder: String? = null,
    @ProtoNumber(5) var username: String? = null,
    @ProtoNumber(6) var encryptedPassword: ByteArray? = null,
    @ProtoNumber(7) var stateTakenAt: Long? = null,
    @ProtoNumber(8) var files: List<BackupRemoteFile> = emptyList(),
    // The remoteStorageRole setting's value, MIRROR or ARCHIVE
    @ProtoNumber(9) var role: String? = null,
)

@Serializable
class BackupRemoteFile(
    @ProtoNumber(1) var path: String,
    @ProtoNumber(2) var size: Long = 0,
    @ProtoNumber(3) var lastModified: Long = 0,
)
