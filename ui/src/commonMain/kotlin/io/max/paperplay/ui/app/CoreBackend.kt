package io.max.paperplay.ui.app

import io.max.paperplay.core.app.AppServices
import io.max.paperplay.core.app.createBackup
import io.max.paperplay.core.app.NowPlaying
import io.max.paperplay.core.app.Repeat
import io.max.paperplay.core.library.LibraryChat
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.recs.DailyMix
import io.max.paperplay.core.recs.DailyMixes
import io.max.paperplay.core.recs.HomeMix
import io.max.paperplay.core.recs.HomeShelves
import io.max.paperplay.core.recs.TasteList
import io.max.paperplay.core.recs.TasteSettings
import io.max.paperplay.core.recs.WeatherKind
import io.max.paperplay.core.recs.WeatherPlace
import io.max.paperplay.core.recs.WeatherService
import io.max.paperplay.core.integrations.HttpFetcher
import io.max.paperplay.core.integrations.ListenBrainz
import kotlinx.coroutines.flow.MutableStateFlow
import io.max.paperplay.core.recs.ShelfKind
import io.max.paperplay.core.filing.MAIN_CHAT_KEY
import io.max.paperplay.core.filing.parseMainChat
import io.max.paperplay.ui.nowMoment
import io.max.paperplay.ui.state.CollectionUi
import io.max.paperplay.ui.state.HomeUi
import io.max.paperplay.ui.state.ShelfKindUi
import io.max.paperplay.ui.state.ShelfUi
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import io.max.paperplay.ui.state.GroupUi
import io.max.paperplay.ui.state.LibraryUi
import io.max.paperplay.ui.state.Load
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.state.PositionUi
import io.max.paperplay.ui.state.RepeatUi
import io.max.paperplay.ui.state.SongUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

data class LogLine(val at: Long, val source: String, val message: String, val error: Boolean = false)

/** What the connection screen shows. */
data class ConnectionUi(
    val hasCredentials: Boolean = false,
    /** TDLib's authorization state name, empty before connecting. */
    val state: String = "",
    val ready: Boolean = false,
    val error: String? = null,
    val chats: List<LibraryChat> = emptyList(),
)

/** What a running scan says; kept out of [ConnectionUi] so a scan tick does not recompose whole screens. */
data class ScanUi(val text: String, val fraction: Double?)

/**
 * Maps the app services onto the plain state the screens render and the actions they call.
 * No Telegram or database types leave this class except [LibraryChat].
 */
class CoreBackend(val services: AppServices) {
    private val scope = services.scope
    private val repo = services.library

    @Volatile private var byId: Map<String, Song> = emptyMap()
    @Volatile private var byGroupKey: Map<String, Song> = emptyMap()

    fun ensureCover(key: String) {
        val song = byId[key] ?: byGroupKey[key] ?: return
        services.covers.prioritize(listOf(song))
    }

    val library: StateFlow<LibraryUi> = combine(
        repo.allSongs().map { songs ->
            byId = songs.associateBy { it.unique_id }
            val groupsByKey = HashMap<String, Song>(songs.size)
            for (s in songs) {
                for (k in listOf(s.album, s.artist, s.title)) {
                    if (k.isBlank()) continue
                    val prev = groupsByKey[k]
                    if (prev == null || (prev.cover_path != null && s.cover_path == null)) {
                        groupsByKey[k] = s
                    }
                }
            }
            byGroupKey = groupsByKey
            LibraryUi(
                songs = Load.Ready(songs.map(Song::toUi)),
                albums = groups(songs, { it.album }) { name, list -> GroupUi(name, name, albumSubtitle(list), covers(list)) },
                artists = groups(songs, { it.artist }) { name, list -> GroupUi(name, name, "${list.size} songs", covers(list)) },
            )
        },
        repo.playlistSummaries().map { list ->
            list.map { p -> GroupUi(p.id.toString(), p.name, "${p.count} songs", p.covers) }
        },
    ) { lib, playlists -> lib.copy(playlists = playlists) }
        .catch { emit(LibraryUi(songs = Load.Error(it.message ?: "The library could not be read."))) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, LibraryUi())

    val favorites: StateFlow<List<SongUi>> = repo.favorites()
        .onEach { if (it.isNotEmpty()) services.covers.prioritize(it.take(20)) }
        .map { it.map(Song::toUi) }
        .flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    val recents: StateFlow<List<SongUi>> = repo.recents(30)
        .onEach { if (it.isNotEmpty()) services.covers.prioritize(it.take(20)) }
        .map { it.map(Song::toUi) }
        .flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    private class HomeInput(
        val songs: List<Song>, val recent: List<Song>, val favorites: List<Song>, val reserved: Int,
        val taste: TasteSettings, val weather: WeatherKind?, val busy: Boolean,
    )

