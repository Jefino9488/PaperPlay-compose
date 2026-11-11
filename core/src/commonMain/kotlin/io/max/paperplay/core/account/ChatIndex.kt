package io.max.paperplay.core.account

import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.integrations.MatchKind
import io.max.paperplay.core.integrations.looseTitleKey
import io.max.paperplay.core.integrations.matchKind
import io.max.paperplay.core.library.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Library prefs of the index (read and written through [LibraryRepository.pref]):
 * - `index_auto`: "true" refreshes the index on start and every few hours. Default off.
 * - `index_auto_copy`: "true" copies songs found in the index into the main chat on their own. Default off.
 */
object IndexPrefs {
    const val AUTO = "index_auto"
    const val AUTO_COPY = "index_auto_copy"
    fun auto(library: LibraryRepository) = library.pref(AUTO) == "true"
    fun autoCopy(library: LibraryRepository) = library.pref(AUTO_COPY) == "true"
}

/** A [Song] row built from a post, so the shared matching rules can be applied to it. */
internal fun ChatPost.asSong(): Song = Song(
    chat_id = chatId, message_id = messageId, unique_id = uniqueId, remote_id = "", title = title, artist = artist, album = "",
    duration_sec = durationSec.toLong(), size = size, mime = mime, file_name = fileName, date = date,
    cover_path = null, release_id = null, cover_url = null, genre = null, year = null, metadata_source = null,
    favorite = false, cover_hi_res = false, user_edited = false, enrich_state = 0, lyrics = null,
    cached_bytes = 0, last_played_at = null, pinned = false, tags_at = 0, favorite_at = 0, cover_at = 0,
    instrumental = false, mood_scanned_at = 0, plays = 0, skips = 0, topic_id = null,
    sample_rate = null, bit_depth = null, codec = null,
)

/**
 * The saved index of the account's chats. Posts are written as they arrive, so screens and copying work while a
 * read is still going on. A refresh reads only what is newer than the newest post saved.
 */
