package io.max.paperplay.core.library

import io.max.paperplay.core.db.LibraryDatabase

/** Multi-statement operations of Drift's AppDb, as transactions. Streams (`watch*`) are not ported. */
class LibraryRepo(val db: LibraryDatabase, private val clock: () -> Long) {
    private val songs get() = db.songsQueries
    private val lists get() = db.playlistsQueries
    private val misc get() = db.miscQueries

    fun createPlaylist(name: String, sourceUrl: String? = null): Long = db.transactionWithResult {
        lists.insertPlaylist(name, newUuid(), clock(), sourceUrl)
        lists.lastInsertRowId().executeAsOne()
    }

    fun deletePlaylist(id: Long) {
        val prev = lists.playlist(id).executeAsOneOrNull()?.modified_at ?: 0
        lists.markPlaylistDeleted(laterStamp(clock(), prev), id)
    }

    fun addToPlaylist(id: Long, s: Song) = db.transaction {
        val now = clock()
        lists.reviveEntry(now, id, s.chat_id, s.message_id)
        lists.insertEntry(id, s.chat_id, s.message_id, now)
    }

    fun removeFromPlaylist(id: Long, s: Song) = lists.removeEntry(clock(), id, s.chat_id, s.message_id)

    fun setPref(key: String, value: String) = misc.setPref(key, value)
    fun pref(key: String): String? = misc.pref(key).executeAsOneOrNull()

    fun cacheLimit(): Long = pref("cache_limit")?.toLongOrNull() ?: DEFAULT_CACHE_LIMIT

    fun addListen(origin: String, day: String, key: String, ms: Long, plays: Long) = db.transaction {
        misc.addListenUpdate(ms, plays, origin, day, key)
        misc.insertListen(origin, day, key, ms, plays)
    }

    /** Rows from another device as (origin, day, key, ms, plays); the larger value wins. */
    fun mergeListen(rows: List<List<Any>>, self: String) = db.transaction {
        for (r in rows) {
            val origin = r[0] as String
            if (origin == self) continue
            val ms = (r[3] as Number).toLong()
            val plays = (r[4] as Number).toLong()
            misc.mergeListenUpdate(ms, plays, origin, r[1] as String, r[2] as String)
            misc.insertListen(origin, r[1] as String, r[2] as String, ms, plays)
        }
    }

    fun countPlay(s: Song, finished: Boolean) =
        if (finished) songs.countFinished(s.chat_id, s.message_id) else songs.countSkipped(s.chat_id, s.message_id)

    fun prepareOlderRows() = db.transaction {
        songs.prepareTagsAt()
        songs.prepareFavoriteAt()
        for (p in lists.emptyUuidPlaylists().executeAsList()) lists.setPlaylistUuid(legacyPlaylistUuid(p.id, p.name), p.id)
    }

    fun clearLearnedRows() = db.transaction {
        songs.clearLearnedSongs()
        misc.clearMoodVotes()
    }

    fun listenTotals(fromDay: String = "0000-00-00"): List<ListenTotal> =
        misc.listenTotals(fromDay).executeAsList().map { ListenTotal(it.k, it.ms ?: 0, it.plays ?: 0, it.title, it.artist) }

    /**
     * Saves a song the bot has seen. An existing row only refreshes its remote id and topic; a repeat of a
     * file listed under another post is remembered as a copy. True only when a new song was created.
     */
    fun upsertBotSong(s: Song): Boolean = db.transactionWithResult {
        if (songs.song(s.chat_id, s.message_id).executeAsOneOrNull() != null) {
            songs.refreshPost(s.remote_id, s.topic_id, s.chat_id, s.message_id)
            return@transactionWithResult false
        }
        if (songs.songByUniqueId(s.unique_id).executeAsOneOrNull() == null) {
            songs.insertSong(s)
            return@transactionWithResult true
        }
        misc.insertPostCopy(PostCopy(s.chat_id, s.message_id, s.unique_id, s.file_name, s.size, s.topic_id, s.date))
        false
    }

    /** A post is gone. If it was the listed one and a copy remains, the copy takes its place. */
    fun removePost(chatId: Long, messageId: Long, preferChat: Long? = null) = db.transaction {
        misc.deletePostCopy(chatId, messageId)
        val row = songs.song(chatId, messageId).executeAsOneOrNull() ?: return@transaction
        val copies = misc.postCopiesOf(row.unique_id).executeAsList()
        val copy = copies.firstOrNull { it.chat_id == preferChat } ?: copies.firstOrNull()
        if (copy == null) {
            songs.deleteSong(chatId, messageId)
            return@transaction
        }
        misc.deletePostCopy(copy.chat_id, copy.message_id)
        songs.repointPost(copy.chat_id, copy.message_id, null, copy.topic_id, chatId, messageId)
        moveEntries(chatId, messageId, copy.chat_id, copy.message_id)
    }

    /** Forgets a channel; songs only in it go with it. Returns how many songs went. */
    fun removeChat(chatId: Long, preferChat: Long? = null): Int = db.transactionWithResult {
        misc.deletePostCopiesOfChat(chatId)
        var gone = 0
        for (s in songs.songsInChat(chatId).executeAsList()) {
            if (misc.postCopiesOf(s.unique_id).executeAsList().isEmpty()) gone++
            removePost(chatId, s.message_id, preferChat)
        }
        misc.deleteTopicsOfChat(chatId)
        misc.deleteChat(chatId)
        gone
    }

    /** The file was posted again as [messageId]; its one row carries on under the new post. */
    fun movePostTo(s: Song, messageId: Long, remoteId: String, topicId: Long) = db.transaction {
        misc.deletePostCopy(s.chat_id, messageId)
        songs.repointPost(s.chat_id, messageId, remoteId, topicId, s.chat_id, s.message_id)
        misc.moveMoodVotes(s.chat_id, messageId, s.chat_id, s.message_id)
        moveEntries(s.chat_id, s.message_id, s.chat_id, messageId, keepOrder = true)
    }

    /** Lists [s] under its new post in another chat; the old post becomes a repeat of the file. */
    fun listAtCopy(s: Song, chatId: Long, messageId: Long, remoteId: String, topicId: Long?) = db.transaction {
        misc.deletePostCopy(chatId, messageId)
        misc.insertPostCopy(PostCopy(s.chat_id, s.message_id, s.unique_id, s.file_name, s.size, s.topic_id, s.date))
        songs.repointPost(chatId, messageId, remoteId, topicId, s.chat_id, s.message_id)
        misc.moveMoodVotes(chatId, messageId, s.chat_id, s.message_id)
        moveEntries(s.chat_id, s.message_id, chatId, messageId, keepOrder = true)
    }

    private fun moveEntries(fromChat: Long, fromMessage: Long, toChat: Long, toMessage: Long, keepOrder: Boolean = false) {
        val now = clock()
        for (e in lists.entriesOf(fromChat, fromMessage).executeAsList()) {
            val addedAt = if (keepOrder || e.added_at > now) e.added_at else now
            lists.reviveEntry(addedAt, e.playlist_id, toChat, toMessage)
            lists.insertEntry(e.playlist_id, toChat, toMessage, addedAt)
            lists.removeEntry(now, e.playlist_id, fromChat, fromMessage)
        }
    }
}
