package io.max.paperplay.core.account

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Stand-ins until the real account and index are wired; they do nothing. */
class NoAccount : AccountApi {
    override val step: StateFlow<LoginStep> = MutableStateFlow(LoginStep.LoggedOut)
    override val loggedIn: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun begin() {}
    override suspend fun beginQr() {}
    override suspend fun phone(number: String) {}
    override suspend fun code(code: String) {}
    override suspend fun password(password: String) {}
    override suspend fun logOut() {}
    override suspend fun groupsAndChannels(): List<ChatInfo> = emptyList()
    override fun songsIn(chatId: Long, afterMessageId: Long): Flow<List<ChatPost>> = emptyFlow()
    override suspend fun copyPost(fromChat: Long, messageId: Long, toChat: Long) {}
    override suspend fun saveToCloud(tag: String, fileName: String, content: ByteArray) {}
    override suspend fun loadFromCloud(tag: String): ByteArray? = null
}

class NoIndex : ChatIndexApi {
    override val indexedChats: StateFlow<Map<Long, String>> = MutableStateFlow(emptyMap())
    override val progress: StateFlow<IndexProgress?> = MutableStateFlow(null)
    override suspend fun indexChat(chat: ChatInfo) {}
    override suspend fun refresh() {}
    override fun songs(chatId: Long): Flow<List<IndexedSong>> = emptyFlow()
    override suspend fun matchesFor(title: String, artist: String, durationSec: Int): List<ChatPost> = emptyList()
    override suspend fun copyToMain(post: ChatPost) {}
    override fun forget(chatId: Long) {}
    override suspend fun export(): String = "{}"
    override suspend fun import(json: String) {}
}
