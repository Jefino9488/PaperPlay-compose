package io.max.paperplay.core.account

import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.core.library.LibraryRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeAccount : AccountApi {
    override val step: StateFlow<LoginStep> = MutableStateFlow(LoginStep.Ready)
    override val loggedIn = MutableStateFlow(true)
    val reads = mutableListOf<Pair<Long, Long>>()
    var batches: List<List<ChatPost>> = emptyList()
    val copies = mutableListOf<Triple<Long, Long, Long>>()
    var cloud: ByteArray? = null
    override suspend fun begin() {}
    override suspend fun beginQr() {}
    override suspend fun phone(number: String) {}
    override suspend fun code(code: String) {}
    override suspend fun password(password: String) {}
    override suspend fun logOut() {}
    override suspend fun groupsAndChannels(): List<ChatInfo> = emptyList()
    override fun songsIn(chatId: Long, afterMessageId: Long): Flow<List<ChatPost>> = flow {
        reads += chatId to afterMessageId
        for (b in batches) emit(b.filter { it.messageId > afterMessageId })
    }
    override suspend fun copyPost(fromChat: Long, messageId: Long, toChat: Long) { copies += Triple(fromChat, messageId, toChat) }
    override suspend fun saveToCloud(tag: String, fileName: String, content: ByteArray) { cloud = content }
    override suspend fun loadFromCloud(tag: String): ByteArray? = cloud
}

class ChatIndexTest {
    private val main = -100L
    private var now = 1_000L
    private val account = FakeAccount()
    private val lib = libraryDriver().let { d -> LibraryRepository(LibraryRepo(LibraryDatabase(d)) { ++now }) { ++now } to d }
    private val library get() = lib.first
    private val index = ChatIndex(account, IndexStore(lib.second), library, { main }, { ++now })

    private fun post(msg: Long, uid: String, title: String, artist: String = "A", dur: Int = 200, size: Long = 100) =
        ChatPost(-5, msg, uid, title, artist, dur, size, "audio/mpeg", "$title.mp3", msg)

    @Test
    fun savesBatchesAndLists() = runBlocking {
        account.batches = listOf(listOf(post(30, "u3", "Three"), post(20, "u2", "Two")), listOf(post(10, "u1", "One")))
        index.indexChat(ChatInfo(-5, "Chat", true))
        val songs = index.songs(-5).first()
        assertEquals(listOf(30L, 20L, 10L), songs.map { it.post.messageId })
        assertTrue(songs.none { it.inMainChat || it.copied })
        assertEquals(mapOf(-5L to "Chat"), index.indexedChats.value)
    }

    @Test
    fun refreshReadsOnlyNewer() = runBlocking {
        account.batches = listOf(listOf(post(20, "u2", "Two"), post(10, "u1", "One")))
        index.indexChat(ChatInfo(-5, "Chat", true))
        account.reads.clear()
        account.batches = listOf(listOf(post(40, "u4", "Four"), post(20, "u2", "Two")))
        index.refresh()
        assertEquals(listOf(-5L to 20L), account.reads)
        assertEquals(3, index.songs(-5).first().size)
    }

    @Test
    fun copiedAndInMainAreFlagged() = runBlocking {
        library.upsertBotSong(
            post(1, "u1", "One").asSong().copy(chat_id = main, message_id = 7),
        )
        account.batches = listOf(listOf(post(20, "u2", "Two"), post(10, "u1", "One")))
        index.indexChat(ChatInfo(-5, "Chat", true))
        index.copyToMain(post(20, "u2", "Two"))
        val byId = index.songs(-5).first().associateBy { it.post.uniqueId }
        assertTrue(byId.getValue("u1").inMainChat)
        assertTrue(byId.getValue("u2").copied)
        assertEquals(listOf(Triple(-5L, 20L, main)), account.copies)
    }

    @Test
    fun matchesPreferSureThenCloserLength() = runBlocking {
        account.batches = listOf(listOf(post(30, "a", "Song", dur = 300), post(20, "b", "Song", dur = 201), post(10, "c", "Other")))
        index.indexChat(ChatInfo(-5, "Chat", true))
        val m = index.matchesFor("Song", "A", 200)
        assertEquals(listOf("b", "a"), m.map { it.uniqueId })
    }

    @Test
    fun exportImportAndCloudRoundTrip() = runBlocking {
        account.batches = listOf(listOf(post(20, "u2", "Two")))
        index.indexChat(ChatInfo(-5, "Chat", true))
        index.copyToMain(post(20, "u2", "Two"))
        val cloud = IndexCloud(account, index, library)
        library.setPref(IndexPrefs.AUTO, "true")
        cloud.save()
        assertNotNull(account.cloud)
        index.forget(-5)
        assertTrue(index.indexedChats.value.isEmpty())
        library.setPref(IndexPrefs.AUTO, "false")
        assertTrue(cloud.restore())
        assertEquals("true", library.pref(IndexPrefs.AUTO))
        val songs = index.songs(-5).first()
        assertEquals(1, songs.size)
        assertTrue(songs.single().copied)
        assertFalse(index.indexedChats.value.isEmpty())
    }
}
