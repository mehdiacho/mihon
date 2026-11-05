package mihon.data.lansync

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Who gets a link key, and what the key and invite look like on the wire. */
class LanSyncLinkTest {

    private fun body(json: String) = json.toByteArray()

    private val valid = """{"deviceId":"pc-1","deviceName":"Desk","profileId":"p-1","profileName":"Main",""" +
        """"platform":"desktop","port":27193}"""

    @Test
    fun `parses a link request and refuses malformed ones`() {
        val request = LanSyncLink.parseRequest(body(valid))!!
        assertEquals("pc-1", request.deviceId)
        assertEquals("desktop", request.platform)
        assertEquals(27193, request.port)

        // Port is optional, unknown fields are ignored
        val minimal = """{"deviceId":"a","deviceName":"b","profileId":"c","profileName":"d",""" +
            """"platform":"android","x":1}"""
        assertNull(LanSyncLink.parseRequest(body(minimal))!!.port)

        fun request(
            deviceId: String = "a",
            profileName: String? = "d",
            platform: String = "android",
            port: Int? = null,
        ) = buildString {
            append("""{"deviceId":"$deviceId","deviceName":"b","profileId":"c",""")
            profileName?.let { append(""""profileName":"$it",""") }
            port?.let { append(""""port":$it,""") }
            append(""""platform":"$platform"}""")
        }

        listOf(
            "not json",
            request(profileName = null),
            request(deviceId = ""),
            request(platform = "ios"),
            request(port = 70000),
            request(deviceId = "x".repeat(129)),
        ).forEach { assertNull(LanSyncLink.parseRequest(body(it)), it) }
    }

    @Test
    fun `reads exactly the declared body and no more than 4 KiB`() {
        val stream = { (valid + "trailing").byteInputStream() }
        assertEquals(valid, LanSyncLink.readBody(stream(), valid.length.toString())!!.decodeToString())

        assertNull(LanSyncLink.readBody(stream(), null))
        assertNull(LanSyncLink.readBody(stream(), "abc"))
        assertNull(LanSyncLink.readBody(stream(), "4097"))
        // The connection ends before the declared length
        assertNull(LanSyncLink.readBody("short".byteInputStream(), "100"))
    }

    @Test
    fun `keys are 32 random bytes as unpadded base64url`() {
        val key = LanSyncLink.newKey()
        assertEquals(43, key.length)
        assertTrue(key.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(key, LanSyncLink.newKey())
    }

    @Test
    fun `a key replaces the code only for the link it belongs to`() {
        val a = LanSyncLink.Link("pc-a", "A", "p", LanSyncLink.newKey())
        val b = LanSyncLink.Link("pc-b", "B", "p", LanSyncLink.newKey())

        assertEquals(b, LanSyncLink.findByKey(listOf(a, b), b.key))
        assertEquals(a, LanSyncLink.findByKey(listOf(a, b), " ${a.key} "))
        assertNull(LanSyncLink.findByKey(listOf(a, b), LanSyncLink.newKey()))
        assertNull(LanSyncLink.findByKey(listOf(a, b), b.key.dropLast(1)))
        assertNull(LanSyncLink.findByKey(listOf(a, b), ""))
        assertNull(LanSyncLink.findByKey(listOf(a, b), null))
        // Unlinked: the key is gone, so it's unknown
        assertNull(LanSyncLink.findByKey(listOf(a), b.key))
    }

    @Test
    fun `a conflict answer leaves the key out`() {
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }
        val conflict = LanSyncLink.LinkResponse("d", "Phone", "p", "Main")

        assertFalse("key" in json.encodeToString(conflict))
        assertTrue("\"key\":\"k\"" in json.encodeToString(conflict.copy(key = "k")))
    }

    @Test
    fun `invites round trip and a bare address is accepted`() {
        val invite = LanSyncLink.Invite("192.168.0.10", 27194, "pc-1", "p-1", "Main & Co", "042137")
        val uri = invite.toUri()

        assertTrue(uri.startsWith("tachimana://link?h=192.168.0.10%3A27194"))
        assertEquals(invite, LanSyncLink.parseInvite(uri))

        assertEquals(
            LanSyncLink.Invite("192.168.0.10", 27193, null, null, null, null),
            LanSyncLink.parseInvite("192.168.0.10"),
        )
        assertEquals(27200, LanSyncLink.parseInvite(" http://pc.local:27200/ ")!!.port)

        listOf("", "tachimana://other?h=x", "tachimana://link?d=no-host", "host:99999", "a b", "user@host").forEach {
            assertNull(LanSyncLink.parseInvite(it), it)
        }
    }
}
