package io.max.paperplay.core.app

import io.max.paperplay.core.library.LibraryChat
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.Playlist
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.library.laterStamp
import io.max.paperplay.core.library.newUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Flow-based view of the library. Every write made through this class bumps a change counter,
 * which re-runs the queries behind the `watch` flows. Write only through here, never through [repo].
 */
class LibraryRepository(val repo: LibraryRepo, private val clock: () -> Long) {
    private val songs get() = repo.db.songsQueries
    private val lists get() = repo.db.playlistsQueries
    private val misc get() = repo.db.miscQueries
    /** Counts every write; collect it to re-run a query that [watch] does not cover. */
    val changes = MutableStateFlow(0L)

    /** Call after changing the database behind this class's back (e.g. a restored backup). Re-runs the queries at once. */
    fun invalidate() = changes.update { it + 1 }

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val soft = Channel<Unit>(Channel.CONFLATED)

    /** True while the song-info run is going; the cover workers stand down for it. */
    val enrichRunning = MutableStateFlow(false)

    init {
        // Background writers: the first bump goes out at once, later ones merge into one per [SOFT_GAP_MS].
        bgScope.launch {
            for (u in soft) {
                invalidate()
                delay(SOFT_GAP_MS)
            }
        }
    }

    /** Like [invalidate] for background writers: at most one bump per couple of seconds, however many writes. */
    fun invalidateSoon() { soft.trySend(Unit) }

    private fun <T> watch(query: () -> T): Flow<T> =
        changes.map { query() }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun <T> write(soft: Boolean = false, notify: Boolean = true, block: () -> T): T =
        block().also { if (notify) { if (soft) invalidateSoon() else invalidate() } }

    /** Runs [block] as one database transaction and signals one change (coalesced when [soft]). */
    fun <T> batch(soft: Boolean = true, block: () -> T): T =
        write(soft) { repo.db.transactionWithResult { block() } }

    private val allShared: Flow<List<Song>> by lazy {
        watch { songs.allSongs().executeAsList() }
            .shareIn(bgScope, SharingStarted.WhileSubscribed(10_000, 0), replay = 1)
    }

    // Reads
    /** One query shared by every collector. */
    fun allSongs(): Flow<List<Song>> = allShared
    fun favorites(): Flow<List<Song>> = watch { songs.favorites().executeAsList() }
    fun recents(limit: Long = 50): Flow<List<Song>> = watch {
        songs.recent(limit) { chat_id, message_id, unique_id, remote_id, title, artist, album,
            duration_sec, size, mime, file_name, date, cover_path, release_id, cover_url, genre, year,
            metadata_source, favorite, cover_hi_res, user_edited, enrich_state, lyrics, cached_bytes,
            last_played_at, pinned, tags_at, favorite_at, cover_at, instrumental, mood_scanned_at,
            plays, skips, topic_id, sample_rate, bit_depth, codec ->
            Song(chat_id, message_id, unique_id, remote_id, title, artist, album, duration_sec, size,
                mime, file_name, date, cover_path, release_id, cover_url, genre, year, metadata_source,
                favorite, cover_hi_res, user_edited, enrich_state, lyrics, cached_bytes, last_played_at,
                pinned, tags_at, favorite_at, cover_at, instrumental, mood_scanned_at, plays, skips,
                topic_id, sample_rate, bit_depth, codec)
        }.executeAsList()
    }
    fun album(name: String): Flow<List<Song>> = watch { songs.albumRows(name).executeAsList().sortedBy { it.title } }
    fun artist(name: String): Flow<List<Song>> = watch { songs.artistRows(name).executeAsList() }
    fun playlistSongs(id: Long): Flow<List<Song>> = watch { lists.playlistSongs(id).executeAsList() }
    fun playlists(): Flow<List<Playlist>> = watch { lists.livePlaylists().executeAsList() }
    fun chats(): Flow<List<LibraryChat>> = watch { misc.chats().executeAsList() }