    /** What mixes may learn from. Saved with the other preferences. */
    val taste = MutableStateFlow(TasteSettings.parse(repo.pref(TasteSettings.PREF_KEY)))

    fun updateTaste(change: (TasteSettings) -> TasteSettings) {
        val next = change(taste.value).copy(at = nowMoment().epochMs)
        taste.value = next
        repo.setPref(TasteSettings.PREF_KEY, next.encode())
    }

    /** Forgets plays, skips and "doesn't fit" votes, and ignores older counts synced from elsewhere. */
    fun clearLearned() {
        scope.launch(Dispatchers.Default) {
            repo.repo.clearLearnedRows()
            updateTaste { it.copy(resetAt = nowMoment().epochMs) }
            repo.invalidate()
        }
    }

    /** Playlists with their uuid, for choosing what mixes learn from. */
    fun playlistKeys(): List<Pair<String, String>> =
        repo.repo.db.playlistsQueries.livePlaylists().executeAsList().map { TasteSettings.playlistKey(it.uuid) to it.name }

    private fun tasteLists(favorites: List<Song>, t: TasteSettings): List<TasteList> = buildList {
        if (t.learnsFrom(TasteSettings.FAVORITES_KEY)) add(TasteList("Favorites", favorites.map { it.unique_id }.toSet()))
        for (p in repo.repo.db.playlistsQueries.livePlaylists().executeAsList()) {
            if (!t.learnsFrom(TasteSettings.playlistKey(p.uuid))) continue
            add(TasteList(p.name, repo.repo.db.playlistsQueries.playlistSongs(p.id).executeAsList().map { it.unique_id }.toSet()))
        }
    }

    val http: HttpFetcher = io.max.paperplay.core.integrations.defaultHttpFetcher()
    private val weatherService = WeatherService(http)

    val weatherPlace = MutableStateFlow(
        repo.pref("weather_city")?.takeIf { it.isNotEmpty() }?.let { name ->
            val lat = repo.pref("weather_lat")?.toDoubleOrNull()
            val lon = repo.pref("weather_lon")?.toDoubleOrNull()
            if (lat != null && lon != null) WeatherPlace(name, lat, lon) else null
        },
    )
    val weather = MutableStateFlow<WeatherKind?>(null)

    suspend fun searchPlaces(query: String): List<WeatherPlace> =
        runCatching { weatherService.searchPlaces(query) }.getOrDefault(emptyList())

    /** Chooses the city to get the weather for, or turns weather off with null. */
    fun setPlace(p: WeatherPlace?) {
        weatherPlace.value = p
        weather.value = null
        repo.setPref("weather_city", p?.name.orEmpty())
        repo.setPref("weather_lat", p?.latitude?.toString().orEmpty())
        repo.setPref("weather_lon", p?.longitude?.toString().orEmpty())
        refreshWeather()
    }

    fun refreshWeather() {
        val p = weatherPlace.value ?: return
        scope.launch { weather.value = runCatching { weatherService.kindAt(p) }.getOrNull() }
    }

    init { refreshWeather() }

    /** tg://privatepost link of the song's channel post, or null for chats that are not channels or supergroups. */
    /** Queues the song for another online lookup; says what happened. */
    fun findMetadata(song: SongUi): String {
        val s = byId[song.id] ?: return "Song not found"
        if (s.user_edited) return "Edited songs are left alone"
        return if (services.enricher.retrySongs(listOf(s)) > 0) "Looking up song info" else "Song not found"
    }

    fun songRow(id: String): Song? = byId[id]

    fun telegramLink(song: SongUi): String? {
        val s = byId[song.id] ?: return null
        if (s.chat_id > -1_000_000_000_000L) return null
        val channel = -s.chat_id - 1_000_000_000_000L
        val post = s.message_id shr 20
        if (post <= 0) return null
        return "tg://privatepost?channel=$channel&post=$post"
    }

    fun playNext(song: SongUi) {
        byId[song.id]?.let { player.playNext(it) }
    }

    /** Lyrics for a song: the saved ones, else looked up on LRCLIB (unless lookups are off) and saved. Null when none. */
    suspend fun lyricsFor(songId: String): String? {
        val song = byId[songId] ?: return null
        song.lyrics?.takeIf { it.isNotBlank() }?.let { return it }
        if (!taste.value.lookups) return null
        val found = runCatching {
            io.max.paperplay.core.integrations.fetchLyrics(http, song.title, song.artist, song.album, song.duration_sec.toInt())
        }.getOrNull() ?: return null
        runCatching {
            services.driver.execute(null, "UPDATE songs SET lyrics = ? WHERE unique_id = ?", 2) { bindString(0, found); bindString(1, songId) }
        }
        return found
    }

    private var lyricsJob: kotlinx.coroutines.Job? = null
    private var lyricsTask = 0L
    val lyricsRunning = MutableStateFlow(false)

