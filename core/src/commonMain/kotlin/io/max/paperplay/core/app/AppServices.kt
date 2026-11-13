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
import kotlinx.coroutines.flow.first
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

    /** Local network sync and connect. Null (off) until [enableLanSync] is called; it does not listen until `start()`. */
    var lanSync: io.max.paperplay.core.sync.LanSync? = null
        private set

    fun enableLanSync(isDesktop: Boolean): io.max.paperplay.core.sync.LanSync = lanSync ?: io.max.paperplay.core.sync.createLanSync(
        io.max.paperplay.core.sync.LanEnv(library, platform.secrets, scope, isDesktop, platform.deviceModel, { platform.secrets.get(Keys.BOT_TOKEN) }),
    ).also { lanSync = it; bindLan(it) }

    /** Lets a paired device drive playback here, and tells linked devices what is playing. */
    private fun bindLan(lan: io.max.paperplay.core.sync.LanSync) {
        scope.launch {
            lan.messages.collect { m ->
                if (m.type != "control") return@collect
                val action = runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(m.body) as kotlinx.serialization.json.JsonObject)["action"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } }.getOrNull()
                when (action) {
                    "play" -> playback.play()
                    "pause" -> playback.pause()
                    "next" -> playback.next()
                    "previous" -> playback.previous()
                }
            }
        }
        scope.launch {
            var sent = emptyMap<String, Pair<String?, Boolean>>()
            while (true) {
                kotlinx.coroutines.delay(1_500)
                if (!playbackStarted) continue
                val np = playback.nowPlayingStatic.value
                val state = np.song?.unique_id to np.isPlaying
                val linked = lan.connected.value
                for (d in linked) if (sent[d.id] != state) {
                    runCatching { lan.sendNowPlaying(d.id, state.first, playback.nowPlaying.value.positionMs, np.isPlaying, np.song?.title, np.song?.artist) }
                }
                sent = linked.associate { it.id to state }
            }
        }
    }

    /** Sends audio files to a chat or topic as the bot. */
    val uploader: Uploader by lazy {
        Uploader(scope, { client }, library::pref, {
            library.repo.db.songsQueries.allSongs().executeAsList().mapTo(HashSet()) { it.file_name to it.size }
        }, botPool, library::setPref)
    }

    /** The main bot plus extra bots that share uploads. Each extra bot has its own TDLib session. */
    val botPool: io.max.paperplay.core.telegram.BotPool by lazy {
        io.max.paperplay.core.telegram.BotPool(
            scope, platform.secrets, io.max.paperplay.core.dupes.defaultHttpPoster(),
            { platform.secrets.get(Keys.BOT_TOKEN) }, { client },
            open = { id, token ->
                val apiId = platform.settings.get(Keys.API_ID)?.toIntOrNull()
                val apiHash = platform.settings.get(Keys.API_HASH)
                val key = platform.secrets.get(Keys.TD_KEY)
                if (apiId == null || apiHash.isNullOrEmpty() || key == null) null
                else {
                    val rt = runtime ?: TdRuntime(platform.native, scope, platform.receiveDispatcher).also { runtime = it }
                    TdClient(
                        rt,
                        TdConfig(
                            apiId, apiHash, token, "${platform.dataDir}/td_extra_$id", "${platform.dataDir}/td_extra_files_$id", key,
                            deviceModel = platform.deviceModel, appVersion = platform.appVersion,
                        ),
                    )
                }
            },
        )
    }

    /** The owner's own account (login, chats, copying). The real one replaces the stand-in. */
    val account: io.max.paperplay.core.account.AccountApi by lazy {
        io.max.paperplay.core.account.UserSession(
            scope, { runtime ?: TdRuntime(platform.native, scope, platform.receiveDispatcher).also { runtime = it } },
            platform.dataDir, platform.secrets, platform.settings, library::pref, library::setPref, platform.deviceModel, platform.appVersion,
        )
    }

    /** The saved index of the account's chats. The real one replaces the stand-in. */
    val chatIndex: io.max.paperplay.core.account.ChatIndexApi by lazy {
        io.max.paperplay.core.account.ChatIndex(
            account, io.max.paperplay.core.account.IndexStore(driver), library,
            { io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY)) }, clock,
        ).also { it.onRunFinished = { runCatching { indexCloud.save() } } }
    }

    /** Music folders on this device, with optional sending of new songs to Telegram. */
    val localFolders: LocalFolders by lazy {
        LocalFolders(
            library::pref, library::setPref, uploader,
            { io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY)) },
            {
                val id = io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY))
                id?.let { library.chat(it)?.title } ?: "Main chat"
            },
        )
    }

    /** Brings back songs the bot cannot read (posted by other bots) by posting them again where it can. */
    val recovery: io.max.paperplay.core.account.SongRecovery by lazy {
        val session = account as? io.max.paperplay.core.account.UserSession
        io.max.paperplay.core.account.SongRecovery(
            index = chatIndex, account = account, uploader = uploader,
            mainChat = { io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY)) },
            mainTitle = {
                val id = io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY))
                id?.let { library.chat(it)?.title } ?: "Main chat"
            },
            download = { post -> session?.downloadPost(post.chatId, post.messageId) },
            release = { id -> session?.releaseFile(id) },
            deletePost = { chat, id -> session?.deleteMessages(chat, listOf(id)) ?: false },
            scope = scope,
            perMinute = { (library.pref("rate_accountCopy")?.toIntOrNull() ?: 20) },
            onFinished = {
                val main = io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY))
                library.chat(main ?: 0L)?.let { scanner.rescanChat(it) }
                enricher.kick()
            },
        )
    }

    /** Refreshes imported Spotify playlists and the index in the background, and fetches songs from the index. */
    /** The owner's own Spotify login, for reading whole playlists. */
    val spotify: io.max.paperplay.core.integrations.SpotifyAccount by lazy {
        io.max.paperplay.core.integrations.SpotifyAccount(platform.secrets, io.max.paperplay.core.integrations.defaultHttp())
    }

    val playlistSync: io.max.paperplay.core.integrations.PlaylistAutoSync by lazy {
        io.max.paperplay.core.integrations.PlaylistAutoSync(
            library, io.max.paperplay.core.integrations.ImportStore(library.repo, driver), io.max.paperplay.core.integrations.defaultHttp(),
            account, chatIndex, scope, official = spotify::fetchPlaylist, canRun = platform::canRunBackground,
        )
    }

    /** What the app is doing now, for the notification: scan, then index, then playlist sync. */
    val activity: StateFlow<String?> by lazy {
        combine(scanner.progress, chatIndex.progress, playlistSync.progress) { scan, idx, sync ->
            scan?.text ?: idx?.let { "Indexing ${it.chatTitle}: ${it.found} songs" } ?: sync
        }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    /** Saves the index to Telegram (Saved Messages) and restores it after a login. */
    val indexCloud: io.max.paperplay.core.account.IndexCloud by lazy { io.max.paperplay.core.account.IndexCloud(account, chatIndex, library) }
    val resolver = TrackResolver(
        { client }, TdStreamSource.DEFAULT_WINDOW_SECONDS, { (account as? io.max.paperplay.core.account.UserSession)?.streamClient() }, { id -> botPool.clientOf(id) },
    )
    val covers = CoverService(
        platform.dataDir,
        { if (scanner.progress.value == null) client else null },
        library,
        defaultHttpFetcher(),
        scope,
        clock,
        tdFor = { s -> botOwnerOf(s.remote_id)?.let { botPool.clientOf(it) } },
    )

    /** Fills title, artist, album and cover from tags inside downloaded files. */
    val embeddedTags = EmbeddedTagFiller(platform.dataDir, library, clock)

    /** Looks songs up on MusicBrainz, Deezer and iTunes and writes the result back. */
    val enricher = MetadataEnricher(platform.dataDir, library, defaultHttpFetcher(), scope, clock)

    /** Tags songs with moods from lyrics and metadata, one at a time, once lookups are idle. */
    val moodScanner = io.max.paperplay.core.recs.MoodScanner(
        driver, library.repo, io.max.paperplay.core.integrations.defaultHttp(), scope, clock,
        allowed = { io.max.paperplay.core.recs.TasteSettings.parse(library.pref(io.max.paperplay.core.recs.TasteSettings.PREF_KEY)).lookups },
        enrichRunning = { enricher.progress.value != null },
    )

    /** Deletes duplicate posts and songs: bot, then Bot API, then the owner's account. */
    val deleteQueue: io.max.paperplay.core.dupes.DeleteQueue by lazy {
        val rules = io.max.paperplay.core.dupes.DefaultSongRules
        val caller = object : io.max.paperplay.core.dupes.TdCaller {
            override suspend fun call(request: kotlinx.serialization.json.JsonObject, timeout: kotlin.time.Duration) =
                (client ?: throw io.max.paperplay.core.telegram.TdError(500, "The bot is not connected.")).call(request, timeout)
        }
        val bot = io.max.paperplay.core.dupes.BotApi({ platform.secrets.get(Keys.BOT_TOKEN) }, io.max.paperplay.core.dupes.defaultHttpPoster())
        val acct = (account as? io.max.paperplay.core.account.UserSession)?.let { io.max.paperplay.core.account.UserAccountDeleter(it) }
            ?: object : io.max.paperplay.core.dupes.AccountDeleter {
                override val isLoggedIn = false
                override suspend fun deleteBatch(chatId: Long, ids: List<Long>) = ids
            }
        io.max.paperplay.core.dupes.DeleteQueue(
            scope, caller, bot, acct, io.max.paperplay.core.dupes.LibraryDeleteStore(library.repo, rules),
            rules, { io.max.paperplay.core.filing.parseMainChat(library.pref(io.max.paperplay.core.filing.MAIN_CHAT_KEY)) },
        )
    }

    /** Moves songs between forum topics by reposting them. */
    val topicMover: io.max.paperplay.core.filing.TopicMover by lazy {
        val botApi = io.max.paperplay.core.dupes.BotApi({ platform.secrets.get(Keys.BOT_TOKEN) }, io.max.paperplay.core.dupes.defaultHttpPoster())
        io.max.paperplay.core.filing.TopicMover(
            scope, { client }, library.repo,
            playingNow = { if (playbackStarted) playback.nowPlaying.value.song?.let { it.chat_id to it.message_id } else null },
            moveProblem = { chat -> botApi.moveProblem(chat) },
        )
    }

    /** Created on first use, so a screen that never plays does not load the audio stack. */
    private val playbackLazy = lazy { PlaybackController(platform.createPlayer(), resolver, library, scope) }
    val playback: PlaybackController get() = playbackLazy.value

    /** Network play: listening sessions with other people on the same network. Nothing listens until a session starts. */
    val netplay: io.max.paperplay.core.sync.netplay.Netplay by lazy {
        io.max.paperplay.core.sync.netplay.createNetplay(
            io.max.paperplay.core.sync.netplay.NetplayEnv(
                PlaybackNetplayPlayer({ playback }, library, resolver), scope, { lanSync?.deviceName?.value ?: platform.deviceModel },
            ),
        )
    }

    /** True once the player has been created. */
    val playbackStarted: Boolean get() = playbackLazy.isInitialized()

    /** Releases the player if one was created; the desktop engine writes its plugin cache on release. */
    fun releasePlayback() {
        if (!playbackLazy.isInitialized()) return
        runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(4_000) { playback.release() } } }
    }

    @Volatile private var client: TdClient? = null
    private var runtime: TdRuntime? = null
    private val cachePending = HashMap<String, Pair<Long, Boolean>>()
    private val clockMs = clock
    private var lastEvict = 0L
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
                playlistSync.start()
                botPool.load()
                
                platform.secrets.get("restoredExtraBots")?.let { raw ->
                    val array = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray }.getOrNull()
                    if (array != null) {
                        botPool.adopt(array.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content })
                    }
                    platform.secrets.remove("restoredExtraBots")
                }

                // Songs read through the account need its client running before they are played.
                launch {
                    if (!account.loggedIn.value) return@launch
                    val chats = driver.executeQuery(null, "SELECT DISTINCT chat_id FROM songs WHERE remote_id = ?", { cur ->
                        val ids = mutableListOf<Long>()
                        while (cur.next().value) cur.getLong(0)?.let { ids += it }
                        app.cash.sqldelight.db.QueryResult.Value(ids)
                    }, 1) { bindString(0, ACCOUNT_REMOTE) }.value
                    for (id in chats) (account as? io.max.paperplay.core.account.UserSession)?.warmUp(id)
                }
                // After the first login on this install, bring back the saved index and the user's song data once.
                launch {
                    account.loggedIn.first { it }
                    delay(5.seconds)
                    runCatching { indexCloud.restoreOnce() }
                }
                // Covers wait a while, so the first seconds after connecting stay light.
                launch {
                    delay(30.seconds)
                    covers.start()
                }
                // Catching up and the one-time check run beside the rest, never in the way of connecting.
                launch {
                    try {
                        scanner.catchUp()
                        verifyChats()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        _error.value = e.message ?: e::class.simpleName
                    }
                    enricher.kick()
                    moodScanner.start()
                    downloadPins()
                    runCatching { localFolders.syncNow() }
                }
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
     * A library restored from a backup keeps that backup's scan position, so posts it never saw stay missing. Every chat
     * is read from the start once; the chats done so far are remembered, so a closed app carries on with the rest.
     * Waits for the foreground or a charger, and for the enricher to be idle.
     */
    private suspend fun verifyChats() {
        if (library.pref("scan_verified") == "2") return
        val done = (library.pref("scan_verified_chats") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toMutableSet()
        var all = true
        scanner.retryEmptyIds = true
        try {
            for (chat in library.chatList()) {
                if (chat.id in done) continue
                while (!platform.canRunBackground() || enricher.progress.value != null) delay(60.seconds)
                if (scanner.rescanChat(chat)) {
                    done += chat.id
                    library.setPref("scan_verified_chats", done.joinToString(","))
                } else all = false
            }
        } finally {
            scanner.retryEmptyIds = false
        }
        if (all) library.setPref("scan_verified", "2")
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
            enricher.kick()
        }
    }

    /** Asks the bot for one post and says what it got, to find out why a song is missing from the library. */
    suspend fun botCheck(chatId: Long, messageId: Long): String {
        val c = client ?: return "The bot is not connected."
        return try {
            val m = c.call(request("getMessage") { put("chat_id", chatId); put("message_id", messageId) })
            if (songFromMessage(m) != null) "The bot can read this post and sees a song." else "The bot reads this post, but it does not see a song in it."
        } catch (e: TdError) {
            "The bot cannot read this post: ${e.message}"
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

    /** Pins or unpins songs; pinned songs download in full and are never evicted. */
    fun setPinned(uniqueIds: Collection<String>, pinned: Boolean) {
        library.setPinned(uniqueIds, pinned)
        if (pinned) scope.launch { downloadPins() }
    }

    /** Starts a full download for every pinned song that is not complete yet. Safe to call again. */
    suspend fun downloadPins() {
        val main = client ?: return
        for (s in library.incompletePins()) {
            runCatching {
                // A song only an extra bot can read is fetched by that bot.
                val c = botOwnerOf(s.remote_id)?.let { botPool.clientOf(it) } ?: main
                val msg = c.call(request("getMessage") { put("chat_id", s.chat_id); put("message_id", s.message_id) })
                val fileId = fileOfMessage(msg)?.long("id")?.toInt()
                if (fileId != null && fileId != 0) {
                    c.call(request("downloadFile") {
                        put("file_id", fileId); put("priority", 1); put("offset", 0); put("limit", 0); put("synchronous", false)
                    })
                }
            }
        }
    }

    private fun onFileUpdate(c: TdClient, file: kotlinx.serialization.json.JsonObject) {
        resolver.onFileUpdate(file)
        val uid = file.obj("remote")?.str("unique_id")?.takeIf { it.isNotEmpty() } ?: return
        val local = file.obj("local") ?: return
        val downloaded = local.long("downloaded_size") ?: return
        val completed = (local["is_downloading_completed"] as? JsonPrimitive)?.booleanOrNull == true
        synchronized(cachePending) { cachePending[uid] = downloaded to completed }
        if (completed) {
            local.str("path")?.takeIf { it.isNotEmpty() }?.let { path ->
                scope.launch(kotlinx.coroutines.Dispatchers.IO) { embeddedTags.apply(uid, path) }
            }
        }
        if (cacheFlushJob?.isActive != true) {
            cacheFlushJob = scope.launch {
                delay(3.seconds)
                flushCache(c)
            }
        }
    }

    private suspend fun flushCache(c: TdClient) {
        val batch = synchronized(cachePending) { HashMap(cachePending).also { cachePending.clear() } }
        if (batch.isEmpty()) return
        for ((k, entry) in batch) {
            runCatching { library.setCachedBytes(k, entry.first, notify = entry.second) }
        }
        // Looking for songs to evict reads the whole cache, so it runs at most once a minute.
        val now = clockMs()
        if (now - lastEvict < 60_000L) return
        lastEvict = now
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
