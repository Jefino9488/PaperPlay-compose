package io.max.paperplay.ui.app

import io.max.paperplay.core.app.AppServices
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
import kotlinx.coroutines.flow.transformLatest
import io.max.paperplay.ui.state.GroupUi
import io.max.paperplay.ui.state.LibraryUi
import io.max.paperplay.ui.state.Load
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.state.RepeatUi
import io.max.paperplay.ui.state.SongUi
import kotlinx.coroutines.Dispatchers
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
    val scanText: String? = null,
    val scanFraction: Double? = null,
    val chats: List<LibraryChat> = emptyList(),
)

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
            services.covers.prioritize(songs.take(30))
            LibraryUi(
                songs = Load.Ready(songs.map(Song::toUi)),
                albums = groups(songs, { it.album }) { name, list -> GroupUi(name, name, albumSubtitle(list), covers(list)) },
                artists = groups(songs, { it.artist }) { name, list -> GroupUi(name, name, "${list.size} songs", covers(list)) },
            )
        },
        repo.changes.map {
            repo.repo.db.playlistsQueries.livePlaylists().executeAsList().map { p ->
                val songs = repo.repo.db.playlistsQueries.playlistSongs(p.id).executeAsList()
                GroupUi(p.id.toString(), p.name, "${songs.size} songs", covers(songs))
            }
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
        val taste: TasteSettings, val weather: WeatherKind?,
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

    val http: HttpFetcher = io.max.paperplay.core.integrations.JavaHttp()
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

    fun playNext(song: SongUi) {
        byId[song.id]?.let { services.playback.playNext(it) }
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

    /** Listening totals for the last [days] days (0 for all time): per song and per day. */
    fun listenStats(days: Int): Pair<List<io.max.paperplay.core.library.ListenTotal>, Map<String, Long>> {
        val from = if (days <= 0) "0000-00-00" else io.max.paperplay.core.app.dayOf(nowMoment().epochMs - (days - 1) * 86_400_000L)
        return repo.repo.listenTotals(from) to repo.repo.listenPerDay(from)
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
            services.playback.pause()
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
        services.playback.setEqualizer(if (on) gains else null)
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

    init {
        if (eqOn.value) services.playback.setEqualizer(eqGains.value)
        // A listen counts after half the song or four minutes, whichever comes first.
        services.playback.onListened = { song, ms ->
            val token = services.platform.secrets.get(io.max.paperplay.core.backup.SecretKeys.LISTENBRAINZ_TOKEN)
            val full = song.duration_sec * 1000L
            val counts = if (full > 0) ms >= minOf(full / 2, 240_000L) else ms >= 30_000L
            if (token != null && repo.pref("listenbrainz_on") == "true" && counts) scope.launch {
                val ok = runCatching { listenBrainz.submit(token, song, (nowMoment().epochMs - ms) / 1000) }.getOrDefault(false)
                if (!ok) addLog("ListenBrainz", "Could not send \"${song.title}\".", error = true)
            }
        }
    }

    /** What the app has been doing, newest last. Kept in memory; bot tokens are blanked out. */
    val log = MutableStateFlow<List<LogLine>>(emptyList())
    private var lastScanLog = 0L

    fun addLog(source: String, message: String, error: Boolean = false) {
        val clean = message.replace(Regex("\\b\\d{5,}:[A-Za-z0-9_-]{20,}\\b"), "<bot token>")
        log.value = (log.value + LogLine(nowMoment().epochMs, source, clean, error)).takeLast(1000)
    }

    fun clearLog() { log.value = emptyList() }

    init {
        scope.launch { services.auth.collect { if (it.isNotEmpty()) addLog("Telegram", it.removePrefix("authorizationState")) } }
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
    private val homePlan: Flow<HomePlanState> = combine(
        repo.allSongs(), repo.recents(30), repo.favorites(), library, taste.combine(weather) { t, w -> t to w },
    ) { songs, recent, fav, lib, tw ->
        HomeInput(songs, recent, fav, minOf(lib.playlists.size, 3) + if (fav.isEmpty()) 0 else 1, tw.first, tw.second)
    }
        .distinctUntilChangedBy { "${it.songs.size}:${it.favorites.size}:${it.reserved}:${it.taste.at}:${it.taste.showMixes}:${it.weather}:${nowMoment().dayKey}" }
        .transformLatest { input ->
            if (input.songs.isEmpty()) { emit(HomePlanState()); return@transformLatest }
            val now = nowMoment()
            val plan = HomeShelves.plan(input.songs, input.recent, input.favorites, now, tileCount = 6 - input.reserved)
            val leadSongs = buildList {
                plan.tiles.forEach { add(it.cover); addAll(it.songs.take(1)) }
                plan.shelves.forEach { s ->
                    addAll(s.songs.take(15))
                    s.groups.take(15).forEach { add(it.cover) }
                }
            }.distinctBy { it.unique_id }
            if (leadSongs.isNotEmpty()) services.covers.prioritize(leadSongs)
            // Tiles and shelves show at once; the slower daily mixes join when they are ready.
            emit(HomePlanState(plan = plan))
            if (!input.taste.showMixes) return@transformLatest
            val daily = runCatching {
                DailyMixes.build(input.songs, now, weather = input.weather, lists = tasteLists(input.favorites, input.taste), options = input.taste)
            }.getOrDefault(emptyList())
            val mixLead = daily.flatMap { it.songs.take(4) }.distinctBy { it.unique_id }
            if (mixLead.isNotEmpty()) services.covers.prioritize(mixLead)
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

    val playback: StateFlow<PlaybackUiState> = combine(
        services.playback.nowPlaying.onEach { np ->
            val idx = np.index.coerceAtLeast(0)
            val upcoming = if (idx < np.queue.size) np.queue.subList(idx, minOf(idx + 3, np.queue.size)) else emptyList()
            val priority = (listOfNotNull(np.song) + upcoming).distinctBy { it.unique_id }
            if (priority.isNotEmpty()) services.covers.prioritize(priority)
        },
        repo.changes,
    ) { np, changes -> np.toUi(changes) }
        .flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, PlaybackUiState())

    private fun NowPlaying.toUi(changes: Long): PlaybackUiState {
        // The queue is mapped once per queue or library cover update, not per position tick.
        val ui = if (queueCache.first === queue && queueCache.second == changes) {
            queueCache.third
        } else {
            queue.map { (byId[it.unique_id] ?: it).toUi() }.also { queueCache = Triple(queue, changes, it) }
        }
        val currentSong = song?.let { byId[it.unique_id] ?: it }
        return PlaybackUiState(
            current = currentSong?.toUi(),
            playing = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            shuffle = shuffle,
            repeat = when (repeat) { Repeat.OFF -> RepeatUi.Off; Repeat.ALL -> RepeatUi.All; Repeat.ONE -> RepeatUi.One },
            queue = ui,
            queueIndex = index,
        )
    }

    /** Text of the last failure to start a track. */
    val playbackError: StateFlow<String?> = services.playback.nowPlaying.map { it.error }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val connection: StateFlow<ConnectionUi> = combine(
        services.auth, services.ready, services.error, services.scanner.progress, repo.chats(),
    ) { auth, ready, error, scan, chats ->
        ConnectionUi(services.hasCredentials, auth, ready, error, scan?.text, scan?.fraction, chats)
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionUi(hasCredentials = services.hasCredentials))

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
        services.playback.setQueue(list, start)
    }

    fun shuffleAll(songs: List<SongUi>) {
        val list = songs.mapNotNull { byId[it.id] }.shuffled()
        if (list.isEmpty()) return
        services.playback.setQueue(list, 0)
    }

    fun togglePlay() = if (playback.value.playing) services.playback.pause() else services.playback.play()
    fun next() = services.playback.next()
    fun previous() = services.playback.previous()
    fun seekTo(fraction: Float) = services.playback.seek((fraction * playback.value.durationMs).toLong())
    /** Output volume, 0 to 1; kept here since the engines do not report it. */
    val volume = kotlinx.coroutines.flow.MutableStateFlow(1f)
    fun setVolume(v: Float) { volume.value = v.coerceIn(0f, 1f); services.playback.setVolume(volume.value) }
    fun toggleShuffle() = services.playback.setShuffle(!playback.value.shuffle)
    fun cycleRepeat() = services.playback.setRepeat(
        when (playback.value.repeat) { RepeatUi.Off -> Repeat.ALL; RepeatUi.All -> Repeat.ONE; RepeatUi.One -> Repeat.OFF },
    )
    fun playQueueIndex(index: Int) {
        val q = services.playback.nowPlaying.value.queue
        if (index in q.indices) services.playback.setQueue(q, index)
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

