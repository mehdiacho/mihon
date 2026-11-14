package mihon.data.lansync

import android.content.Context
import android.net.Uri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.data.lansync.changes.ChangeApplier
import mihon.data.lansync.changes.ChangeJournal
import mihon.data.lansync.changes.ChangeRecorder
import mihon.data.lansync.changes.ChangesWire
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.storage.service.StorageManager
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Keeps this phone's library in step with linked computers: TLP `changes/1` (the journal, served
 * and pulled and pushed), `roles/1` (who leads, and the one-off copies) and `hello/1`.
 *
 * The server routes call in here once the link key checks out; the pulls run from
 * [LanSyncQueue.catchUpSoon] (app to the front, a peer seen on the network, at most once a minute)
 * and the pushes about two seconds after a journal write.
 */
@OptIn(FlowPreview::class)
@Inject
@SingleIn(AppScope::class)
class LanSyncSync(
    private val context: Context,
    private val preferences: LanSyncPreferences,
    private val links: LanSyncLinks,
    private val recorder: ChangeRecorder,
    private val applier: ChangeApplier,
    private val linker: Lazy<LanSyncLinker>,
    private val backupCreatorFactory: BackupCreator.Factory,
    private val backupDecoder: BackupDecoder,
    private val storageManager: StorageManager,
    private val mangas: MangaRepositoryImpl,
    private val getFavorites: GetFavorites,
) {

    /** A plain answer for the server to write. */
    class Reply(val status: Int, val body: String)

    private val journal: ChangeJournal get() = recorder.journal

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val takes = LanSyncRoles.TakeLedger(decodeList(preferences.takes.get()))

    init {
        recorder.written
            .debounce(PUSH_DELAY_MS)
            .onEach { pushAll() }
            .launchIn(scope)
    }

    // ---- hello/1 ----

    @Serializable
    data class Hello(
        val protocol: String = "1",
        val deviceId: String,
        val deviceName: String,
        val platform: String = LanSyncLink.PLATFORM_ANDROID,
        val profileId: String,
        val profileName: String,
        val app: App,
        val capabilities: List<String> = CAPABILITIES,
    )

    @Serializable
    data class App(val name: String, val version: String)

    fun hello(deviceName: String) = Hello(
        deviceId = preferences.deviceId,
        deviceName = deviceName,
        profileId = links.profileId,
        profileName = links.profileName,
        app = App("mihon", BuildConfig.VERSION_NAME),
    )

    // ---- changes/1, served ----

    fun getChanges(link: LanSyncLink.Link, query: Map<String, String>): Reply {
        val after = ChangesWire.afterOf(query["after"]).getOrElse {
            return reply(400, ChangesWire.Error("bad-request", "after must be a seq"))
        }
        return when (val served = journal.serve(after, ChangesWire.limitOf(query["limit"]), link.peerDeviceId)) {
            is ChangeJournal.Served.Page -> reply(200, served.page)
            is ChangeJournal.Served.TooOld -> reply(410, ChangesWire.TooOld(oldest = served.oldest))
        }
    }

    suspend fun postChanges(link: LanSyncLink.Link, body: String): Reply {
        val push = ChangesWire.parsePush(body) ?: return reply(400, ChangesWire.Error("bad-request", "not a push"))
        if (push.changes.size > ChangesWire.MAX_PUSH) {
            return reply(413, ChangesWire.Error("too-many", "at most ${ChangesWire.MAX_PUSH} entries"))
        }
        val result = journal.receive(push.changes, LanSyncRoles.ignoredFrom(link), applier::apply)
        if (result.applied >
            0
        ) {
            logcat(LogPriority.INFO) { "LAN sync: ${link.peerName} pushed ${result.applied} changes" }
        }
        return reply(200, result)
    }

    // ---- changes/1, pulled and pushed ----

    /** Pulls from every linked computer that has `changes/1`, then pushes to it. */
    fun syncSoon() {
        scope.launch { syncAll() }
    }

    private suspend fun syncAll() = syncMutex.withLock {
        journal.prune()
        computers().forEach { link ->
            if (!capabilitiesOf(link).contains(ChangesWire.CAPABILITY)) return@forEach
            pull(link)
            push(link)
        }
    }

    private suspend fun pushAll() = syncMutex.withLock {
        computers().forEach { link ->
            if (capabilitiesOf(link).contains(ChangesWire.CAPABILITY)) push(link)
        }
    }

    private fun computers() = links.all().filter {
        it.platform == LanSyncLink.PLATFORM_DESKTOP && it.lastAddress != null
    }

    private suspend fun pull(link: LanSyncLink.Link) {
        var pages = 0
        while (pages++ < MAX_PAGES_PER_PULL) {
            val after = journal.pullCursor(link.peerDeviceId)
            val url =
                "http://${link.lastAddress}/changes?limit=${ChangesWire.MAX_LIMIT}" +
                    (after?.let { "&after=$it" } ?: "")
            val (code, text) = call(link, Request.Builder().url(url)) ?: return
            when (code) {
                200 -> {
                    val page =
                        runCatching { ChangesWire.json.decodeFromString<ChangesWire.Page>(text) }.getOrNull() ?: return
                    // Only `link` itself is read for who follows whom, so it is fetched fresh each page
                    val current = links.find(link.peerDeviceId) ?: return
                    val result = journal.receive(page.changes, LanSyncRoles.ignoredFrom(current), applier::apply)
                    journal.setPullCursor(link.peerDeviceId, page.next)
                    if (result.applied > 0) {
                        logcat(LogPriority.INFO) { "LAN sync: applied ${result.applied} changes from ${link.peerName}" }
                    }
                    if (!page.more) return
                }
                410 -> {
                    // Entries were dropped there before this phone read them. A full copy covers
                    // what was missed; this phone doesn't start one by itself, so it carries on
                    // from what is still kept and the Sync screen's "Take everything" fills gaps.
                    val tooOld =
                        runCatching { ChangesWire.json.decodeFromString<ChangesWire.TooOld>(text) }.getOrNull()
                            ?: return
                    logcat(LogPriority.WARN) {
                        "LAN sync: ${link.peerName}'s journal no longer has this phone's cursor"
                    }
                    journal.setPullCursor(link.peerDeviceId, tooOld.oldest)
                }
                else -> {
                    logcat(LogPriority.DEBUG) { "LAN sync: changes from ${link.peerName}: HTTP $code" }
                    return
                }
            }
        }
    }

    private fun push(link: LanSyncLink.Link) {
        val current = links.find(link.peerDeviceId) ?: return
        // A follower keeps its changes; they go once the roles change
        if (!LanSyncRoles.sendsTo(current)) return
        while (true) {
            val (changes, cursor) = journal.toPush(link.peerDeviceId)
            if (cursor == (journal.pushCursor(link.peerDeviceId) ?: 0L)) return
            if (changes.isNotEmpty()) {
                val body = ChangesWire.json.encodeToString(ChangesWire.Push(preferences.deviceId, changes))
                val (code, _) = call(
                    link,
                    Request.Builder().url("http://${link.lastAddress}/changes").post(body.toRequestBody(JSON)),
                )
                    ?: return
                if (code != 200) {
                    logcat(LogPriority.DEBUG) { "LAN sync: push to ${link.peerName}: HTTP $code" }
                    return
                }
            }
            journal.setPushCursor(link.peerDeviceId, cursor)
        }
    }

    /** After a full copy, both sides start from now, so the copy isn't followed by a replay of old entries. */
    private fun startFromNow(link: LanSyncLink.Link) {
        journal.startPushingNow(link.peerDeviceId)
        var after = journal.pullCursor(link.peerDeviceId)
        repeat(MAX_PAGES_PER_PULL) {
            val url =
                "http://${link.lastAddress}/changes?limit=${ChangesWire.MAX_LIMIT}" +
                    (after?.let { "&after=$it" } ?: "")
            val (code, text) = call(link, Request.Builder().url(url)) ?: return
            val next = when (code) {
                200 -> runCatching { ChangesWire.json.decodeFromString<ChangesWire.Page>(text) }.getOrNull()
                    ?.also { if (!it.more) return journal.setPullCursor(link.peerDeviceId, it.next) }
                    ?.next
                410 -> runCatching { ChangesWire.json.decodeFromString<ChangesWire.TooOld>(text) }.getOrNull()?.oldest
                else -> null
            } ?: return
            after = next
        }
    }

    // ---- roles/1, served ----

    fun getRoles(link: LanSyncLink.Link): Reply =
        reply(200, LanSyncRoles.Mode(LanSyncRoles.asServer(link.mode), link.modeAt))

    suspend fun putRoles(link: LanSyncLink.Link, body: String): Reply {
        val request = runCatching { json.decodeFromString<LanSyncRoles.Mode>(body) }.getOrNull()
            ?: return reply(400, ChangesWire.Error("bad-request", "not a mode"))
        return when (val result = LanSyncRoles.put(LanSyncRoles.Mode(link.mode, link.modeAt), request)) {
            is LanSyncRoles.PutResult.Stored -> {
                links.setMode(link.peerDeviceId, result.stored.mode, result.stored.modeAt)
                notice(modeNotice(link.peerName, result.stored.mode))
                // Leaving "follows", what was held back goes now
                if (link.mode == LanSyncRoles.I_FOLLOW && result.stored.mode != LanSyncRoles.I_FOLLOW) syncSoon()
                reply(200, LanSyncRoles.Mode(LanSyncRoles.asServer(result.stored.mode), result.stored.modeAt))
            }
            is LanSyncRoles.PutResult.Newer ->
                reply(409, LanSyncRoles.Mode(LanSyncRoles.asServer(result.stored.mode), result.stored.modeAt))
            LanSyncRoles.PutResult.Bad -> reply(400, ChangesWire.Error("bad-request", "unknown mode"))
        }
    }

    fun postTake(link: LanSyncLink.Link, body: String): Reply {
        val take = runCatching { json.decodeFromString<LanSyncRoles.TakeRequest>(body) }.getOrNull()
            ?.takeIf { it.how == LanSyncRoles.HOW_MERGE || it.how == LanSyncRoles.HOW_REPLACE }
            ?.takeIf { it.requestId.isNotBlank() }
            ?: return reply(400, ChangesWire.Error("bad-request", "how must be merge or replace, with a requestId"))
        if (takes.firstTime(take.requestId)) {
            preferences.takes.set(json.encodeToString(takes.remembered))
            scope.launch {
                val outcome = runTake(link, take.how)
                report(link, take.requestId, outcome)
            }
        }
        return reply(202, LanSyncRoles.TakeAccepted(take.requestId))
    }

    // ---- roles/1, called from this phone ----

    /** Sets the mode on this phone and tells the computer; a newer mode there wins and is shown instead. */
    suspend fun changeMode(link: LanSyncLink.Link, mode: String): Result<String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        links.setMode(link.peerDeviceId, mode, now)
        val body = json.encodeToString(LanSyncRoles.Mode(LanSyncRoles.asCaller(mode), now))
        val (code, text) = call(
            link,
            Request.Builder().url("http://${link.lastAddress}/roles").put(body.toRequestBody(JSON)),
        )
            ?: return@withContext Result.failure(
                IOException("${link.peerName} isn't reachable; it will be told next time"),
            )
        when (code) {
            200 -> Result.success(mode)
            409 -> {
                val theirs = runCatching { json.decodeFromString<LanSyncRoles.Mode>(text) }.getOrNull()
                val local = theirs?.let { LanSyncRoles.fromServer(it.mode) }
                if (theirs != null && local != null) {
                    links.setMode(link.peerDeviceId, local, theirs.modeAt)
                    Result.success(local)
                } else {
                    Result.failure(IOException("HTTP 409"))
                }
            }
            else -> Result.failure(IOException("HTTP $code"))
        }.also { if (it.isSuccess) syncSoon() }
    }

    /** "Send everything to the computer once", and "start from this phone's library". */
    suspend fun sendEverything(link: LanSyncLink.Link, how: String): Result<Unit> = withContext(Dispatchers.IO) {
        val body = json.encodeToString(LanSyncRoles.TakeRequest(how, UUID.randomUUID().toString()))
        val (code, _) = call(
            link,
            Request.Builder().url("http://${link.lastAddress}/roles/take").post(body.toRequestBody(JSON)),
        )
            ?: return@withContext Result.failure(IOException("${link.peerName} isn't reachable"))
        if (code == 202) {
            journal.startPushingNow(link.peerDeviceId)
            Result.success(Unit)
        } else {
            Result.failure(IOException("HTTP $code"))
        }
    }

    /** "Take everything from the computer once", and "start from the computer's library". Asks first, like a take from there. */
    fun takeEverything(link: LanSyncLink.Link, how: String) {
        scope.launch {
            val outcome = runTake(link, how)
            if (!outcome.ok &&
                outcome.message != DECLINED
            ) {
                notice(outcome.message ?: "Couldn't copy from ${link.peerName}")
            }
        }
    }

    // ---- The take itself ----

    /** A take waiting for the person: how many manga each side has, and what will happen. */
    data class PendingTake(
        val peerName: String,
        val how: String,
        val theirs: Int,
        val mine: Int,
        internal val answer: CompletableDeferred<Boolean>,
    )

    private val _pendingTake = MutableStateFlow<PendingTake?>(null)
    val pendingTake: StateFlow<PendingTake?> = _pendingTake.asStateFlow()

    fun answerTake(accept: Boolean) {
        _pendingTake.value?.answer?.complete(accept)
        _pendingTake.value = null
    }

    private data class Outcome(val ok: Boolean, val message: String? = null)

    private val takeMutex = Mutex()

    /** Copies [link]'s library through `pull/1` once the person says yes. */
    private suspend fun runTake(link: LanSyncLink.Link, how: String): Outcome = takeMutex.withLock {
        try {
            val file = linker.value.downloadBackup(link)
            val theirs = backupDecoder.decodeMetadata(Uri.fromFile(file)).entryCount
            val mine = getFavorites.await().size

            val answer = CompletableDeferred<Boolean>()
            _pendingTake.value = PendingTake(link.peerName, how, theirs, mine, answer)
            notice(takeNotice(link.peerName))
            if (!answer.await()) return@withLock Outcome(false, DECLINED)

            if (how == LanSyncRoles.HOW_REPLACE) {
                saveBackupBeforeReplace(link)
                // Through the plain repository: a replace isn't the person removing each manga, so
                // it isn't journaled and nothing is sent back
                val favorites = getFavorites.await()
                mangas.updateAll(favorites.map { MangaUpdate(it.id) { favoriteAt = null } })
            }
            val workManager = WorkManager.getInstance(context)
            BackupRestoreWorker.start(workManager, Uri.fromFile(file), RestoreOptions())
            if (!awaitRestore(workManager)) return@withLock Outcome(false, "The restore didn't finish")
            startFromNow(link)
            Outcome(true)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "LAN sync: take from ${link.peerName} failed" }
            _pendingTake.value = null
            Outcome(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** "before-replace-<computer>-<date>.tachibk" next to the automatic backups, kept until the person deletes it. */
    private suspend fun saveBackupBeforeReplace(link: LanSyncLink.Link) {
        val dir = storageManager.getAutomaticBackupsDirectory() ?: throw IOException("No backup folder set")
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date())
        val safeName = link.peerName.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
        val file =
            dir.createFile("before-replace-$safeName-$stamp.tachibk")
                ?: throw IOException("Couldn't create the backup file")
        backupCreatorFactory.create(isAutoBackup = false).backup(file.uri, BackupOptions())
    }

    private suspend fun awaitRestore(workManager: WorkManager): Boolean {
        val deadline = System.currentTimeMillis() + RESTORE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            val infos = runCatching { workManager.getWorkInfosForUniqueWork(RESTORE_WORK).get() }.getOrNull().orEmpty()
            val info = infos.lastOrNull() ?: continue
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> return true
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> return false
                else -> Unit
            }
        }
        return false
    }

    // ---- This phone's outbox (done/1's GET /outbox), for take reports ----

    @Serializable
    data class OutboxEvent(
        val eventId: String,
        val kind: String,
        val to: String,
        val requestId: String? = null,
        val ok: Boolean? = null,
        val message: String? = null,
        val at: Long,
    )

    @Serializable
    data class Outbox(val events: List<OutboxEvent>)

    private fun report(link: LanSyncLink.Link, requestId: String, outcome: Outcome) = synchronized(this) {
        val event = OutboxEvent(
            eventId = UUID.randomUUID().toString(),
            kind = "take",
            to = link.peerDeviceId,
            requestId = requestId,
            ok = outcome.ok,
            message = outcome.message,
            at = System.currentTimeMillis(),
        )
        val events = (outboxEvents() + event).takeLast(MAX_OUTBOX)
        preferences.outbox.set(json.encodeToString(events))
    }

    fun outbox(link: LanSyncLink.Link, since: String?): Reply {
        val mine = outboxEvents().filter { it.to == link.peerDeviceId }
        val index = since?.let { id -> mine.indexOfLast { it.eventId == id } } ?: -1
        return reply(200, Outbox(mine.drop(index + 1)))
    }

    private fun outboxEvents(): List<OutboxEvent> =
        preferences.outbox.get().takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<OutboxEvent>>(it) }.getOrNull() }
            .orEmpty()

    // ---- Shared ----

    private val capabilities = HashMap<String, Pair<Long, Set<String>>>()

    /** What a computer lists in `/hello`; a computer without one has none of the new capabilities. Kept for a minute. */
    fun capabilitiesOf(link: LanSyncLink.Link): Set<String> {
        val now = System.currentTimeMillis()
        synchronized(capabilities) {
            capabilities[link.peerDeviceId]?.takeIf { now - it.first < 60_000 }?.let { return it.second }
        }
        val (code, text) = call(link, Request.Builder().url("http://${link.lastAddress}/hello")) ?: return emptySet()
        val listed = if (code == 200) {
            runCatching { json.decodeFromString<HelloCapabilities>(text).capabilities.toSet() }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        synchronized(capabilities) { capabilities[link.peerDeviceId] = now to listed }
        return listed
    }

    @Serializable
    private data class HelloCapabilities(val capabilities: List<String> = emptyList())

    private fun call(link: LanSyncLink.Link, builder: Request.Builder): Pair<Int, String>? = try {
        client.newCall(builder.header(LanSyncLink.LINK_KEY_HEADER, link.key).build()).execute().use {
            it.code to it.body.string()
        }
    } catch (e: Exception) {
        logcat(LogPriority.DEBUG) { "LAN sync: ${link.peerName} unreachable: ${e.message}" }
        null
    }

    private inline fun <reified T> reply(status: Int, body: T) = Reply(status, json.encodeToString(body))

    private suspend fun notice(text: String) = withContext(Dispatchers.Main) { context.toast(text) }

    private fun modeNotice(peer: String, mode: String) = when (mode) {
        LanSyncRoles.I_LEAD -> "$peer now follows this phone"
        LanSyncRoles.I_FOLLOW -> "This phone now follows $peer"
        else -> "Sync with $peer now goes both ways"
    }

    private fun takeNotice(peer: String) = "$peer wants to copy its library to this phone. Open Mihon to answer."

    private fun decodeList(raw: String): List<String> =
        raw.takeIf {
            it.isNotBlank()
        }?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()

    companion object {
        const val DECLINED = "Declined"

        /** What `/hello` lists. `queue/1`, `done/1` and `file/1` were served before `/hello` existed. */
        val CAPABILITIES = listOf(
            "link/2",
            "pull/1",
            "queue/1",
            "done/1",
            "file/1",
            ChangesWire.CAPABILITY,
            LanSyncRoles.CAPABILITY,
        )

        private const val PUSH_DELAY_MS = 2_000L
        private const val MAX_PAGES_PER_PULL = 50
        private const val MAX_OUTBOX = 100
        private const val RESTORE_TIMEOUT_MS = 30L * 60 * 1000
        private const val RESTORE_WORK = "BackupRestore"
        private val JSON = "application/json".toMediaType()
    }
}