    /**
     * Background run: looks up lyrics for every song without them, two songs at a time, each against all lyrics
     * sources at once (rate-limited per source). Songs nobody had lyrics for are remembered for two weeks
     * (pref "lyrics_miss") and skipped; a network failure is retried a couple of times and is not remembered.
     * Progress shows in the task centre. Calling it while running does nothing.
     */
    fun startLyricsRun() {
        if (lyricsJob?.isActive == true) return
        val now0 = nowMoment().epochMs
        val ttl = 14 * 86_400_000L
        val misses = HashMap<String, Long>()
        repo.pref("lyrics_miss")?.lines()?.forEach { l ->
            val p = l.split('\t')
            val at = p.getOrNull(1)?.toLongOrNull() ?: return@forEach
            if (now0 - at < ttl) misses[p[0]] = at
        }
        val todo = byId.values.filter { it.lyrics.isNullOrBlank() && !it.instrumental && misses[it.unique_id] == null }.map { it.unique_id }
        if (todo.isEmpty()) return
        val id = tasks.start(io.max.paperplay.core.app.TaskKind.Info, "Lyrics", todo.size, "Starting", onStop = { stopLyricsRun() })
        lyricsTask = id
        lyricsRunning.value = true
        lyricsJob = scope.launch {
            var found = 0
            var missed = 0
            var done = 0
            val lock = kotlinx.coroutines.sync.Mutex()
            val queue = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            todo.forEach { queue.trySend(it) }
            queue.close()
            val writes = ArrayList<Pair<String, String>>()
            var lastTask = 0L
            // Called under [lock]: all collected lyrics go in one transaction, then the lists refresh once.
            fun flushLyrics() {
                if (writes.isEmpty()) return
                val batch = writes.toList()
                writes.clear()
                runCatching {
                    repo.batch {
                        for ((u, text) in batch) {
                            services.driver.execute(null, "UPDATE songs SET lyrics = ? WHERE unique_id = ?", 2) { bindString(0, text); bindString(1, u) }
                        }
                    }
                }
            }
            fun saveMisses() = repo.setPref("lyrics_miss", misses.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            try {
                kotlinx.coroutines.coroutineScope {
                    repeat(2) {
                        launch {
                            for (uid in queue) {
                                val song = byId[uid] ?: continue
                                var result: String? = null
                                var failed = false
                                var attempt = 0
                                while (true) {
                                    try {
                                        result = io.max.paperplay.core.integrations.fetchLyrics(http, song.title, song.artist, song.album, song.duration_sec.toInt())
                                        break
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        if (++attempt > 2) { failed = true; break }
                                        kotlinx.coroutines.delay(2_000L shl attempt)
                                    }
                                }
                                lock.withLock {
                                    if (result != null) {
                                        writes += uid to result
                                        if (writes.size >= 20) flushLyrics()
                                    }
                                    if (result != null) { found++; misses.remove(uid) }
                                    else {
                                        missed++
                                        if (!failed) misses[uid] = nowMoment().epochMs
                                    }
                                    done++
                                    val t = nowMoment().epochMs
                                    if (t - lastTask >= 1_000L) { lastTask = t; tasks.update(id, done = done, detail = song.title) }
                                    if (done % 25 == 0) saveMisses()
                                }
                            }
                        }
                    }
                }
                lock.withLock { flushLyrics() }
                tasks.update(id, done = todo.size)
                tasks.finish(id, io.max.paperplay.core.app.TaskState.Done, "$found found, $missed without lyrics")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } finally {
                runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { lock.withLock { flushLyrics() } } }
                runCatching { saveMisses() }
                lyricsRunning.value = false
            }
        }
    }

    fun stopLyricsRun() {
        lyricsJob?.cancel()
        lyricsRunning.value = false
        tasks.running.value.firstOrNull { it.id == lyricsTask }?.let { tasks.stop(it.id) }
    }

    fun seekToMs(ms: Long) = player.seek(ms)

    /** Listening totals for the last [days] days (0 for all time): per song and per day. */
    fun listenStats(days: Int): Pair<List<io.max.paperplay.core.library.ListenTotal>, Map<String, Long>> {
        val from = if (days <= 0) "0000-00-00" else io.max.paperplay.core.app.dayOf(nowMoment().epochMs - (days - 1) * 86_400_000L)
        return repo.repo.listenTotals(from) to repo.repo.listenPerDay(from)
    }

    val account get() = services.account
    val chatIndex get() = services.chatIndex

    /** Your own groups and channels. The last list is kept, so the page can show it at once and refresh behind it. */
    val myChats = MutableStateFlow<List<io.max.paperplay.core.account.ChatInfo>?>(
        repo.pref("my_chats")?.lines()?.mapNotNull { l ->
            val p = l.split('\t', limit = 3)
            val id = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            io.max.paperplay.core.account.ChatInfo(id, p.getOrNull(2).orEmpty(), p.getOrNull(1) == "1")
        }?.takeIf { it.isNotEmpty() },
    )

