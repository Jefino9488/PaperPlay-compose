package io.max.paperplay.core.app

import io.max.paperplay.core.library.LibraryChat
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.TdError
import io.max.paperplay.core.telegram.request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** TDLib message ids are the server id shifted left by 20 bits. */
const val MESSAGE_ID_STEP = 1L shl 20

fun idsFrom(start: Long, count: Int): List<Long> = List(count) { start + it * MESSAGE_ID_STEP }

val audioExtensions = setOf(
    "mp3", "flac", "m4a", "m4b", "mp4", "aac", "ogg", "oga", "opus", "wav", "wma", "alac", "ape", "aiff", "aif",
    "aifc", "wv", "mka", "webm", "dsf", "dff", "mpc", "tta", "tak", "amr", "ac3", "dts", "mp2", "caf", "3gp",
)

private val LEADING_NUMBER = Regex("^\\s*\\d{1,3}\\s*[.\\-)]\\s*")

/** Splits `01. Artist - Title.mp3` into (artist, title); artist may be empty. */
fun parseFileName(fileName: String): Pair<String, String> {
    val dot = fileName.lastIndexOf('.')
    val name = (if (dot > 0) fileName.substring(0, dot) else fileName)
        .replace('_', ' ').replaceFirst(LEADING_NUMBER, "").trim()
    val sep = name.indexOf(" - ")
    if (sep < 0) return "" to name
    return name.substring(0, sep).trim() to name.substring(sep + 3).trim()
}

internal fun JsonObject.obj(k: String) = this[k] as? JsonObject
internal fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
internal val JsonObject.type get() = str("@type")

/** The audio or document payload of a message content, if it holds a song. */
private fun audioPayload(content: JsonObject): JsonObject? = when (content.type) {
    "messageAudio" -> content.obj("audio")
    "messageDocument" -> content.obj("document")?.takeIf { doc ->
        val ext = (doc.str("file_name") ?: "").substringAfterLast('.').lowercase()
        (doc.str("mime_type") ?: "").startsWith("audio/") || ext in audioExtensions
    }
    else -> null
}

/** The downloadable `file` object inside a message, or null when it holds no song. */
internal fun fileOfMessage(message: JsonObject): JsonObject? {
    val payload = audioPayload(message.obj("content") ?: return null) ?: return null
    return payload.obj("audio") ?: payload.obj("document")
}

/** The embedded cover thumbnail `file` inside an audio or document message, if any. */
internal fun thumbFileOfMessage(message: JsonObject): JsonObject? {
    val payload = audioPayload(message.obj("content") ?: return null) ?: return null
    val thumb = payload.obj("album_cover_thumbnail") ?: payload.obj("thumbnail") ?: return null
    return thumb.obj("file")
}

private fun topicIdOf(message: JsonObject): Long? = message.obj("topic_id")?.long("forum_topic_id")

/** A song row for [message] (a TDLib `message` object), or null when it is not audio. */
fun songFromMessage(message: JsonObject): Song? {
    val payload = audioPayload(message.obj("content") ?: return null) ?: return null
    val file = payload.obj("audio") ?: payload.obj("document") ?: return null
    val remote = file.obj("remote") ?: return null
    val fileName = payload.str("file_name") ?: ""
    val (nameArtist, nameTitle) = parseFileName(fileName)
    fun tag(k: String) = payload.str(k)?.trim().orEmpty()
    val title = tag("title").ifEmpty { nameTitle }
    val artist = tag("performer").ifEmpty { nameArtist }
    val size = (file.long("size") ?: 0L).takeIf { it != 0L } ?: file.long("expected_size") ?: 0L
    return Song(
        chat_id = message.long("chat_id") ?: return null,
        message_id = message.long("id") ?: return null,
        unique_id = remote.str("unique_id") ?: return null,
        remote_id = remote.str("id") ?: return null,
        title = title.ifEmpty { "Unknown title" },
        artist = artist.ifEmpty { "Unknown artist" },
        album = "",
        duration_sec = payload.long("duration") ?: 0,
        size = size,
        mime = payload.str("mime_type") ?: "audio/mpeg",
        file_name = fileName,
        date = message.long("date") ?: 0,
        cover_path = null, release_id = null, cover_url = null, genre = null, year = null, metadata_source = null,
        favorite = false, cover_hi_res = false, user_edited = false, enrich_state = 0, lyrics = null,
        cached_bytes = 0, last_played_at = null, pinned = false, tags_at = 0, favorite_at = 0, cover_at = 0,
        instrumental = false, mood_scanned_at = 0, plays = 0, skips = 0, topic_id = topicIdOf(message),
        sample_rate = null, bit_depth = null, codec = null,
    )
}

class ScanProgress(val text: String, val fraction: Double?)

