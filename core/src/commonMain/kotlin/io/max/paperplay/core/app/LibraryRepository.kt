package io.max.paperplay.core.app

import io.max.paperplay.core.library.LibraryChat
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.Playlist
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.library.laterStamp
import io.max.paperplay.core.library.newUuid
import kotlinx.coroutines.Dispatchers
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

    /** Call after changing the database behind this class's back (e.g. a restored backup). */
    fun invalidate() = changes.update { it + 1 }

    private fun <T> watch(query: () -> T): Flow<T> =
        changes.map { query() }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun <T> write(block: () -> T): T = block().also { invalidate() }

    // Reads
    fun allSongs(): Flow<List<Song>> = watch { songs.allSongs().executeAsList() }
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
        return watch { if (words.isEmpty()) emptyList() else songs.allSongs().executeAsList().filter { it.matches(words) } }
    }

    fun song(chatId: Long, messageId: Long): Song? = songs.song(chatId, messageId).executeAsOneOrNull()
    fun songByUniqueId(uniqueId: String): Song? = songs.songByUniqueId(uniqueId).executeAsOneOrNull()
    fun songsInChat(chatId: Long): List<Song> = songs.songsInChat(chatId).executeAsList()
    fun chatList(): List<LibraryChat> = misc.chats().executeAsList()
    fun chat(id: Long): LibraryChat? = chatList().firstOrNull { it.id == id }

    // Favorites, listening
    fun toggleFavorite(s: Song) = write {
        val fresh = songs.song(s.chat_id, s.message_id).executeAsOneOrNull() ?: return@write
        songs.setFavorite(!fresh.favorite, clock(), s.chat_id, s.message_id)
    }

    /** Stores [ms] listened to [s] today and, when [finished] is not null, counts a play (true) or skip (false). */
    fun recordListen(s: Song, ms: Long, finished: Boolean?) = write {
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
    fun upsertBotSong(s: Song): Boolean = write { repo.upsertBotSong(s) }
    fun removePost(chatId: Long, messageId: Long) = write { repo.removePost(chatId, messageId) }
    fun removeChat(chatId: Long): Int = write { repo.removeChat(chatId) }
    fun saveTopic(chatId: Long, topicId: Long, name: String) = write { misc.saveTopic(chatId, topicId, name) }
    fun addChat(id: Long, title: String) = write {
        if (chat(id) == null) misc.insertChat(LibraryChat(id, title, null, true, false))
    }
    fun advanceScanned(chatId: Long, messageId: Long) = misc.advanceScanned(messageId, chatId)
    fun setScan(chatId: Long, messageId: Long, walkDone: Boolean) = misc.setScan(messageId, walkDone, chatId)

    fun pref(key: String): String? = repo.pref(key)
    fun setPref(key: String, value: String) = repo.setPref(key, value)

    init {
        if (repo.pref("device_id") == null) repo.setPref("device_id", newUuid())
    }
}

private fun Song.matches(words: List<String>): Boolean {
    val hay = "$title $artist $album ${genre.orEmpty()}".lowercase()
    return words.all { it in hay }
}

/** UTC calendar day of [millis] as yyyy-MM-dd. */
internal fun dayOf(millis: Long): String {
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
