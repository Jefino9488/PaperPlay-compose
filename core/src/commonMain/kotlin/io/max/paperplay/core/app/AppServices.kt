package io.max.paperplay.core.app

import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.integrations.defaultHttpFetcher
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.newUuid
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.TdConfig
import io.max.paperplay.core.telegram.TdError
import io.max.paperplay.core.telegram.TdRuntime
import io.max.paperplay.core.telegram.request
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Clock

/**
 * The one set of app services per process: database, Telegram bot session, scanner and player.
 * No UI code. Create it once, call [connect] when credentials exist, and read the StateFlows.
 */
class AppServices(
    val platform: Platform,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val driver = platform.openDriver("${platform.dataDir}/library.sqlite")
    val db = LibraryDatabase(driver)
    val library = LibraryRepository(LibraryRepo(db, clock), clock)
    val scanner = Scanner(library, scope)
    val resolver = TrackResolver({ client })
    val covers = CoverService(platform.dataDir, { client }, library, defaultHttpFetcher(), scope, clock)

    /** Created on first use, so a screen that never plays does not load the audio stack. */
    val playback: PlaybackController by lazy { PlaybackController(platform.createPlayer(), resolver, library, scope) }

    @Volatile private var client: TdClient? = null
    private var runtime: TdRuntime? = null
    private val cachePending = MutableStateFlow<Map<String, Pair<Long, Boolean>>>(emptyMap())
    private var cacheFlushJob: Job? = null
    private val _auth = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)

    /** TDLib's `authorizationState*` name; empty before [connect]. */
    val auth: StateFlow<String> = _auth

    /** True once the bot is logged in. */
    val ready: StateFlow<Boolean> = _auth.map { it == "authorizationStateReady" }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Last login or scan failure as text; null when none. */
    val error: StateFlow<String?> = combine(_error, scanner.error) { a, b -> a ?: b }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Keeps the bot token in the secret store and the API id and hash in settings. */
    fun saveCredentials(apiId: Int, apiHash: String, botToken: String) {
        platform.settings.put(Keys.API_ID, apiId.toString())
        platform.settings.put(Keys.API_HASH, apiHash)
        platform.secrets.put(Keys.BOT_TOKEN, botToken)
    }

    val hasCredentials: Boolean
        get() = platform.settings.get(Keys.API_ID)?.toIntOrNull() != null &&
            !platform.settings.get(Keys.API_HASH).isNullOrEmpty() && !platform.secrets.get(Keys.BOT_TOKEN).isNullOrEmpty()

    /**
     * Logs the bot in and then reads the library chats. Returns at once; watch [auth], [ready] and [error].
     * Does nothing when already connected.
     */
    fun connect() {
        if (client != null) return
        val apiId = platform.settings.get(Keys.API_ID)?.toIntOrNull()
        val apiHash = platform.settings.get(Keys.API_HASH)
        val token = platform.secrets.get(Keys.BOT_TOKEN)
        if (apiId == null || apiHash.isNullOrEmpty() || token.isNullOrEmpty()) {
            _error.value = "Enter the API id, API hash and bot token first."
            return
        }
        _error.value = null
        scope.launch {
            try {
                val key = platform.secrets.get(Keys.TD_KEY) ?: (newUuid() + newUuid()).also { platform.secrets.put(Keys.TD_KEY, it) }
                val rt = runtime ?: TdRuntime(platform.native, scope, platform.receiveDispatcher).also { runtime = it }
                val c = TdClient(
                    rt,
                    TdConfig(
                        apiId, apiHash, token, "${platform.dataDir}/td", "${platform.dataDir}/td_files", key,
                        deviceModel = platform.deviceModel, appVersion = platform.appVersion,
                    ),
                )
                client = c
                scanner.attach(c)
                launch { c.auth.collect { _auth.value = it } }
                launch { c.authError.collect { _error.value = it } }
                launch {
                    c.updates.collect { u ->
                        if (u.type == "updateFile") u.obj("file")?.let { onFileUpdate(c, it) }
                    }
                }
                c.start()
                c.ready()
                covers.start()
                scanner.catchUp()
                covers.kick(retryTdMissing = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TdError) {
                _error.value = e.message
            } catch (e: Throwable) {
                _error.value = e.message ?: e::class.simpleName
            }
        }
    }

    /**
     * Stops background work and closes the library file, so a restore can replace it (Windows will not rename an open
     * file). Use it only before [connect]; once connected, [disconnect] first.
     */
    fun close() {
        scope.cancel()
        runCatching { driver.close() }
    }

    /** Adds a library chat and reads it. */
    fun addChat(id: Long, title: String) {
        library.addChat(id, title)
        scope.launch {
            library.chat(id)?.let { scanner.scanChat(it) }
            covers.kick()
        }
    }

    /** Deletes the audio TDLib downloaded for streaming. Songs download again when played. False when not connected. */
    suspend fun clearDownloads(): Boolean {
        val c = client ?: return false
        c.call(request("optimizeStorage") {
            put("size", 0)
            put("ttl", 0)
            put("count", 0)
            put("immunity_delay", 0)
            put("file_types", buildJsonArray { add(buildJsonObject { put("@type", "fileTypeAudio") }) })
            put("chat_ids", buildJsonArray { })
            put("exclude_chat_ids", buildJsonArray { })
            put("return_deleted_file_statistics", false)
            put("chat_limit", 0)
        }, 300.seconds)
        driver.execute(null, "UPDATE songs SET cached_bytes = 0 WHERE pinned = 0", 0)
        library.invalidate()
        return true
    }

    private fun onFileUpdate(c: TdClient, file: kotlinx.serialization.json.JsonObject) {
        resolver.onFileUpdate(file)
        val uid = file.obj("remote")?.str("unique_id")?.takeIf { it.isNotEmpty() } ?: return
        val local = file.obj("local") ?: return
        val downloaded = local.long("downloaded_size") ?: return
        val completed = (local["is_downloading_completed"] as? JsonPrimitive)?.booleanOrNull == true
        cachePending.update { it + (uid to (downloaded to completed)) }
        if (completed) {
            cacheFlushJob?.cancel()
            cacheFlushJob = scope.launch { flushCache(c) }
        } else if (cacheFlushJob?.isActive != true) {
            cacheFlushJob = scope.launch {
                delay(3.seconds)
                flushCache(c)
            }
        }
    }

    private suspend fun flushCache(c: TdClient) {
        val batch = cachePending.getAndUpdate { emptyMap() }
        if (batch.isEmpty()) return
        for ((k, entry) in batch) {
            runCatching { library.setCachedBytes(k, entry.first, notify = entry.second) }
        }
        val limit = library.cacheLimit()
        val rows = library.cachedSongs().filter { !it.pinned }.sortedBy { it.last_played_at ?: 0L }
        var total = rows.sumOf { it.cached_bytes }
        val protected = resolver.activeIds() + batch.keys
        for (s in rows) {
            if (total <= limit) break
            if (s.unique_id in protected) continue
            val deleted = runCatching {
                val msg = c.call(request("getMessage") { put("chat_id", s.chat_id); put("message_id", s.message_id) })
                val fileId = fileOfMessage(msg)?.long("id")?.toInt()
                if (fileId != null && fileId != 0) {
                    c.call(request("deleteFile") { put("file_id", fileId) })
                    true
                } else false
            }.getOrDefault(false)
            if (deleted) {
                library.setCachedBytes(s.unique_id, 0L, notify = true)
                total -= s.cached_bytes
            }
        }
    }

    /** Disconnects and forgets the bot's credentials, so setup shows again. The library and prefs stay. */
    suspend fun logOut() {
        disconnect()
        platform.settings.put(Keys.API_ID, "")
        platform.settings.put(Keys.API_HASH, "")
        platform.secrets.remove(Keys.BOT_TOKEN)
    }

    /** Disconnects; the session stays on disk, so [connect] logs in again without a code. */
    suspend fun disconnect() {
        val c = client ?: return
        client = null
        resolver.retain(emptyList())
        c.shutdown()
        _auth.value = ""
    }
}
