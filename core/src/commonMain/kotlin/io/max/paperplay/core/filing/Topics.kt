package io.max.paperplay.core.filing

import io.max.paperplay.core.library.ForumTopic
import io.max.paperplay.core.library.PostCopy
import io.max.paperplay.core.library.Song

/** Telegram's id for a forum's General topic. */
const val GENERAL_TOPIC_ID = 1048576L

/** Key of a topic's name given in the app, in the taste settings. */
fun topicKey(chatId: Long, topicId: Long): String = "topic:$chatId:$topicId"

/** A topic someone can choose, under the name they know it by. */
data class TopicChoice(val id: Long, val name: String) {
    val isGeneral get() = id == GENERAL_TOPIC_ID
}

/**
 * A chat's topics, General first. A name given in the app beats Telegram's. Empty or
 * General alone means the chat has no topics worth choosing between.
 */
fun topicChoices(topics: List<ForumTopic>, chatId: Long, names: Map<String, String>): List<TopicChoice> {
    val known = topics
        .filter { it.chat_id == chatId && it.topic_id != GENERAL_TOPIC_ID }
        .map { TopicChoice(it.topic_id, names[topicKey(chatId, it.topic_id)] ?: it.name) }
        .sortedBy { it.name.lowercase() }
    if (known.isEmpty()) return known
    val general = names[topicKey(chatId, GENERAL_TOPIC_ID)] ?: "General"
    return listOf(TopicChoice(GENERAL_TOPIC_ID, general)) + known
}

/** What the filing code reads and writes in the library. [LibraryFilingStore] is the real one. */
interface FilingStore {
    fun pref(key: String): String?
    fun setPref(key: String, value: String)
    fun mainChatId(): Long?
    fun songs(): List<Song>
    fun song(chatId: Long, messageId: Long): Song?
    fun songByUniqueId(uniqueId: String): Song?
    fun postCopies(): List<PostCopy>
    fun forumTopics(): List<ForumTopic>
    fun chatIds(): List<Long>
    fun upsertBotSong(song: Song)
    fun removePost(chatId: Long, messageId: Long, preferChat: Long? = null)
    fun movePostTo(song: Song, messageId: Long, remoteId: String, topicId: Long)
    fun listAtCopy(song: Song, chatId: Long, messageId: Long, remoteId: String, topicId: Long?)
    fun markExtraBotPost(chatId: Long, messageId: Long)
}