    suspend fun refreshChats() {
        val fresh = account.groupsAndChannels()
        if (fresh.isEmpty()) return
        myChats.value = fresh
        repo.setPref("my_chats", fresh.joinToString("\n") { "${it.id}\t${if (it.isChannel) 1 else 0}\t${it.title.replace('\t', ' ').replace('\n', ' ')}" })
    }

    /** Sleep timer: pauses at [sleepAt] (epoch ms). Null when none is set. */
    val sleepAt = MutableStateFlow<Long?>(null)
    private var sleepJob: kotlinx.coroutines.Job? = null

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) { sleepAt.value = null; return }
        val end = nowMoment().epochMs + minutes * 60_000L
        sleepAt.value = end
        sleepJob = scope.launch {
            kotlinx.coroutines.delay(minutes * 60_000L)
            player.pause()
            sleepAt.value = null
        }
    }

    /** Deletes streamed audio TDLib keeps on disk. Reports what happened as text. */
    fun clearCache(done: (String) -> Unit) {
        scope.launch {
            val msg = try {
                if (services.clearDownloads()) "Cache cleared." else "Connect first, then clear the cache."
            } catch (e: Exception) {
                e.message ?: "The cache could not be cleared."
            }
            done(msg)
        }
    }

    /** What a running backup is doing and how far along (null fraction when unknown); null when none runs. */
    val backupProgress = MutableStateFlow<Pair<String, Double?>?>(null)

    /**
     * Builds a full backup (Flutter's format), asks the platform where to put it, and copies it there. With a
     * [password] the keys and account session are included, sealed with it. Returns a message to show.
     */
    suspend fun saveBackup(password: String?, suggestedName: String): String {
        val pick = BackupFiles.pickDestination ?: return "Saving a backup is not supported here."
        if (backupProgress.value != null) return "A backup is already running."
        val tmp = services.platform.dataDir.trimEnd('/', '\\') + "/backup-tmp.zip"
        backupProgress.value = "Starting" to 0.0
        return try {
            val summary = services.createBackup(tmp, password) { stage, fraction -> backupProgress.value = stage to fraction }
            backupProgress.value = "Choose where to save it, then it is copied" to null
            val dest = kotlinx.coroutines.suspendCancellableCoroutine<BackupDestination?> { c ->
                pick(suggestedName) { if (c.isActive) c.resumeWith(Result.success(it)) }
            }
            if (dest == null) "Backup not saved."
            else if (withContext(Dispatchers.Default) { dest.save(tmp) }) {
                "Backup saved: ${summary.songs} songs" + if (password != null && !summary.credentials) ". No bot keys to add yet." else "."
            } else "Backup not saved."
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            "Couldn't save the backup: ${e.message ?: e::class.simpleName}"
        } finally {
            backupProgress.value = null
            io.max.paperplay.core.backup.Fs.deleteRecursively(tmp)
        }
    }

    // Equalizer: five bands in dB, saved as a JSON list like the Flutter app does.
    val eqOn = MutableStateFlow(repo.pref("eq_on") == "true")
    val eqGains = MutableStateFlow(
        repo.pref("eq_gains")?.trim('[', ']', ' ')?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == 5 } ?: List(5) { 0f },
    )

    fun setEqualizer(on: Boolean = eqOn.value, gains: List<Float> = eqGains.value) {
        eqOn.value = on
        eqGains.value = gains
        repo.setPref("eq_on", "$on")
        repo.setPref("eq_gains", gains.joinToString(",", "[", "]"))
        player.setEqualizer(if (on) gains else null)
    }

    // ListenBrainz: the token lives in the secret store, never in a plain preference.
    private val listenBrainz = ListenBrainz(http)
    val lbUser = MutableStateFlow(
        if (services.platform.secrets.get(io.max.paperplay.core.backup.SecretKeys.LISTENBRAINZ_TOKEN) == null) null
        else repo.pref("listenbrainz_user")?.takeIf { it.isNotEmpty() } ?: "your account",
    )


    /** Checks the token with ListenBrainz and keeps it. Returns a problem as text, or null when it worked. */
    suspend fun connectListenBrainz(token: String): String? {
        val t = token.trim()
        if (t.isEmpty()) return "Paste your ListenBrainz user token."
        val user = try { listenBrainz.validate(t) } catch (e: Exception) { return "ListenBrainz could not be reached." }
            ?: return "ListenBrainz does not accept that token."
        services.platform.secrets.put(io.max.paperplay.core.backup.SecretKeys.LISTENBRAINZ_TOKEN, t)
        repo.setPref("listenbrainz_user", user)
        repo.setPref("listenbrainz_on", "true")
        lbUser.value = user
        return null
    }

    fun disconnectListenBrainz() {
        services.platform.secrets.remove(io.max.paperplay.core.backup.SecretKeys.LISTENBRAINZ_TOKEN)
        repo.setPref("listenbrainz_user", "")
        repo.setPref("listenbrainz_on", "false")
        lbUser.value = null
    }


    /** What the app has been doing, newest last. Kept in memory; bot tokens are blanked out. */
    val log = MutableStateFlow<List<LogLine>>(emptyList())
    private var lastScanLog = 0L

    /** Running background jobs and their history; features call tasks.start/update/finish. */
    val tasks = io.max.paperplay.core.app.TaskQueue(scope).also { q -> scope.launch(Dispatchers.Default) { q.watchServices(services) } }

    fun addLog(source: String, message: String, error: Boolean = false) {
        val clean = message.replace(Regex("\\b\\d{5,}:[A-Za-z0-9_-]{20,}\\b"), "<bot token>")
        log.value = (log.value + LogLine(nowMoment().epochMs, source, clean, error)).takeLast(1000)
    }

    fun clearLog() { log.value = emptyList() }

    init {
        scope.launch { services.auth.collect { if (it.isNotEmpty()) addLog("Telegram", it.removePrefix("authorizationState")) } }
        scope.launch { services.scanner.report.collect { if (it != null) addLog("Scan", it) } }
        scope.launch { services.error.collect { if (it != null) addLog("Telegram", it, error = true) } }
        scope.launch {
            services.scanner.progress.collect { p ->
                val t = nowMoment().epochMs
                if (p != null && t - lastScanLog > 10_000) { lastScanLog = t; addLog("Scan", p.text) }
            }
        }
    }

    private class HomePlanState(val plan: HomeMix? = null, val mixes: List<DailyMix> = emptyList())

    /**
     * The Home page plan: quick tiles, shelves and daily mixes. Replanned when the library, the favorites or the
     * day changes, not on every play or cover update, so rows stay put while cover art pops in live.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Volatile private var homePlanned = false
    private fun sizeBucket(n: Int) = if (n < 200) n else 200 + n / 100
    private val homePlan: Flow<HomePlanState> = combine(
        repo.allSongs(), repo.recents(30), repo.favorites(), library,
        combine(taste, weather, services.scanner.progress.map { it != null }, services.covers.queued.map { it > 0 }) { t, w, scanning, covering ->
            Triple(t, w, scanning || covering)
        },
    ) { songs, recent, fav, lib, tw ->
        HomeInput(songs, recent, fav, minOf(lib.playlists.size, 3) + if (fav.isEmpty()) 0 else 1, tw.first, tw.second, tw.third)
    }
        .distinctUntilChangedBy {
            "${it.favorites.size}:${it.reserved}:${it.taste.at}:${it.taste.showMixes}:${it.weather}:${it.busy}:${sizeBucket(it.songs.size)}:${nowMoment().dayKey}"
        }
        .transformLatest { input ->
            if (input.songs.isEmpty()) { emit(HomePlanState()); return@transformLatest }
            // Replanning waits two quiet seconds (a newer input cancels this one); the first plan is not delayed.
            if (homePlanned) delay(2_000L)
            homePlanned = true
            val now = nowMoment()
            val plan = HomeShelves.plan(input.songs, input.recent, input.favorites, now, tileCount = 6 - input.reserved)
            val leadSongs = buildList {
                plan.tiles.forEach { add(it.cover); addAll(it.songs.take(1)) }
                plan.shelves.forEach { s ->
                    addAll(s.songs.take(8))
                    s.groups.take(8).forEach { add(it.cover) }
                }
            }.distinctBy { it.unique_id }
            if (leadSongs.isNotEmpty()) services.covers.prioritize(leadSongs)
            // Tiles and shelves show at once; the slower daily mixes join when they are ready.
            if (!input.taste.showMixes) { emit(HomePlanState(plan = plan)); return@transformLatest }
            // Saved mixes show at once, even from an older day or library; fresh ones replace them when computed.
            val mixKey = "${now.dayKey}:${sizeBucket(input.songs.size)}:${input.favorites.size}:${input.taste.at}:${input.weather}"
            val cached = runCatching { DailyMixCache.decode(repo.pref(DailyMixCache.PREF), input.songs.associateBy { it.unique_id }) }.getOrNull()
            emit(HomePlanState(plan = plan, mixes = cached?.second.orEmpty()))
            if (cached != null && cached.first == mixKey && cached.second.isNotEmpty()) return@transformLatest
            // Building mixes is heavy: not while the library is scanning or covers are loading.
            if (input.busy) return@transformLatest
            val daily = runCatching {
                DailyMixes.build(input.songs, now, weather = input.weather, lists = tasteLists(input.favorites, input.taste), options = input.taste)
            }.getOrDefault(emptyList())
            val mixLead = daily.flatMap { it.songs.take(4) }.distinctBy { it.unique_id }
            if (mixLead.isNotEmpty()) services.covers.prioritize(mixLead)
            if (daily.isNotEmpty()) runCatching { repo.setPref(DailyMixCache.PREF, DailyMixCache.encode(mixKey, daily)) }
            emit(HomePlanState(plan = plan, mixes = daily))
        }

    val home: StateFlow<HomeUi> = combine(repo.allSongs(), homePlan) { songs, state ->
        val plan = state.plan ?: return@combine HomeUi()
        val freshById = songs.associateBy { it.unique_id }
        fun fresh(s: Song) = freshById[s.unique_id] ?: s
        fun coverOf(lead: Song, groupSongs: List<Song>): String? {
            val f = fresh(lead)
            return (f.cover_path ?: f.cover_url)
                ?: groupSongs.firstNotNullOfOrNull { it.cover_path ?: it.cover_url }
        }
        HomeUi(
            tiles = plan.tiles.map { t ->
                val ts = t.songs.map(::fresh)
                CollectionUi(t.title, t.title, "${ts.size} songs", coverOf(t.cover, ts), ts.map(Song::toUi))
            },
            shelves = plan.shelves.map { s ->
                val kind = when (s.kind) { ShelfKind.SONGS -> ShelfKindUi.Songs; ShelfKind.ALBUMS -> ShelfKindUi.Albums; ShelfKind.ARTISTS -> ShelfKindUi.Artists }
                val ss = s.songs.map(::fresh)
                ShelfUi(s.title, kind, ss.map(Song::toUi), s.groups.map { g ->
                    val gs = g.songs.map(::fresh)
                    val sub = if (kind == ShelfKindUi.Albums) fresh(g.cover).artist else "${gs.size} songs"
                    CollectionUi(g.name, g.name, sub, coverOf(g.cover, gs), gs.map(Song::toUi))
                })
            },
            mixes = state.mixes.map { m ->
                val ms = m.songs.map(::fresh)
                CollectionUi(m.mood.id, m.title, ms.take(3).joinToString(", ") { it.artist }, null, ms.map(Song::toUi), covers(ms))
            },
        )
    }
        .catch { emit(HomeUi()) }
        .flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, HomeUi())

    private var queueCache: Triple<List<Song>, Long, List<SongUi>> = Triple(emptyList(), -1L, emptyList())
    private var currentCache: Pair<Song?, SongUi?> = null to null

    /** The player is created here on first use, on a background thread, so startup does not wait for it. */
    /** Output volume, 0 to 1; kept here since the engines do not report it. */
    val volume = kotlinx.coroutines.flow.MutableStateFlow(repo.pref("player_volume")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f)

    private val player by lazy {
        services.playback.also { p ->
            if (eqOn.value) p.setEqualizer(eqGains.value)
            if (volume.value != 1f) p.setVolume(volume.value)
            // A listen counts after half the song or four minutes, whichever comes first.
            p.onListened = { song, ms ->
                val token = services.platform.secrets.get(io.max.paperplay.core.backup.SecretKeys.LISTENBRAINZ_TOKEN)
                val full = song.duration_sec * 1000L
                val counts = if (full > 0) ms >= minOf(full / 2, 240_000L) else ms >= 30_000L
                if (token != null && repo.pref("listenbrainz_on") == "true" && counts) scope.launch {
                    val ok = runCatching { listenBrainz.submit(token, song, (nowMoment().epochMs - ms) / 1000) }.getOrDefault(false)
                    if (!ok) addLog("ListenBrainz", "Could not send \"${song.title}\".", error = true)
                }
            }
        }
    }

    private val nowPlayingFlow: Flow<NowPlaying> = flow { emitAll(player.nowPlaying) }
        .flowOn(Dispatchers.Default)

    /** Everything about playback except the position: it changes on a song, queue or button, not every tick. */
    val playbackMeta: StateFlow<PlaybackUiState> = combine(
        nowPlayingFlow.distinctUntilChanged { a, b ->
            a.song?.unique_id == b.song?.unique_id && a.index == b.index && a.isPlaying == b.isPlaying &&
                a.durationMs == b.durationMs && a.shuffle == b.shuffle && a.repeat == b.repeat &&
                (a.queue === b.queue || a.queue == b.queue)
        }.onEach { np -> prioritizeNowPlaying(np) },
        repo.changes,
    ) { np, changes -> np.toUi(changes) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, PlaybackUiState())

    /** Same as [playbackMeta]; kept for callers that only need the song or the play state. */
    val playback: StateFlow<PlaybackUiState> get() = playbackMeta

    /** Position and buffered time. Only the seek bar, mini progress and lyrics should read this. */
    val position: StateFlow<PositionUi> = nowPlayingFlow.map { PositionUi(it.positionMs, it.bufferedMs) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), PositionUi())

    private var lastPriority: Pair<String?, Int> = null to -2

    private fun prioritizeNowPlaying(np: NowPlaying) {
        val key = np.song?.unique_id to np.index
        if (key == lastPriority) return
        lastPriority = key
        val idx = np.index.coerceAtLeast(0)
        val upcoming = if (idx < np.queue.size) np.queue.subList(idx, minOf(idx + 3, np.queue.size)) else emptyList()
        val priority = (listOfNotNull(np.song) + upcoming).distinctBy { it.unique_id }
        if (priority.isNotEmpty()) services.covers.prioritize(priority)
    }

    private fun NowPlaying.toUi(changes: Long): PlaybackUiState {
        // The queue is mapped once per queue or library cover update, not per tick.
        val ui = if (queueCache.first === queue && queueCache.second == changes) {
            queueCache.third
        } else {
            queue.map { (byId[it.unique_id] ?: it).toUi() }.also { queueCache = Triple(queue, changes, it) }
        }
        val currentSong = song?.let { byId[it.unique_id] ?: it }
        val currentUi = if (currentCache.first === currentSong) currentCache.second
        else currentSong?.toUi().also { currentCache = currentSong to it }
        return PlaybackUiState(
            current = currentUi,
            playing = isPlaying,
            durationMs = durationMs,
            shuffle = shuffle,
            repeat = when (repeat) { Repeat.OFF -> RepeatUi.Off; Repeat.ALL -> RepeatUi.All; Repeat.ONE -> RepeatUi.One },
            queue = ui,
            queueIndex = index,
        )
    }

    /** Text of the last failure to start a track. */
    val playbackError: StateFlow<String?> = nowPlayingFlow.map { it.error }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val connection: StateFlow<ConnectionUi> = combine(
        services.auth, services.ready, services.error, repo.chats(),
    ) { auth, ready, error, chats ->
        ConnectionUi(services.hasCredentials, auth, ready, error, chats)
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionUi(hasCredentials = services.hasCredentials))

    /** The running scan, read only by the status chip, the task row and the task page. */
    val scan: StateFlow<ScanUi?> = services.scanner.progress.map { p -> p?.let { ScanUi(it.text, it.fraction) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** True while a scan runs; changes twice per scan, unlike [scan]. */
    val scanning: StateFlow<Boolean> = services.scanner.progress.map { it != null }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    // Reading
    fun album(name: String): Flow<List<SongUi>> = repo.album(name)
        .onEach { if (it.isNotEmpty()) services.covers.prioritize(it.take(30)) }
        .map { it.map(Song::toUi) }.flowOn(Dispatchers.Default)
    fun artist(name: String): Flow<List<SongUi>> = repo.artist(name)
        .onEach { if (it.isNotEmpty()) services.covers.prioritize(it.take(30)) }
        .map { it.map(Song::toUi) }.flowOn(Dispatchers.Default)
    fun playlist(id: Long): Flow<List<SongUi>> = repo.playlistSongs(id)
        .onEach { if (it.isNotEmpty()) services.covers.prioritize(it.take(30)) }
        .map { it.map(Song::toUi) }.flowOn(Dispatchers.Default)
    fun search(query: String): Flow<List<SongUi>> = repo.search(query)
        .onEach { if (it.isNotEmpty()) services.covers.prioritize(it.take(20)) }
        .map { it.map(Song::toUi) }.flowOn(Dispatchers.Default)

    // Playing
    fun play(songs: List<SongUi>, index: Int) {
        val list = songs.mapNotNull { byId[it.id] }
        if (list.isEmpty()) return
        val start = list.indexOfFirst { it.unique_id == songs.getOrNull(index)?.id }.coerceAtLeast(0)
        player.setQueue(list, start)
    }

    fun shuffleAll(songs: List<SongUi>) {
        val list = songs.mapNotNull { byId[it.id] }.shuffled()
        if (list.isEmpty()) return
        player.setQueue(list, 0)
    }

    fun togglePlay() = if (playbackMeta.value.playing) player.pause() else player.play()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(fraction: Float) = player.seek((fraction * playbackMeta.value.durationMs).toLong())
    fun setVolume(v: Float) {
        volume.value = v.coerceIn(0f, 1f)
        player.setVolume(volume.value)
        repo.setPref("player_volume", volume.value.toString())
    }

    fun toggleShuffle() = player.setShuffle(!playbackMeta.value.shuffle)
    fun cycleRepeat() = player.setRepeat(
        when (playbackMeta.value.repeat) { RepeatUi.Off -> Repeat.ALL; RepeatUi.All -> Repeat.ONE; RepeatUi.One -> Repeat.OFF },
    )
    fun playQueueIndex(index: Int) {
        val q = player.nowPlaying.value.queue
        if (index in q.indices) player.setQueue(q, index)
    }

    // Editing
    fun setFavorite(id: String, favorite: Boolean) {
        val s = byId[id] ?: return
        if (s.favorite != favorite) scope.launch(Dispatchers.IO) { repo.toggleFavorite(s) }
    }

    fun createPlaylist(name: String, thenAdd: Collection<String> = emptyList()) {
        scope.launch(Dispatchers.IO) {
            val id = repo.createPlaylist(name.trim().ifEmpty { "New playlist" })
            thenAdd.mapNotNull { byId[it] }.forEach { repo.addToPlaylist(id, it) }
        }
    }

    fun addToPlaylist(playlistId: String, songIds: Collection<String>) {
        val id = playlistId.toLongOrNull() ?: return
        scope.launch(Dispatchers.IO) { songIds.mapNotNull { byId[it] }.forEach { repo.addToPlaylist(id, it) } }
    }

    fun removeFromPlaylist(playlistId: String, songId: String) {
        val id = playlistId.toLongOrNull() ?: return
        val s = byId[songId] ?: return
        scope.launch(Dispatchers.IO) { repo.removeFromPlaylist(id, s) }
    }

    fun renamePlaylist(playlistId: String, name: String) {
        val id = playlistId.toLongOrNull() ?: return
        val n = name.trim().ifEmpty { return }
        scope.launch(Dispatchers.IO) { repo.renamePlaylist(id, n) }
    }

    fun deletePlaylist(playlistId: String) {
        val id = playlistId.toLongOrNull() ?: return
        scope.launch(Dispatchers.IO) { repo.deletePlaylist(id) }
    }

    // Connection
    /** Returns an error text, or null when the credentials were saved and the login started. */
    fun saveCredentials(apiId: String, apiHash: String, botToken: String): String? {
        val id = apiId.trim().toIntOrNull() ?: return "The API id is a number."
        if (apiHash.isBlank()) return "Enter the API hash."
        if (botToken.isBlank()) return "Enter the bot token."
        services.saveCredentials(id, apiHash.trim(), botToken.trim())
        hasCredentials.value = true
        services.connect()
        return null
    }

    /** True while the bot's credentials are stored; false after [logOut], which shows setup again. */
    val hasCredentials = kotlinx.coroutines.flow.MutableStateFlow(services.hasCredentials)

    fun logOut() {
        scope.launch { services.logOut(); hasCredentials.value = false }
    }

    fun connect() = services.connect()

    fun pref(key: String): String? = repo.pref(key)
    fun setPref(key: String, value: String) = repo.setPref(key, value)

    fun mainChatId(): Long? = parseMainChat(repo.pref(MAIN_CHAT_KEY))

    fun setMainChat(chatId: Long) {
        repo.setPref(MAIN_CHAT_KEY, """{"id":$chatId,"at":${nowMoment().epochMs}}""")
    }

    /** Adds a chat to read songs from. [id] is Telegram's chat id, for example -1001234567890. */
    fun addChat(id: String, title: String): String? {
        val chatId = id.trim().toLongOrNull() ?: return "The chat id is a number, like -1001234567890."
        services.addChat(chatId, title.trim().ifEmpty { chatId.toString() })
        return null
    }

    fun rescan() {
        scope.launch { repo.chatList().forEach { services.scanner.rescanChat(it) } }
    }
}

private fun <T> groups(songs: List<Song>, key: (Song) -> String, make: (String, List<Song>) -> T): List<T> =
    songs.filter { key(it).isNotBlank() }.groupBy { key(it) }.entries
        .sortedBy { it.key.lowercase() }.map { make(it.key, it.value) }

private fun covers(songs: List<Song>): List<String?> = songs.mapNotNull { it.cover_path ?: it.cover_url }.distinct().take(4)

private fun albumSubtitle(list: List<Song>): String {
    val artists = list.map { it.artist }.distinct()
    return (if (artists.size == 1) artists[0] else "Various artists") + " · ${list.size} songs"
}

private val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv")

internal fun Song.toUi(): SongUi {
    val ext = (codec ?: file_name.substringAfterLast('.', "")).lowercase().trim()
    val lossless = ext in LOSSLESS
    val kbps = if (duration_sec > 0) size * 8 / duration_sec / 1000 else 0L
    val label = buildString {
        if (ext.isNotEmpty()) append(ext.uppercase())
        if (lossless && bit_depth != null && sample_rate != null) append(" $bit_depth/${sample_rate!! / 1000.0}".replace(".0", ""))
        else if (!lossless && kbps > 0) append(" ${kbps}k")
    }.trim().ifEmpty { null }
    return SongUi(
        id = unique_id, title = title, artist = artist, album = album, genre = genre, coverUri = cover_path ?: cover_url,
        durationSec = duration_sec.toInt(), sizeBytes = size, year = year?.toInt(), plays = plays.toInt(),
        addedAt = date, qualityLabel = label, qualityRank = if (lossless) 100_000 + (bit_depth ?: 16).toInt() * 1000 else kbps.toInt(),
        lossless = lossless, favorite = favorite, pinned = pinned, offline = size > 0 && cached_bytes >= size,
    )
}

