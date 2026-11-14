package mihon.data.lansync

import kotlinx.serialization.Serializable

/**
 * TLP `roles/1`: who leads on a link. The contract is TachiMana's
 * `docs/protocol/capabilities/roles-1.md`. Kept free of Android so it is tested against the
 * protocol's test files.
 *
 * Each install stores the mode from its own side ("I lead / I follow / both"). On the wire it is
 * always from the message's side (`callerLeads` / `serverLeads`), so it can't be read backwards.
 */
object LanSyncRoles {

    const val CAPABILITY = "roles/1"

    // This install's side
    const val BOTH = "both"
    const val I_LEAD = "leads"
    const val I_FOLLOW = "follows"

    // The wire
    const val WIRE_BOTH = "both"
    const val CALLER_LEADS = "callerLeads"
    const val SERVER_LEADS = "serverLeads"

    const val HOW_MERGE = "merge"
    const val HOW_REPLACE = "replace"

    @Serializable
    data class Mode(val mode: String, val modeAt: Long)

    @Serializable
    data class TakeRequest(val how: String, val requestId: String)

    @Serializable
    data class TakeAccepted(val requestId: String)

    /** The stored mode, written as this install answers it (this install is the server). */
    fun asServer(local: String): String = when (local) {
        I_LEAD -> SERVER_LEADS
        I_FOLLOW -> CALLER_LEADS
        else -> WIRE_BOTH
    }

    /** A wire mode this install received as the server, in its own terms; null if unknown. */
    fun fromCaller(wire: String): String? = when (wire) {
        WIRE_BOTH -> BOTH
        CALLER_LEADS -> I_FOLLOW
        SERVER_LEADS -> I_LEAD
        else -> null
    }

    /** This install's mode, written for a message it sends (this install is the caller). */
    fun asCaller(local: String): String = when (local) {
        I_LEAD -> CALLER_LEADS
        I_FOLLOW -> SERVER_LEADS
        else -> WIRE_BOTH
    }

    /** An answer this install got as the caller, in its own terms; null if unknown. */
    fun fromServer(wire: String): String? = when (wire) {
        WIRE_BOTH -> BOTH
        CALLER_LEADS -> I_LEAD
        SERVER_LEADS -> I_FOLLOW
        else -> null
    }

    sealed interface PutResult {
        /** 200: [stored] is the new value, in this install's terms. */
        data class Stored(val stored: Mode) : PutResult

        /** 409: the stored value is newer; it goes back as it is. */
        data class Newer(val stored: Mode) : PutResult

        data object Bad : PutResult
    }

    /** `PUT /roles` against what this install holds ([stored] in its own terms). */
    fun put(stored: Mode, request: Mode): PutResult {
        val local = fromCaller(request.mode) ?: return PutResult.Bad
        return if (request.modeAt > stored.modeAt) {
            PutResult.Stored(Mode(local, request.modeAt))
        } else {
            PutResult.Newer(stored)
        }
    }

    /** Devices whose `changes/1` entries this install doesn't take: the ones that follow it. */
    fun ignoredFrom(link: LanSyncLink.Link): Set<String> =
        if (link.mode == I_LEAD) setOf(link.peerDeviceId) else emptySet()

    /** A follower's own changes are not sent; they stay in its journal until the roles change. */
    fun sendsTo(link: LanSyncLink.Link): Boolean = link.mode != I_FOLLOW

    /**
     * The `requestId`s of takes already started, newest last, so a repeat is answered 202 and not
     * run twice. Bounded: a request id older than the last [MAX_REMEMBERED] can't still be retried.
     */
    class TakeLedger(seen: List<String> = emptyList()) {
        private val ids = ArrayDeque(seen.takeLast(MAX_REMEMBERED))

        val remembered: List<String> get() = ids.toList()

        /** True the first time [requestId] is seen, and records it. */
        @Synchronized
        fun firstTime(requestId: String): Boolean {
            if (requestId in ids) return false
            ids.addLast(requestId)
            while (ids.size > MAX_REMEMBERED) ids.removeFirst()
            return true
        }

        companion object {
            const val MAX_REMEMBERED = 200
        }
    }
}
