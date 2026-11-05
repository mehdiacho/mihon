package mihon.data.lansync

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a stranger on the LAN can and can't get out of the phone. The manifest JSON is the contract
 * with TachiMana's puller, so its field names are pinned here as well.
 */
class LanSyncProtocolTest {

    private fun request(raw: String) = LanSyncProtocol.readRequest(raw.byteInputStream(Charsets.ISO_8859_1))

    @Test
    fun `reads a GET with an encoded path query`() {
        val r = request(
            "GET /file?path=downloads%2FSrc%20(EN)%2FTitle%2FCh+1%2B2.cbz HTTP/1.1\r\n" +
                "Host: x\r\nX-Pair-Code: 123456\r\n\r\n",
        )!!

        assertEquals("GET", r.method)
        assertEquals("/file", r.path)
        assertEquals("downloads/Src (EN)/Title/Ch 1+2.cbz", r.query["path"])
        assertEquals("123456", r.header("x-pair-code"))
    }

    @Test
    fun `refuses what isn't HTTP and heads that never end`() {
        assertNull(request("hello\r\n\r\n"))
        assertNull(request("GET / SPDY/3\r\n\r\n"))
        assertNull(request("GET /ping HTTP/1.1\r\nHost: x\r\n"))
        assertNull(request("GET /ping HTTP/1.1\r\nX: " + "a".repeat(9000) + "\r\n\r\n"))
        assertNull(request("GET /file?path=%zz HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun `only plain paths under downloads are servable`() {
        assertTrue(LanSyncProtocol.isSafePath("downloads/Src (EN)/Title/Ch 1.cbz"))

        listOf(
            "",
            "/etc/passwd",
            "downloads/../shared_prefs/x.xml",
            "downloads/Src/./Title/a.cbz",
            "downloads/Src//a.cbz",
            "downloads\\Src\\a.cbz",
            "downloads/C:/a.cbz",
            "content://x/downloads/a.cbz",
            "databases/tachiyomi.db",
            "downloads/a\u0000.cbz",
        ).forEach { assertFalse(LanSyncProtocol.isSafePath(it), it) }
    }

    @Test
    fun `pair code must match exactly`() {
        assertTrue(LanSyncProtocol.codeMatches("042137", "042137"))
        assertTrue(LanSyncProtocol.codeMatches("042137", " 042137 "))
        assertFalse(LanSyncProtocol.codeMatches("042137", "42137"))
        assertFalse(LanSyncProtocol.codeMatches("042137", null))
        assertFalse(LanSyncProtocol.codeMatches("", ""))
    }

    @Test
    fun `locks after five wrong codes in a minute and unlocks after five more`() {
        var now = 0L
        val lockout = PairCodeLockout(clock = { now })

        repeat(4) { lockout.recordFailure() }
        assertFalse(lockout.isLocked())

        // Spread out, old failures stop counting
        now += 61_000
        lockout.recordFailure()
        assertFalse(lockout.isLocked())

        repeat(4) { lockout.recordFailure() }
        assertTrue(lockout.isLocked())

        now += 5 * 60_000 - 1
        assertTrue(lockout.isLocked())
        now += 1
        assertFalse(lockout.isLocked())
    }

    @Test
    fun `manifest uses the brief's field names and a string source id`() {
        val manifest = LanSyncProtocol.Manifest(
            deviceId = "d",
            deviceName = "Phone",
            backupSize = 10,
            files = listOf(
                LanSyncProtocol.ManifestFile(
                    path = "downloads/S/T/C.cbz",
                    size = 5,
                    lastModified = 1,
                    sourceId = "9223372036854775807",
                    mangaUrl = "/manga/x",
                    chapterUrl = "/chapter/y",
                ),
            ),
        )

        assertEquals(
            """{"deviceId":"d","deviceName":"Phone","backupSize":10,"files":[{"path":"downloads/S/T/C.cbz",""" +
                """"size":5,"lastModified":1,"kind":"download","sourceId":"9223372036854775807",""" +
                """"mangaUrl":"/manga/x","chapterUrl":"/chapter/y","packed":false}]}""",
            Json { encodeDefaults = true }.encodeToString(manifest),
        )
    }
}
