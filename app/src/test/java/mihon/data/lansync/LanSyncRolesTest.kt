package mihon.data.lansync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** `roles/1` against TachiMana's test files. Synthetic data only. */
class LanSyncRolesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun vector(name: String): JsonObject =
        json.parseToJsonElement(File(javaClass.classLoader!!.getResource("tlp/roles-1/$name")!!.toURI()).readText())
            .jsonObject

    /** The vectors' `stored` is the server's state on the wire, as the server would answer GET /roles. */
    private fun putVector(name: String): Pair<Int, LanSyncRoles.Mode> {
        val v = vector(name)
        val storedWire = json.decodeFromJsonElement<LanSyncRoles.Mode>(v["stored"]!!)
        val stored = LanSyncRoles.Mode(LanSyncRoles.fromCaller(storedWire.mode)!!, storedWire.modeAt)
        // Read as the server: callerLeads stored means this side follows
        val request = json.decodeFromJsonElement<LanSyncRoles.Mode>(v["request"]!!.jsonObject["body"]!!)
        return when (val result = LanSyncRoles.put(stored, request)) {
            is LanSyncRoles.PutResult.Stored -> 200 to result.stored
            is LanSyncRoles.PutResult.Newer -> 409 to result.stored
            LanSyncRoles.PutResult.Bad -> 400 to stored
        }.let { (status, mode) -> status to LanSyncRoles.Mode(LanSyncRoles.asServer(mode.mode), mode.modeAt) }
    }

    @Test
    fun `a newer mode is stored`() {
        val expect = vector("01-newer-mode-stored.json")["expect"]!!.jsonObject
        val (status, body) = putVector("01-newer-mode-stored.json")
        assertEquals(expect["status"]!!.jsonPrimitive.int, status)
        assertEquals(json.decodeFromJsonElement<LanSyncRoles.Mode>(expect["body"]!!), body)
    }

    @Test
    fun `an older mode is refused with the stored one`() {
        val expect = vector("02-older-mode-refused.json")["expect"]!!.jsonObject
        val (status, body) = putVector("02-older-mode-refused.json")
        assertEquals(expect["status"]!!.jsonPrimitive.int, status)
        assertEquals(json.decodeFromJsonElement<LanSyncRoles.Mode>(expect["body"]!!), body)
    }

    @Test
    fun `a repeated take is accepted but runs once`() {
        val v = vector("03-take-repeat.json")
        val ledger = LanSyncRoles.TakeLedger()
        var runs = 0
        val statuses = v["requests"]!!.jsonArray.map {
            val take = json.decodeFromJsonElement<LanSyncRoles.TakeRequest>(it.jsonObject["body"]!!)
            if (ledger.firstTime(take.requestId)) runs++
            202
        }
        val expect = v["expect"]!!.jsonObject
        assertEquals(expect["statuses"]!!.jsonArray.map { it.jsonPrimitive.int }, statuses)
        assertEquals(expect["runs"]!!.jsonPrimitive.int, runs)
    }

    @Test
    fun `wire names read from each side`() {
        // This phone leads; as the server it says serverLeads, as the caller callerLeads
        assertEquals(LanSyncRoles.SERVER_LEADS, LanSyncRoles.asServer(LanSyncRoles.I_LEAD))
        assertEquals(LanSyncRoles.CALLER_LEADS, LanSyncRoles.asCaller(LanSyncRoles.I_LEAD))
        assertEquals(LanSyncRoles.I_FOLLOW, LanSyncRoles.fromCaller(LanSyncRoles.CALLER_LEADS))
        assertEquals(LanSyncRoles.I_FOLLOW, LanSyncRoles.fromServer(LanSyncRoles.SERVER_LEADS))
        val link = LanSyncLink.Link("pc", "PC", "p", "k", mode = LanSyncRoles.I_LEAD)
        assertEquals(setOf("pc"), LanSyncRoles.ignoredFrom(link))
        assertEquals(false, LanSyncRoles.sendsTo(link.copy(mode = LanSyncRoles.I_FOLLOW)))
    }
}