    /** Songs where every word of [query] occurs in the title, artist, album or genre (any case). */
    fun search(query: String): Flow<List<Song>> {
        val words = query.lowercase().split(' ', '\t').filter { it.isNotEmpty() }
        if (words.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList())
        return allShared.map { l -> l.filter { it.matches(words) } }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    /** Playlists with song count and up to four cover links each, from one query (no per-playlist song lists). */
    fun playlistSummaries(): Flow<List<PlaylistSummary>> = watch {
        val rows = lists.playlistCoverRows().executeAsList().groupBy { it.playlist_id }
        lists.livePlaylists().executeAsList().map { p ->
            val r = rows[p.id].orEmpty()
            PlaylistSummary(p.id, p.uuid, p.name, r.size, r.mapNotNull { it.cover_path }.distinct().take(4))
        }
    }

    fun song(chatId: Long, messageId: Long): Song? = songs.song(chatId, messageId).executeAsOneOrNull()
    /** Saves what the start of a song's file said about its sound. A shown list refreshes. */
    fun setAudioDetails(uniqueId: String, h: AudioHeader, notify: Boolean = true) = write(soft = true, notify = notify) {
        songs.setAudioDetails(h.sampleRate?.toLong(), h.bitDepth?.toLong(), h.codec ?: songs.songByUniqueId(uniqueId).executeAsOneOrNull()?.codec, uniqueId)
    }

    fun songByUniqueId(uniqueId: String): Song? = songs.songByUniqueId(uniqueId).executeAsOneOrNull()
    fun replaceSong(s: Song) = write { repo.db.enrichQueries.replaceSong(s) }
    /** Saves many rows in one transaction with one (coalesced) change signal. */
    fun replaceSongs(list: List<Song>) { if (list.isNotEmpty()) batch { list.forEach { repo.db.enrichQueries.replaceSong(it) } } }
    fun nextToEnrich(): Song? = songs.nextToEnrich().executeAsOneOrNull()
    fun unmatchedSongs(): List<Song> = songs.allSongs().executeAsList().filter { it.enrich_state == 2L && !it.user_edited }
    fun setCachedBytes(uniqueId: String, bytes: Long, notify: Boolean = false) {
        if (notify) write(soft = true) { songs.setCachedBytes(bytes, uniqueId) } else songs.setCachedBytes(bytes, uniqueId)
    }
    fun cachedSongs(): List<Song> = songs.cachedSongs().executeAsList()
    fun cacheLimit(): Long = repo.cacheLimit()
    fun songsInChat(chatId: Long): List<Song> = songs.songsInChat(chatId).executeAsList()
    fun songsNeedingCovers(): List<Song> = songs.songsNeedingCovers().executeAsList()
    /** Songs of one album name, for sharing a cover and album info between its tracks. */
    fun albumSongs(name: String): List<Song> = songs.albumRows(name).executeAsList()
    /** Songs whose cover is only a thumbnail but have a full-size source. */
    fun songsNeedingUpgrade(): List<Song> = songs.songsNeedingUpgrade().executeAsList()
    /** Ids of songs with no cover, then of songs holding only a thumbnail; light rows for the cover queue. */
    fun coverCandidateIds(): List<String> = songs.idsNeedingCovers().executeAsList() + songs.idsNeedingUpgrade().executeAsList()
    fun updateCover(
        uniqueId: String,
        coverPath: String? = null,
        coverUrl: String? = null,
        coverHiRes: Boolean? = null,
        album: String? = null,
        year: Long? = null,
        genre: String? = null,
        metadataSource: String? = null,
        enrichState: Long? = null,
        notify: Boolean = true,
    ) {
        val block = {
            songs.updateSongCover(
                coverPath = coverPath,
                coverUrl = coverUrl,
                coverHiRes = coverHiRes,
                album = album,
                year = year,
                genre = genre,
                metadataSource = metadataSource,
                enrichState = enrichState,
                uniqueId = uniqueId,
            )
        }
        if (notify) write(soft = true) { block() } else block()
    }
    fun fillEmbeddedTags(
        uniqueId: String, title: String?, artist: String?, album: String?, year: Long?, genre: String?, coverPath: String?,
    ) = write(soft = true) { songs.fillEmbeddedTags(title, artist, album, year, genre, coverPath, uniqueId) }
    fun chatList(): List<LibraryChat> = misc.chats().executeAsList()
    fun chat(id: Long): LibraryChat? = chatList().firstOrNull { it.id == id }

    // Favorites, listening
    fun toggleFavorite(s: Song) = write {
        val fresh = songs.song(s.chat_id, s.message_id).executeAsOneOrNull() ?: return@write
        songs.setFavorite(!fresh.favorite, clock(), s.chat_id, s.message_id)
    }

    /** Marks songs as kept on this device (or not). */
    fun setPinned(uniqueIds: Collection<String>, pinned: Boolean) = write {
        uniqueIds.forEach { songs.setPinnedById(pinned, it) }
    }

    fun incompletePins(): List<Song> = songs.incompletePins().executeAsList()

    /** Stores [ms] listened to [s] today and, when [finished] is not null, counts a play (true) or skip (false). */
    fun recordListen(s: Song, ms: Long, finished: Boolean?, notify: Boolean = true) = write(soft = true, notify = notify) {
        val now = clock()
        songs.markPlayed(now, s.chat_id, s.message_id)
        if (finished != null) repo.countPlay(s, finished)
        repo.addListen(repo.pref("device_id") ?: "self", dayOf(now), s.unique_id, ms, if (finished == true) 1 else 0)
    }

    // Playlists
    fun createPlaylist(name: String): Long = write { repo.createPlaylist(name) }
    fun deletePlaylist(id: Long) = write { repo.deletePlaylist(id) }
    fun addToPlaylist(id: Long, s: Song) = write { repo.addToPlaylist(id, s) }
    fun removeFromPlaylist(id: Long, s: Song) = write { repo.removeFromPlaylist(id, s) }
    fun renamePlaylist(id: Long, name: String) = write {
        val prev = lists.playlist(id).executeAsOneOrNull()?.modified_at ?: 0
        lists.renamePlaylist(name, laterStamp(clock(), prev), id)
    }

    // Used by the scanner
    fun upsertBotSong(s: Song): Boolean = write(soft = true) { repo.upsertBotSong(s) }
    fun removePost(chatId: Long, messageId: Long) = write { repo.removePost(chatId, messageId) }
    fun removeChat(chatId: Long): Int = write { repo.removeChat(chatId) }
    fun saveTopic(chatId: Long, topicId: Long, name: String) = write { misc.saveTopic(chatId, topicId, name) }
    /** Forum topics known per chat. */
    fun topicCountsByChat(): Map<Long, Int> = misc.topics().executeAsList().groupingBy { it.chat_id }.eachCount()
    /** Posts copied into the main chat, by the chat they came from. */
    fun copyCountsByChat(): Map<Long, Int> = repo.db.filingQueries.allPostCopies().executeAsList().groupingBy { it.chat_id }.eachCount()
    fun addChat(id: Long, title: String) = write {
        if (chat(id) == null) misc.insertChat(LibraryChat(id, title, null, true, false))
    }
    fun advanceScanned(chatId: Long, messageId: Long) = misc.advanceScanned(messageId, chatId)
    fun setScan(chatId: Long, messageId: Long, walkDone: Boolean) = misc.setScan(messageId, walkDone, chatId)
    fun renameChat(chatId: Long, title: String) = write { misc.renameChat(title, chatId) }

    fun pref(key: String): String? = repo.pref(key)
    fun setPref(key: String, value: String) = repo.setPref(key, value)

    init {
        if (repo.pref("device_id") == null) repo.setPref("device_id", newUuid())
    }
}

/** A playlist row for the library page: count and a few cover links. */
data class PlaylistSummary(val id: Long, val uuid: String, val name: String, val count: Int, val covers: List<String>)

private const val SOFT_GAP_MS = 2_000L

private fun Song.matches(words: List<String>): Boolean {
    val hay = "$title $artist $album ${genre.orEmpty()}".lowercase()
    return words.all { it in hay }
}

/** UTC calendar day of [millis] as yyyy-MM-dd. */
fun dayOf(millis: Long): String {
    val z = millis.floorDiv(86_400_000L) + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
