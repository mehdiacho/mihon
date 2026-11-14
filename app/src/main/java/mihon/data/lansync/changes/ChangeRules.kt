package mihon.data.lansync.changes

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * "Who wins" from `changes/1`: whether an incoming entry replaces the value this device holds
 * for the same `(kind, target)`.
 */
object ChangeRules {

    /** What this device holds for one `(kind, target)`: the winning entry's time, id and value. */
    data class Held(val at: Long, val changeId: String, val value: JsonObject)

    sealed interface Decision {
        /** Apply [value] (the incoming one, or a merge of both) and journal the entry. */
        data class Apply(val value: JsonObject) : Decision

        /** Lost, but part of it still merges in (the earlier date added). Not journaled. */
        data class Adjust(val value: JsonObject) : Decision

        data object Ignore : Decision
    }

    /** The same target always gives the same key, whatever order its fields came in. */
    fun targetKey(target: JsonObject): String =
        target.entries.sortedBy { it.key }.joinToString("\u0000") { (k, v) -> "$k=${v.asText()}" }

    fun decide(held: Held?, incoming: Change): Decision {
        if (held == null) return Decision.Apply(incoming.value)

        if (incoming.kind == ChangeKind.HISTORY_READ) {
            return Decision.Apply(mergeHistory(held.value, incoming.value))
        }

        val wins = when {
            incoming.at > held.at -> true
            incoming.at < held.at -> false
            else -> tieWins(incoming.kind, held, incoming)
        }

        if (incoming.kind == ChangeKind.MANGA_LIBRARY && inLibrary(held.value) && inLibrary(incoming.value)) {
            val earlier = earlierDateAdded(held.value, incoming.value)
            return when {
                wins -> Decision.Apply(withDateAdded(incoming.value, earlier))
                earlier != dateAdded(held.value) -> Decision.Adjust(withDateAdded(held.value, earlier))
                else -> Decision.Ignore
            }
        }
        return if (wins) Decision.Apply(incoming.value) else Decision.Ignore
    }

    /** The value a device holds after its own [value] for a target it already held as [held]. */
    fun afterLocal(kind: String, held: JsonObject?, value: JsonObject): JsonObject = when {
        held == null -> value
        kind == ChangeKind.HISTORY_READ -> mergeHistory(held, value)
        else -> value
    }

    private fun tieWins(kind: String, held: Held, incoming: Change): Boolean {
        when (kind) {
            ChangeKind.CHAPTER_READ -> {
                val inRead = incoming.value.bool("read")
                val heldRead = held.value.bool("read")
                if (inRead != heldRead) return inRead
                val inPage = incoming.value.long("lastPageRead") ?: 0
                val heldPage = held.value.long("lastPageRead") ?: 0
                if (inPage != heldPage) return inPage > heldPage
            }
            ChangeKind.MANGA_LIBRARY -> {
                val inIn = inLibrary(incoming.value)
                if (inIn != inLibrary(held.value)) return inIn
            }
        }
        // So both sides settle on the same value
        return incoming.changeId > held.changeId
    }

    /** The later `lastRead`, and the reading time of every session added up. */
    private fun mergeHistory(held: JsonObject, incoming: JsonObject): JsonObject {
        val lastRead = maxOf(held.long("lastRead") ?: 0, incoming.long("lastRead") ?: 0)
        val timeRead = (held.long("timeRead") ?: 0) + (incoming.long("timeRead") ?: 0)
        return JsonObject(
            held + incoming + mapOf("lastRead" to JsonPrimitive(lastRead), "timeRead" to JsonPrimitive(timeRead)),
        )
    }

    private fun inLibrary(value: JsonObject) = value.bool("inLibrary")

    private fun dateAdded(value: JsonObject) = value.long("dateAdded")

    private fun earlierDateAdded(a: JsonObject, b: JsonObject): Long? =
        listOfNotNull(dateAdded(a), dateAdded(b)).minOrNull()

    private fun withDateAdded(value: JsonObject, dateAdded: Long?): JsonObject =
        if (dateAdded == null) value else JsonObject(value + ("dateAdded" to JsonPrimitive(dateAdded)))

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonElement.asText(): String = if (this is JsonPrimitive && isString) content else toString()
}