/**
 * Reads the library chats through a bot (bots cannot list history, so message ids are requested in order)
 * and keeps the database current from live updates. Call [attach] once the [TdClient] exists.
 */
class Scanner(
    private val repo: LibraryRepository,
    private val scope: CoroutineScope,
    private val minPause: Duration = 500.milliseconds,
    private val maxPause: Duration = 1.minutes,
) {
    private var td: TdClient? = null
    private var listener: Job? = null
    private var pause = minPause
    private val caughtUp = MutableStateFlow<Set<Long>>(emptySet())
    private val _progress = MutableStateFlow<ScanProgress?>(null)
    private val _error = MutableStateFlow<String?>(null)

    /** Null when idle. */
    val progress: StateFlow<ScanProgress?> = _progress

    /** When on, ids that come back empty before the end are asked for once more (used by the one-time check after a restore). */
    @kotlin.concurrent.Volatile
    var retryEmptyIds = false

    /** How many batches of message ids are asked for at once. Flood waits slow the whole read down on their own. */
    @kotlin.concurrent.Volatile
    var parallelBatches = 2

    @kotlin.concurrent.Volatile
    private var calm = false

    private val _report = MutableStateFlow<String?>(null)

    /** A line about the last finished read of a chat: posts found, new songs, ids that held nothing. */
    val report: StateFlow<String?> = _report

    /** Why the last scan failed; null after a scan that worked. */
    val error: StateFlow<String?> = _error

    fun attach(client: TdClient) {
        td = client
        listener?.cancel()
        listener = scope.launch {
            client.updates.collect { u ->
                runCatching {
                    when (u.type) {
                        "updateNewMessage", "updateMessageSendSucceeded" -> u.obj("message")?.let(::onMessage)
                        "updateDeleteMessages" -> onDelete(u)
                        "updateMessageContent" -> onContent(u)
                        "updateForumTopicInfo" -> onTopicInfo(u)
                    }
                }
            }
        }
    }

    private fun onMessage(message: JsonObject) {
        val chatId = message.long("chat_id") ?: return
        // A pending upload has a temporary id; it arrives again on send success.
        if (message["sending_state"] != null || repo.chat(chatId) == null) return
        // Heard live, so the next start need not read it again; only once this session has read the chat to now.
        if (chatId in caughtUp.value) message.long("id")?.let { repo.advanceScanned(chatId, it) }
        saveTopicName(message)
        songFromMessage(message)?.let { repo.upsertBotSong(it) }
    }

    private fun onDelete(u: JsonObject) {
        if (u["is_permanent"]?.jsonPrimitive?.booleanOrNull != true || u["from_cache"]?.jsonPrimitive?.booleanOrNull == true) return
        val chatId = u.long("chat_id") ?: return
        if (repo.chat(chatId) == null) return
        for (id in u["message_ids"] as? JsonArray ?: return) id.jsonPrimitive.longOrNull?.let { repo.removePost(chatId, it) }
    }

    /** The content changed: re-read the post; if it no longer holds audio the song goes. */
    private suspend fun onContent(u: JsonObject) {
        val chatId = u.long("chat_id") ?: return
        val id = u.long("message_id") ?: return
        if (repo.chat(chatId) == null) return
        val message = td?.call(request("getMessage") { put("chat_id", chatId); put("message_id", id) }) ?: return
        val song = songFromMessage(message)
        if (song == null) repo.removePost(chatId, id) else repo.upsertBotSong(song)
    }

    private fun onTopicInfo(u: JsonObject) {
        val info = u.obj("info")
        val chatId = info?.long("chat_id") ?: u.long("chat_id") ?: return
        val topic = info?.long("forum_topic_id") ?: info?.long("message_thread_id") ?: return
        val name = info?.str("name") ?: return
        if (repo.chat(chatId) != null) repo.saveTopic(chatId, topic, name)
    }

    private fun saveTopicName(message: JsonObject) {
        val content = message.obj("content") ?: return
        val created = content.type == "messageForumTopicCreated"
        if (!created && content.type != "messageForumTopicEdited") return
        val name = content.str("name")?.takeIf { it.isNotEmpty() } ?: return
        val id = (if (created) message.long("id") else topicIdOf(message)) ?: return
        repo.saveTopic(message.long("chat_id") ?: return, id, name)
    }

    /** Reads every library chat from where it was last read. */
    suspend fun catchUp() {
        for (chat in repo.chatList()) scanChat(chat)
    }

    private val _gone = MutableStateFlow<List<Song>>(emptyList())

    /**
     * Songs a full read did not see and Telegram said are not there. A bot cannot see posts another bot made as itself,
     * so "not found" can be wrong; these are kept until the owner confirms with [removeGone].
     */
    val gone: StateFlow<List<Song>> = _gone

    /** Removes the songs in [gone] from the library (not from Telegram). */
    fun removeGone() {
        for (s in _gone.value) repo.removePost(s.chat_id, s.message_id)
        _gone.value = emptyList()
    }

    /**
     * Rescans from scratch. Songs whose message looks gone are listed in [gone] and kept; with [prune] they are removed
     * at once, which is only safe when no other bot posts in the chat.
     */
    suspend fun rescanChat(chat: LibraryChat, prune: Boolean = false): Boolean {
        // A failed scan saw only part of the chat, so it proves nothing was deleted.
        val seen = scanChat(chat, full = true) ?: return false
        val client = td ?: return false
        val missing = mutableListOf<Song>()
        for (s in repo.songsInChat(chat.id)) {
            if (s.message_id in seen || s.remote_id == ACCOUNT_REMOTE) continue
            // The read can come back empty for a post that exists, so each one is asked for by itself. Only
            // Telegram saying it is gone removes the song.
            try {
                val m = client.call(request("getMessage") { put("chat_id", s.chat_id); put("message_id", s.message_id) })
                val song = songFromMessage(m)
                if (song == null) missing += s else repo.upsertBotSong(song)
            } catch (e: TdError) {
                if (e.code == 404 || e.message.contains("not found", ignoreCase = true)) missing += s
                else return false
            }
        }
        if (prune) for (s in missing) repo.removePost(s.chat_id, s.message_id)
        else _gone.value = _gone.value.filter { it.chat_id != chat.id } + missing
        return true
    }

    /**
     * Returns ids of all song messages seen, or null if the scan failed. [endHint] is a message id known
     * to exist, for a first read.
     */
    private fun effectiveMinPause(): Duration {
        val rpm = repo.pref("rate_reading")?.toLongOrNull()?.coerceIn(1L, 1200L)
        val base = if (rpm != null) (60_000L / rpm).milliseconds else minPause
        // After a flood wait the rest of this scan goes one batch at a time with a longer pause.
        return if (calm) maxOf(base, 1500.milliseconds) else base
    }

    suspend fun scanChat(chat: LibraryChat, full: Boolean = false, endHint: Long = 0): Set<Long>? {
        val client = td ?: return null
        val rows = repo.songsInChat(chat.id)
        val last = chat.last_scanned_message_id
        var next = if (full || last == null) MESSAGE_ID_STEP else last + MESSAGE_ID_STEP
        var newest = last ?: 0
        var empties = 0
        var added = 0
        var holes = 0
        var recovered = 0
        val seen = mutableSetOf<Long>()
        calm = false
        val initialFloor = effectiveMinPause()
        if (pause < initialFloor) pause = initialFloor
        var chatTitle = chat.title
        _error.value = null
        try {
            val meta = introduceChat(client, chat.id)
            if (meta.title != null && meta.title != chatTitle) {
                chatTitle = meta.title
                repo.renameChat(chat.id, chatTitle)
            }
            val knownEnd = maxOf(endHint, rows.lastOrNull()?.message_id ?: 0, last ?: 0, meta.lastMessageId)
            val window = ArrayDeque<JsonObject>()
            val clockMark = kotlin.time.TimeSource.Monotonic
            var lastWrite = clockMark.markNow() - 10.seconds
            var lastChatCheck = clockMark.markNow()
            var lastShown = lastWrite
            while (true) {
                // The chat row is looked up again only every 10 s; removal is rare.
                if (lastChatCheck.elapsedNow() > 10.seconds) {
                    lastChatCheck = clockMark.markNow()
                    if (repo.chat(chat.id) == null) return seen // Removed while reading.
                }
                if (window.isEmpty()) {
                    // Several batches are asked for at once; they are used in order below.
                    val starts = List(if (calm) 1 else parallelBatches.coerceIn(1, 6)) { next + it * WALK_BATCH * MESSAGE_ID_STEP }
                    window.addAll(
                        coroutineScope {
                            starts.map { st ->
                                async {
                                    retrying {
                                        client.call(request("getMessages") {
                                            put("chat_id", chat.id)
                                            put("message_ids", JsonArray(idsFrom(st, WALK_BATCH).map(::JsonPrimitive)))
                                        })
                                    }
                                }
                            }.awaitAll()
                        },
                    )
                }
                val page = window.removeFirst()
                val floorPause = effectiveMinPause()
                if (pause > floorPause) pause = maxOf(floorPause, pause * 0.85)
                val raw = (page["messages"] as? JsonArray).orEmpty()
                val requested = idsFrom(next, WALK_BATCH)
                val got = raw.filterIsInstance<JsonObject>().toMutableList()
                // An id that came back empty and lies before the end may still exist: ask for those once more.
                val empty = requested.filterIndexed { i, id -> id <= knownEnd && raw.getOrNull(i) !is JsonObject }
                if (retryEmptyIds && empty.isNotEmpty()) {
                    delay(pause)
                    val again = retrying {
                        client.call(request("getMessages") { put("chat_id", chat.id); put("message_ids", JsonArray(empty.map(::JsonPrimitive))) })
                    }
                    val found = (again["messages"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    got += found
                    recovered += found.size
                }
                val messages: List<JsonObject> = got
                holes += requested.size - messages.size
                // One transaction and one change notice for the whole page.
                repo.repo.db.transaction {
                    for (m in messages) {
                        m.long("id")?.let { newest = maxOf(newest, it) }
                        saveTopicName(m)
                        val song = songFromMessage(m) ?: continue
                        seen += song.message_id
                        if (repo.repo.upsertBotSong(song)) added++
                    }
                }
                repo.invalidate()
                // Saved about once a second, so a closed app continues near here; only real message ids, or later posts could be skipped.
                if (!full) {
                    if (lastWrite.elapsedNow() >= 1.seconds) { repo.setScan(chat.id, newest, chat.walk_done); lastWrite = clockMark.markNow() }
                }
                next += WALK_BATCH * MESSAGE_ID_STEP
                val share = if ((full || !chat.walk_done) && knownEnd > 0 && next <= knownEnd) next.toDouble() / knownEnd else null
                if (lastShown.elapsedNow() >= 1.seconds || _progress.value == null) {
                    lastShown = clockMark.markNow()
                    val counts = "${seen.size} songs read, $added new"
                    _progress.value = ScanProgress(
                        if (share == null) "Checking $chatTitle: $counts"
                        else "Syncing $chatTitle: ${minOf(next, knownEnd) / MESSAGE_ID_STEP} of ${knownEnd / MESSAGE_ID_STEP} messages, $counts",
                        share,
                    )
                }
                val emptyLimit = if (chat.walk_done || (meta.lastMessageId > 0L && next > meta.lastMessageId)) 2 else 6
                if (messages.isNotEmpty() || next <= knownEnd) empties = 0 else if (++empties >= emptyLimit) break
                if (window.isEmpty()) delay(pause)
            }
            repo.setScan(chat.id, newest, true)
            _report.value = "${chat.title}: ${seen.size} song posts read up to message ${newest / MESSAGE_ID_STEP}, $added new, $recovered found on a second ask, $holes ids held nothing"
            if (!full) caughtUp.update { it + chat.id }
            return seen
        } catch (e: TdError) {
            _error.value = "$chatTitle: scan failed, ${e.message}"
            return null
        } finally {
            _progress.value = null
        }
    }

    private data class ChatMeta(val title: String? = null, val lastMessageId: Long = 0L)

    /**
     * A bot's TDLib database starts empty, so a channel is unknown to it ("Chat not found") until it is introduced.
     * The first attempt needs the network, the forced one does not; a forced chat has no access hash, so the channel
     * is then fetched, which is what makes its messages readable. Failures here are not fatal: the read that
     * follows reports what is really wrong (the bot is not in the channel, or offline).
     */
    private suspend fun introduceChat(client: TdClient, chatId: Long): ChatMeta {
        val group = -chatId - 1_000_000_000_000
        if (group > 0) {
            for (force in listOf(false, true)) {
                try {
                    client.call(request("createSupergroupChat") { put("supergroup_id", group); put("force", force) })
                    break
                } catch (_: TdError) {
                    // Offline, or the bot has not seen the channel; the forced attempt needs no network.
                }
            }
            try {
                withTimeout(10.seconds) { client.call(request("getSupergroup") { put("supergroup_id", group) }) }
            } catch (_: TdError) {
                // The bot is not in the channel.
            } catch (_: TimeoutCancellationException) {
                // Offline.
            }
        }
        return try {
            val chat = withTimeout(10.seconds) { client.call(request("getChat") { put("chat_id", chatId) }) }
            ChatMeta(
                title = chat.str("title")?.trim()?.takeIf { it.isNotEmpty() },
                lastMessageId = chat.obj("last_message")?.long("id") ?: 0L,
            )
        } catch (_: TdError) {
            ChatMeta()
        } catch (_: TimeoutCancellationException) {
            ChatMeta()
        }
    }

    /** Waits out Telegram rate limits and tries again. */
    private suspend fun retrying(call: suspend () -> JsonObject): JsonObject {
        while (true) {
            try {
                return call()
            } catch (e: TdError) {
                val wait = e.retryAfter ?: throw e
                calm = true
                pause = minOf(maxPause, pause * 1.5)
                _progress.value = ScanProgress("Telegram rate limit, waiting ${wait}s", null)
                delay(wait.seconds)
            }
        }
    }

    companion object {
        const val WALK_BATCH = 100
    }
}
