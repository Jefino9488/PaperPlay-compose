package io.max.paperplay.core.account

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Shared contracts for the own-account login, the chat index and the sync that uses it.
 * Each piece is built apart; they meet only through these types. Do not change a signature
 * here without telling the lead: add what you need in your own files.
 */

/** A group or channel the logged-in account is in. */
data class ChatInfo(val id: Long, val title: String, val isChannel: Boolean)

/** A song post found in one of the account's chats. [messageId] is TDLib's id. */
data class ChatPost(
    val chatId: Long,
    val messageId: Long,
    val uniqueId: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val size: Long,
    val mime: String,
    val fileName: String,
    val date: Long,
)

/** Where the login is. [Qr] carries the tg://login link to show as a code. */
sealed interface LoginStep {
    data object LoggedOut : LoginStep
    data object Starting : LoginStep
    data object Phone : LoginStep
    data class Qr(val link: String) : LoginStep
    data object Code : LoginStep
    data object Password : LoginStep
    data object Ready : LoginStep
    data class Failed(val message: String) : LoginStep
}

/** The owner's own Telegram account: login, chat listing, reading songs, copying. Built on its own TDLib client. */
interface AccountApi {
    val step: StateFlow<LoginStep>
    val loggedIn: StateFlow<Boolean>

    /** Starts the login (phone number flow). The flow then waits at [LoginStep.Phone]. */
    suspend fun begin()

    /** Starts the QR flow: [LoginStep.Qr] follows, then [LoginStep.Ready] once another device approves. */
    suspend fun beginQr()
    suspend fun phone(number: String)
    suspend fun code(code: String)
    suspend fun password(password: String)

    /** Logs out and forgets the session. The library and the index stay. */
    suspend fun logOut()

    /** Groups and channels, most recent first. Empty when not logged in. */
    suspend fun groupsAndChannels(): List<ChatInfo>

    /**
     * Song posts of [chatId], newest first, in batches as they are found, so a screen can show them while the
     * read goes on. With [afterMessageId] > 0 only posts newer than that id are read, and the flow ends there.
     */
    fun songsIn(chatId: Long, afterMessageId: Long = 0): Flow<List<ChatPost>>

    /** Copies one post into [toChat] without a "forwarded" label. */
    suspend fun copyPost(fromChat: Long, messageId: Long, toChat: Long)

    /** Sends [content] as a document named [fileName] to the account's Saved Messages, with [tag] in its caption. */
    suspend fun saveToCloud(tag: String, fileName: String, content: ByteArray)

    /** The newest document in Saved Messages whose caption holds [tag], or null. */
    suspend fun loadFromCloud(tag: String): ByteArray?
}

/** One indexed post and what it means for the library. */
data class IndexedSong(
    val post: ChatPost,
    /** The unique id or message of the same song already in the main chat, when there is one. */
    val inMainChat: Boolean,
    /** The user moved (copied) it from here before. */
    val copied: Boolean,
    /** The main chat already has this song, but this file is better quality. */
    val better: Boolean = false,
    /** An imported Spotify playlist still waits for this song. */
    val wanted: Boolean = false,
)

/** Progress of an index run, for screens and the notification. */
data class IndexProgress(val chatId: Long, val chatTitle: String, val found: Int, val running: Boolean)

/** The saved index of the account's chats. Persisted, so it survives restarts and logouts. */
interface ChatIndexApi {
    /** Chats that are indexed: id to title. */
    val indexedChats: StateFlow<Map<Long, String>>
    val progress: StateFlow<IndexProgress?>

    /** Reads a chat once from the start and saves what it finds; posts show up in [songs] as they are saved. */
    suspend fun indexChat(chat: ChatInfo)

    /** Reads only posts newer than the last one indexed for each indexed chat. */
    suspend fun refresh()

    /** Songs of [chatId] in the index, updating as the index grows. */
    fun songs(chatId: Long): Flow<List<IndexedSong>>

    /** Possible matches in the index for a wanted track (title, artist, length), best first. */
    suspend fun matchesFor(title: String, artist: String, durationSec: Int): List<ChatPost>

    /** Copies [post] to the main chat and remembers it. */
    suspend fun copyToMain(post: ChatPost)

    fun forget(chatId: Long)

    /** Adds [post] to the library so it can be played through the owner's account, with no copy in Telegram. */
    suspend fun addToLibrary(post: ChatPost) {}

    /** Adds every indexed song of the main chat that the library lacks. Returns how many were added. */
    suspend fun addMissingFromMainChat(): Int = 0

    /** The index as text, for saving to Telegram; and the way back. */
    suspend fun export(): String
    suspend fun import(json: String)
}
