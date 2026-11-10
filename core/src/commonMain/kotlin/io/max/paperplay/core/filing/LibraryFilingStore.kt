package io.max.paperplay.core.filing

import io.max.paperplay.core.library.ForumTopic
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.PostCopy
import io.max.paperplay.core.library.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

const val MAIN_CHAT_KEY = "main_chat"
const val GONE_KEY = "deleted_songs"
private const val EXTRA_POSTS_KEY = "extra_bot_posts"

/** The id in the saved main chat choice (`{"id":..,"at":..}`), if any. */
fun parseMainChat(json: String?): Long? = json?.let {
    runCatching { Json.parseToJsonElement(it).jsonObject["id"]?.jsonPrimitive?.longOrNull }.getOrNull()
}

/** When the main chat was chosen, in ms; 0 when unknown. */
fun mainChatAt(json: String?): Long = json?.let {
    runCatching { Json.parseToJsonElement(it).jsonObject["at"]?.jsonPrimitive?.longOrNull }.getOrNull()
} ?: 0L

class LibraryFilingStore(private val repo: LibraryRepo) : FilingStore {
    private val songsQ get() = repo.db.songsQueries
    private val misc get() = repo.db.miscQueries

    override fun pref(key: String) = repo.pref(key)
    override fun setPref(key: String, value: String) = repo.setPref(key, value)
    override fun mainChatId() = parseMainChat(pref(MAIN_CHAT_KEY))
    override fun songs(): List<Song> = songsQ.allSongs().executeAsList()
    override fun song(chatId: Long, messageId: Long) = songsQ.song(chatId, messageId).executeAsOneOrNull()
    override fun songByUniqueId(uniqueId: String) = songsQ.songByUniqueId(uniqueId).executeAsOneOrNull()
    override fun postCopies(): List<PostCopy> = repo.db.filingQueries.allPostCopies().executeAsList()
    override fun forumTopics(): List<ForumTopic> = misc.topics().executeAsList()
    override fun chatIds(): List<Long> = misc.chats().executeAsList().map { it.id }
    override fun upsertBotSong(song: Song) { repo.upsertBotSong(song) }
    override fun removePost(chatId: Long, messageId: Long, preferChat: Long?) = repo.removePost(chatId, messageId, preferChat)
    override fun movePostTo(song: Song, messageId: Long, remoteId: String, topicId: Long) =
        repo.movePostTo(song, messageId, remoteId, topicId)
    override fun listAtCopy(song: Song, chatId: Long, messageId: Long, remoteId: String, topicId: Long?) =
        repo.listAtCopy(song, chatId, messageId, remoteId, topicId)

    /** Posts made by an extra bot, which the main bot can't read, so a scan mustn't read their absence as a delete. */
    override fun markExtraBotPost(chatId: Long, messageId: Long) {
        val posts = runCatching {
            (Json.parseToJsonElement(pref(EXTRA_POSTS_KEY) ?: "[]") as JsonArray).mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrDefault(emptyList()).toMutableSet()
        if (posts.add("$chatId:$messageId")) setPref(EXTRA_POSTS_KEY, JsonArray(posts.map(::JsonPrimitive)).toString())
    }
}

