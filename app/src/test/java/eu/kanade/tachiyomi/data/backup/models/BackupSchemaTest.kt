package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The fork's backup additions are read by TachiMana as well, so their wire format is a contract
 * rather than a detail. These pin the bytes, not just a round trip through the same code.
 */
@OptIn(ExperimentalSerializationApi::class)
class BackupSchemaTest {

    private val proto = ProtoBuf

    @Test
    fun `installed extensions are written under tag 900`() {
        val backup = Backup(backupManga = emptyList(), backupExtensions = listOf(BackupExtension(pkgName = "a")))
        val bytes = proto.encodeToByteArray(Backup.serializer(), backup).toUByteList()

        // (900 << 3) | 2, as a varint
        assertEquals(listOf(0xA2u, 0x38u).map { it.toUByte() }, bytes.take(2))
    }

    @Test
    fun `source ids are unpacked`() {
        val bytes = proto.encodeToByteArray(
            BackupExtension.serializer(),
            BackupExtension(pkgName = "a", sourceIds = listOf(1L, 2L)),
        ).toUByteList()

        // One varint key per id, (7 << 3) | 0, and never the packed form (7 << 3) | 2
        assertEquals(2, bytes.count { it == 0x38u.toUByte() })
        assertFalse(0x3Au.toUByte() in bytes)
    }

    @Test
    fun `downloaded is tag 900 on a chapter and absent when false`() {
        val downloaded = proto.encodeToByteArray(
            BackupChapter.serializer(),
            BackupChapter(url = "u", name = "n", downloaded = true),
        ).toUByteList()
        val notDownloaded = proto.encodeToByteArray(
            BackupChapter.serializer(),
            BackupChapter(url = "u", name = "n"),
        ).toUByteList()

        // (900 << 3) | 0, then true
        assertTrue(downloaded.windowed(3).contains(listOf(0xA0u, 0x38u, 0x01u).map { it.toUByte() }))
        assertFalse(notDownloaded.windowed(2).contains(listOf(0xA0u, 0x38u).map { it.toUByte() }))
    }

    @Test
    fun `on server is tag 901 and independent of downloaded`() {
        val bytes = proto.encodeToByteArray(
            BackupChapter.serializer(),
            BackupChapter(url = "u", name = "n", onServer = true),
        ).toUByteList()

        // (901 << 3) | 0, then true; and nothing claiming it is on the device
        assertTrue(bytes.windowed(3).contains(listOf(0xA8u, 0x38u, 0x01u).map { it.toUByte() }))
        assertFalse(bytes.windowed(2).contains(listOf(0xA0u, 0x38u).map { it.toUByte() }))
    }

    @Test
    fun `round trips and reads backups without the new tags`() {
        val extension = BackupExtension(
            pkgName = "eu.kanade.tachiyomi.extension.en.example",
            name = "Example",
            versionCode = 12,
            versionName = "1.4.12",
            lang = "en",
            repoIndexUrl = "https://example.org/index.min.json",
            sourceIds = listOf(1L, -2L),
            signatureHash = "abc",
        )
        val decoded = proto.decodeFromByteArray(
            Backup.serializer(),
            proto.encodeToByteArray(Backup.serializer(), Backup(emptyList(), backupExtensions = listOf(extension))),
        ).backupExtensions.single()

        assertEquals(extension.pkgName, decoded.pkgName)
        assertEquals(extension.versionCode, decoded.versionCode)
        assertEquals(extension.repoIndexUrl, decoded.repoIndexUrl)
        assertEquals(extension.sourceIds, decoded.sourceIds)
        assertEquals(extension.signatureHash, decoded.signatureHash)

        val old = proto.encodeToByteArray(Backup.serializer(), Backup(emptyList()))
        assertTrue(proto.decodeFromByteArray(Backup.serializer(), old).backupExtensions.isEmpty())
    }

    @Test
    fun `NAS connection is tag 901 on a backup and round trips`() {
        val remote = BackupRemoteStorage(
            host = "nas.example.test",
            port = 5006,
            useHttps = true,
            folder = "manga/preview",
            username = "reader",
            encryptedPassword = byteArrayOf(1, 2, 3),
            role = "ARCHIVE",
        )
        val encoded = proto.encodeToByteArray(Backup.serializer(), Backup(emptyList(), remoteStorage = remote))

        // (901 << 3) | 2, as a varint
        assertEquals(listOf(0xAAu, 0x38u).map { it.toUByte() }, encoded.toUByteList().take(2))

        val decoded = proto.decodeFromByteArray(Backup.serializer(), encoded).remoteStorage!!
        assertEquals(remote.host, decoded.host)
        assertEquals(remote.port, decoded.port)
        assertEquals(remote.useHttps, decoded.useHttps)
        assertEquals(remote.folder, decoded.folder)
        assertEquals(remote.username, decoded.username)
        assertEquals(remote.role, decoded.role)
        assertTrue(remote.encryptedPassword!!.contentEquals(decoded.encryptedPassword))

        val old = proto.encodeToByteArray(Backup.serializer(), Backup(emptyList()))
        assertEquals(null, proto.decodeFromByteArray(Backup.serializer(), old).remoteStorage)
    }

    private fun ByteArray.toUByteList() = map { it.toUByte() }
}