class ChatIndex(
    private val account: AccountApi,
    private val store: IndexStore,
    private val library: LibraryRepository,
    private val mainChat: () -> Long?,
    private val clock: () -> Long,
) : ChatIndexApi {
    private val _chats = MutableStateFlow(store.chats().associate { it.id to it.title })
    private val _progress = MutableStateFlow<IndexProgress?>(null)
    private val runLock = Mutex()

    /** Called after a read finished, for example to save the index to Telegram. */
    var onRunFinished: (suspend () -> Unit)? = null

    override val indexedChats: StateFlow<Map<Long, String>> = _chats
    override val progress: StateFlow<IndexProgress?> = _progress

    override suspend fun indexChat(chat: ChatInfo) {
        runLock.withLock {
            store.saveChat(chat.id, chat.title)
            _chats.value = store.chats().associate { it.id to it.title }
            read(chat.id, chat.title, after = 0, complete = true)
        }
        onRunFinished?.invoke()
    }

    override suspend fun refresh() {
        if (!account.loggedIn.value) return
        runLock.withLock {
            for (c in store.chats()) {
                // A chat whose first read never finished is read again from the start; the rest only for newer posts.
                if (c.complete) read(c.id, c.title, after = c.newestMessageId, complete = true)
                else read(c.id, c.title, after = 0, complete = true)
            }
        }
        onRunFinished?.invoke()
    }

    private suspend fun read(chatId: Long, title: String, after: Long, complete: Boolean) {
        var found = 0
        var newest = 0L
        _progress.value = IndexProgress(chatId, title, 0, true)
        try {
            account.songsIn(chatId, after).collect { batch ->
                if (batch.isEmpty()) return@collect
                store.upsertPosts(batch)
                found += batch.size
                newest = maxOf(newest, batch.maxOf { it.messageId })
                // Newest is saved at once but the chat only counts as complete when the whole read ends.
                store.setState(chatId, newest, clock(), complete = after > 0 && complete)
                _progress.value = IndexProgress(chatId, title, found, true)
            }
            store.setState(chatId, newest, clock(), complete)
        } finally {
            _progress.value = null
        }
    }

    private class Main(val ids: Set<String>, val byKey: Map<String, List<Song>>) {
        fun has(p: ChatPost): Boolean = p.uniqueId in ids ||
            byKey[looseTitleKey(p.title, p.artist)].orEmpty().any { matchKind(it, p.title, p.artist, p.durationSec) == MatchKind.Sure }
    }

    private fun main(): Main? {
        val m = mainChat() ?: return null
        val songs = library.songsInChat(m)
        val copies = library.repo.db.filingQueries.allPostCopies().executeAsList().filter { it.chat_id == m }
        return Main(songs.map { it.unique_id }.toSet() + copies.map { it.unique_id }, songs.groupBy { looseTitleKey(it.title, it.artist) })
    }

    override fun songs(chatId: Long): Flow<List<IndexedSong>> =
        combine(store.changes, library.changes) { _, _ ->
            val main = main()
            val copied = store.copiedIds()
            store.posts(chatId).map { IndexedSong(it, inMainChat = main?.has(it) == true, copied = it.uniqueId in copied) }
        }.flowOn(Dispatchers.Default)

    private var keyed: Pair<Long, Map<String, List<ChatPost>>>? = null

    private fun byKey(): Map<String, List<ChatPost>> {
        val stamp = store.changes.value
        keyed?.takeIf { it.first == stamp }?.let { return it.second }
        return store.allPosts().groupBy { looseTitleKey(it.title, it.artist) }.also { keyed = stamp to it }
    }

    override suspend fun matchesFor(title: String, artist: String, durationSec: Int): List<ChatPost> {
        val key = looseTitleKey(title, artist)
        if (key.isEmpty()) return emptyList()
        val main = main()
        val copied = store.copiedIds()
        return byKey()[key].orEmpty()
            .mapNotNull { p -> matchKind(p.asSong(), title, artist, durationSec)?.let { p to it } }
            .filter { (p, _) -> p.uniqueId !in copied && main?.has(p) != true }
            .sortedWith(compareBy({ it.second != MatchKind.Sure }, { abs(it.first.durationSec - durationSec) }, { -it.first.size }))
            .map { it.first }
    }

    override suspend fun copyToMain(post: ChatPost) {
        val main = mainChat() ?: throw IllegalStateException("No main chat is set.")
        account.copyPost(post.chatId, post.messageId, main)
        store.markCopied(post, clock())
    }

    override fun forget(chatId: Long) {
        store.deleteChat(chatId)
        _chats.value = store.chats().associate { it.id to it.title }
    }

    override suspend fun export(): String = buildJsonObject {
        put("version", 1)
        put("chats", buildJsonArray {
            for (c in store.chats()) add(buildJsonObject {
                put("id", c.id); put("title", c.title); put("newest", c.newestMessageId); put("refreshed", c.lastRefreshAt); put("complete", c.complete)
            })
        })
        put("posts", buildJsonArray {
            for (p in store.allPosts()) add(buildJsonArray {
                add(JsonPrimitive(p.chatId)); add(JsonPrimitive(p.messageId)); add(JsonPrimitive(p.uniqueId)); add(JsonPrimitive(p.title))
                add(JsonPrimitive(p.artist)); add(JsonPrimitive(p.durationSec)); add(JsonPrimitive(p.size)); add(JsonPrimitive(p.mime))
                add(JsonPrimitive(p.fileName)); add(JsonPrimitive(p.date))
            })
        })
        put("copied", buildJsonArray {
            for (c in store.copiedRows()) add(buildJsonArray {
                add(JsonPrimitive(c.uniqueId)); add(JsonPrimitive(c.chatId)); add(JsonPrimitive(c.messageId)); add(JsonPrimitive(c.at))
            })
        })
    }.toString()

    /** Merges an exported index in. Saved posts stay; the newer position and a finished read win. Safe to repeat. */
    override suspend fun import(json: String) {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return
        val mine = store.chats().associateBy { it.id }
        for (c in root["chats"] as? JsonArray ?: JsonArray(emptyList())) {
            val o = c as? JsonObject ?: continue
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: continue
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: continue
            store.saveChat(id, title)
            val had = mine[id]
            val newest = maxOf((o["newest"] as? JsonPrimitive)?.longOrNull ?: 0L, had?.newestMessageId ?: 0L)
            val complete = (o["complete"] as? JsonPrimitive)?.contentOrNull == "true" || had?.complete == true
            store.setState(id, newest, maxOf((o["refreshed"] as? JsonPrimitive)?.longOrNull ?: 0L, had?.lastRefreshAt ?: 0L), complete)
        }
        val posts = (root["posts"] as? JsonArray).orEmpty().mapNotNull { e ->
            val a = e as? JsonArray ?: return@mapNotNull null
            runCatching {
                fun s(i: Int) = (a[i] as JsonPrimitive).content
                fun n(i: Int) = s(i).toLong()
                ChatPost(n(0), n(1), s(2), s(3), s(4), n(5).toInt(), n(6), s(7), s(8), n(9))
            }.getOrNull()
        }
        if (posts.isNotEmpty()) store.upsertPosts(posts)
        for (e in (root["copied"] as? JsonArray).orEmpty()) {
            val a = e as? JsonArray ?: continue
            runCatching {
                store.markCopied(ChatPost((a[1] as JsonPrimitive).content.toLong(), (a[2] as JsonPrimitive).content.toLong(), (a[0] as JsonPrimitive).content, "", "", 0, 0, "", "", 0), (a[3] as JsonPrimitive).content.toLong())
            }
        }
        _chats.value = store.chats().associate { it.id to it.title }
    }
}

/**
 * Saves the index to the account's Saved Messages and brings it back, so a later login, logout or reinstall can pick
 * it up. Only titles, artists and ids are stored: never tokens or the session.
 */
class IndexCloud(private val account: AccountApi, private val index: ChatIndexApi, private val library: LibraryRepository) {
    private val tag = "paperplay-index-v1"

    suspend fun save() {
        if (!account.loggedIn.value) return
        val body = buildJsonObject {
            put("index", Json.parseToJsonElement(index.export()))
            put("prefs", buildJsonObject {
                put(IndexPrefs.AUTO, library.pref(IndexPrefs.AUTO) ?: "false")
                put(IndexPrefs.AUTO_COPY, library.pref(IndexPrefs.AUTO_COPY) ?: "false")
            })
        }.toString()
        account.saveToCloud(tag, "paperplay-index.json", body.encodeToByteArray())
    }

    /** True when a snapshot was found and merged. */
    suspend fun restore(): Boolean {
        val bytes = account.loadFromCloud(tag) ?: return false
        val root = runCatching { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull() ?: return false
        val data = root["index"] ?: return false
        index.import(data.toString())
        (root["prefs"] as? JsonObject)?.forEach { (k, v) ->
            if (k == IndexPrefs.AUTO || k == IndexPrefs.AUTO_COPY) (v as? JsonPrimitive)?.contentOrNull?.let { library.setPref(k, it) }
        }
        return true
    }
}
