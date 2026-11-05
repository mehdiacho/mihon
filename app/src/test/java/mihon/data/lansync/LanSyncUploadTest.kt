package mihon.data.lansync

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LanSyncUploadTest {

    @Test
    fun `a chapter archive three levels under downloads is accepted`() {
        assertEquals(
            LanSyncUpload.Target("Test Source (EN)", "Test Manga", "Test_Chapter 1.cbz"),
            LanSyncUpload.parseTarget("downloads/Test Source (EN)/Test Manga/Test_Chapter 1.cbz"),
        )
        assertEquals("C", LanSyncUpload.parseTarget("downloads/S/M/C.cbz")?.chapterDirName)
    }

    @Test
    fun `paths outside a chapter archive are refused`() {
        listOf(
            "",
            "/downloads/S/M/C.cbz",
            "C:/downloads/S/M/C.cbz",
            "downloads/../S/M/C.cbz",
            "downloads/S/../M/C.cbz",
            "downloads/S/M/../../../etc.cbz",
            "downloads/S/M/C.cbz/..",
            "backup/S/M/C.cbz",
            "downloads/S/C.cbz",
            "downloads/S/M/X/C.cbz",
            "downloads/S/M/C",
            "downloads/S/M/.cbz",
            "downloads/S/M/C.cbz.part",
            "downloads/S/M/.hidden.cbz",
            "downloads/.S/M/C.cbz",
            "downloads/S//C.cbz",
            "downloads\\S\\M\\C.cbz",
        ).forEach { path ->
            assertNull(LanSyncUpload.parseTarget(path), path)
        }
    }

    @Test
    fun `length must be present, positive and at most the limit`() {
        assertNull(LanSyncUpload.parseLength(null))
        assertNull(LanSyncUpload.parseLength(""))
        assertNull(LanSyncUpload.parseLength("abc"))
        assertNull(LanSyncUpload.parseLength("0"))
        assertNull(LanSyncUpload.parseLength("-5"))
        assertNull(LanSyncUpload.parseLength((LanSyncUpload.MAX_BYTES + 1).toString()))
        assertEquals(42L, LanSyncUpload.parseLength(" 42 "))
        assertEquals(LanSyncUpload.MAX_BYTES, LanSyncUpload.parseLength(LanSyncUpload.MAX_BYTES.toString()))
    }

    @Test
    fun `copy is exact, short or too long`() {
        val data = ByteArray(200_000) { it.toByte() }

        val out = ByteArrayOutputStream()
        assertEquals(
            LanSyncUpload.Copy.COMPLETE,
            LanSyncUpload.copyExactly(ByteArrayInputStream(data), out, data.size.toLong()),
        )
        assertArrayEquals(data, out.toByteArray())

        // The connection is cut before the declared length arrives
        assertEquals(
            LanSyncUpload.Copy.SHORT,
            LanSyncUpload.copyExactly(ByteArrayInputStream(data), ByteArrayOutputStream(), data.size + 1L),
        )

        // More bytes follow than were declared
        assertEquals(
            LanSyncUpload.Copy.TOO_LONG,
            LanSyncUpload.copyExactly(ByteArrayInputStream(data), ByteArrayOutputStream(), data.size - 1L),
        )
    }
}
