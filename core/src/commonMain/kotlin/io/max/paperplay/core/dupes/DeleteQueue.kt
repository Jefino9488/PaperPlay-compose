package io.max.paperplay.core.dupes

import io.max.paperplay.core.library.PostCopy
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.TdError
import io.max.paperplay.core.telegram.request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The slice of TdClient the queue uses; tests fake it. */
interface TdCaller {
    suspend fun call(request: JsonObject, timeout: Duration = 60.seconds): JsonObject
}

fun TdClient.asCaller() = object : TdCaller {
    override suspend fun call(request: JsonObject, timeout: Duration) = this@asCaller.call(request, timeout)
}

/** The owner's own account, the last resort for posts the bot is refused. */
interface AccountDeleter {
    val isLoggedIn: Boolean

    /** Deletes for everyone, up to 100 to a call. Returns the ids Telegram still refused (all if not logged in). */
    suspend fun deleteBatch(chatId: Long, ids: List<Long>): List<Long>
}

/** The library database and deletion record a delete touches. The owner wires it to LibraryRepo and sync. */
interface DeleteStore {
    suspend fun postCopiesOf(uniqueId: String): List<PostCopy>

    /** A post is gone from Telegram: a copy takes its place in the library, or the song leaves. */
    suspend fun removePost(chatId: Long, messageId: Long)

    /** Hands [from]'s playlists, favorite, hand-picked cover and hand-edited tags to [to], before [from] is deleted. */
    suspend fun moveReferences(from: Song, to: Song)
    suspend fun markSongGone(uniqueId: String)
    suspend fun markSongKeyGone(key: String)
    fun announceChange()
}

/** A deleted song's identity as a key in the synced deletion record, since other versions would be copied back in. */
fun goneKeyOf(s: Song, rules: SongRules): String =
    "song:${rules.comparableTitle(s)}\u0001${rules.comparableArtist(s)}\u0001${s.duration_sec}"

private const val BATCH = 100
private val ASK_TIMEOUT = 30.seconds

/**
 * Bulk deletes of songs and repeated posts, observable by the Tasks and duplicates screens. Runs on
 * [scope], so it carries on when a screen is left. Use a single-threaded scope (Main), as callers are.
 *
 * Posts are deleted through the bot, then Telegram's HTTP Bot API (TDLib can judge from stale
 * rights), then the owner's account if logged in (bots can't delete old posts).
 */
class DeleteQueue(
    private val scope: CoroutineScope,
    private val td: TdCaller,
    private val bot: BotDeleter,
    private val account: AccountDeleter,
    private val store: DeleteStore,
    private val rules: SongRules,
    private val mainChat: () -> Long?,
    /** Called before a song is deleted, so a player isn't left reading a file that's gone. */
    private val beforeDelete: suspend (Song) -> Unit = {},
    private val pause: suspend (millis: Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    private val _jobs = MutableStateFlow<List<DeleteJob>>(emptyList())

    /** Songs and posts being deleted, for the Tasks page. Cleared when a new run starts. */
    val jobs: StateFlow<List<DeleteJob>> = _jobs

    private class Pending(val post: DuplicatePost, val keep: DuplicatePost, val key: String)

    private val pending = MutableStateFlow<List<Pending>>(emptyList())
    private var pump: Job? = null

    @kotlin.concurrent.Volatile
    private var stop = false

    /** Stops a bulk delete after the song being deleted now; what hasn't started leaves the list. */
    fun cancel() {
        stop = true
    }

    /** Waits for queued duplicate deletes to finish. */
    suspend fun awaitIdle() {
        pump?.join()
    }

    private fun set(key: String, status: DeleteStatus, error: String? = null) =
        _jobs.update { l -> l.map { if (it.key == key) it.copy(status = status, error = error) else it } }

    /** Permanently deletes a song from Telegram: its post and any repeats of the file. Returns how many posts went. */
    suspend fun deleteSong(s: Song): Int {
        // Only the main chat is ever changed in Telegram, so without one nothing could be deleted there.
        val main = mainChat() ?: throw TdError(0, "Choose a main chat in Settings first; songs are deleted from it.")
        val copies = store.postCopiesOf(s.unique_id)
        beforeDelete(s)
        val byChat = linkedMapOf(s.chat_id to mutableListOf(s.message_id))
        for (c in copies) byChat.getOrPut(c.chat_id) { mutableListOf() }.add(c.message_id)
        // Posts in other chats stay in Telegram and only leave the library.
        for ((chat, ids) in byChat) if (chat == main) deletePosts(chat, ids)
        for (c in copies) store.removePost(c.chat_id, c.message_id)
        store.removePost(s.chat_id, s.message_id)
        store.markSongGone(s.unique_id)
        store.markSongKeyGone(goneKeyOf(s, rules))
        store.announceChange()
        return copies.size + 1
    }

    /** Deletes [songs] one by one, recording progress in [jobs]. Returns how many went. */
    suspend fun deleteSongs(songs: List<Song>): Int {
        val all = songs.map { DeleteJob(it) }
        _jobs.value = all
        stop = false
        var done = 0
        for (j in all) {
            if (stop) {
                _jobs.update { l -> l.filter { it.key != j.key } }
                continue
            }
            set(j.key, DeleteStatus.Deleting)
            try {
                deleteSong(j.song)
                set(j.key, DeleteStatus.Done)
                done++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                set(j.key, DeleteStatus.Failed, e.message ?: e.toString())
            }
        }
        return done
    }

    /**
     * Queues repeated posts for deletion after the owner confirmed them: (post to delete, post to keep).
     * Each post goes after what points at it moves to its keeper, in batches of up to 100.
     */
    fun deleteDuplicates(pairs: List<Pair<DuplicatePost, DuplicatePost>>) {
        val running = pump?.isActive == true
        if (!running && _jobs.value.none { it.status == DeleteStatus.Queued || it.status == DeleteStatus.Deleting }) {
            _jobs.value = emptyList()
        }
        val added = mutableListOf<DeleteJob>()
        val entries = mutableListOf<Pending>()
        for ((post, keep) in pairs) {
            val song = post.song ?: keep.song ?: continue
            val job = DeleteJob(song, post.chatId, post.messageId, keep.chatId, keep.messageId)
            entries += Pending(post, keep, job.key)
            added += job
        }
        _jobs.update { it + added }
        pending.update { it + entries }
        stop = false
        if (!running) pump = scope.launch { pumpDuplicates() }
    }

    private suspend fun pumpDuplicates() {
        while (pending.value.isNotEmpty()) {
            if (stop) {
                val dropped = pending.getAndUpdate { emptyList() }.map { it.key }.toSet()
                _jobs.update { l -> l.filter { it.key !in dropped } }
                break
            }
            val batch = pending.getAndUpdate { it.drop(BATCH) }.take(BATCH)
            for (e in batch) set(e.key, DeleteStatus.Deleting)
            deleteDuplicateBatch(batch)
        }
    }

    private suspend fun idsExist(chat: Long, ids: List<Long>): JsonArray? = try {
        td.call(
            request("getMessages") {
                put("chat_id", chat)
                put("message_ids", JsonArray(ids.map { JsonPrimitive(it) }))
            },
            ASK_TIMEOUT,
        )["messages"]?.jsonArray
    } catch (_: TdError) {
        null // Not reachable by this bot, or offline: nothing is concluded.
    }

    private fun byChat(posts: Collection<Pair<Long, Long>>) =
        posts.groupBy({ it.first }, { it.second }).mapValues { it.value.chunked(BATCH) }

    /** Which of [posts] still exist. A batch that can't be answered counts as not existing, so nothing is added on a guess. */
    private suspend fun postsExist(posts: Collection<Pair<Long, Long>>): Set<Pair<Long, Long>> {
        val alive = mutableSetOf<Pair<Long, Long>>()
        for ((chat, chunks) in byChat(posts)) for (ids in chunks) {
            val found = idsExist(chat, ids) ?: continue
            for (k in ids.indices) if (k < found.size && found[k] !is JsonNull) alive += chat to ids[k]
        }
        return alive
    }

    /** Which of [posts] Telegram says no longer exist, asked 100 at a time. Only a clear answer counts. */
    suspend fun missingPosts(posts: Collection<Pair<Long, Long>>): Set<Pair<Long, Long>> {
        val gone = mutableSetOf<Pair<Long, Long>>()
        for ((chat, chunks) in byChat(posts)) for (ids in chunks) {
            val found = idsExist(chat, ids) ?: continue
            if (found.size != ids.size) continue
            for (k in ids.indices) if (found[k] is JsonNull) gone += chat to ids[k]
        }
        return gone
    }

    /** Drops posts Telegram no longer has from the library, so they stop showing as repeats. */
    suspend fun dropGonePosts(posts: Collection<Pair<Long, Long>>): Int {
        val gone = missingPosts(posts)
        for ((chat, id) in gone) store.removePost(chat, id)
        if (gone.isNotEmpty()) {
            log("${gone.size} listed post${if (gone.size == 1) " was" else "s were"} already gone from Telegram and left the library")
            store.announceChange()
        }
        return gone.size
    }

    /** True if the post exists, false if Telegram says it doesn't, null if it couldn't be asked. Waits out rate limits. */
    private suspend fun postState(chat: Long, message: Long): Boolean? {
        repeat(4) {
            try {
                td.call(request("getMessage") { put("chat_id", chat); put("message_id", message) }, ASK_TIMEOUT)
                return true
            } catch (e: TdError) {
                val wait = e.retryAfter
                if (wait != null) {
                    pause((wait + 1) * 1000L)
                    return@repeat
                }
                return if (e.code == 404 || e.code == 400) false else null
            }
        }
        return null
    }

    private suspend fun deleteDuplicateBatch(batch: List<Pending>) {
        val main = mainChat()
        // This device may not have heard that a kept post was moved or deleted; the only live post
        // of a song is never deleted as a "copy". One question per 100 keepers.
        val keepers = batch.map { it.keep.chatId to it.keep.messageId }.toSet()
        val alive = postsExist(keepers).toMutableSet()
        // A batch that couldn't be answered also looks like "not there", so what wasn't confirmed is
        // asked about singly, waiting out a rate limit, before it is blamed.
        val unsure = mutableSetOf<Pair<Long, Long>>()
        for (k in keepers) {
            if (k in alive) continue
            when (postState(k.first, k.second)) {
                true -> alive += k
                null -> unsure += k
                false -> {}
            }
        }
        val ok = mutableListOf<Pending>()
        for (e in batch) {
            val keepKey = e.keep.chatId to e.keep.messageId
            when {
                e.post.chatId != main -> set(e.key, DeleteStatus.Failed, "That post isn't in the main chat.")
                keepKey in unsure ->
                    set(e.key, DeleteStatus.Failed, "Couldn't check the post to keep (Telegram is busy). Nothing was deleted; try again.")
                keepKey !in alive -> {
                    // The copy meant to stay no longer exists, so this one is now the only one: it stays,
                    // and the dead post leaves the library so the song stops showing as a repeat.
                    store.removePost(e.keep.chatId, e.keep.messageId)
                    log("The post to keep was already gone from Telegram; the other copy stays")
                    set(e.key, DeleteStatus.Skipped, "The other copy was already gone, so this one stays")
                }
                else -> ok += e
            }
        }
        for ((chat, entries) in ok.groupBy { it.post.chatId }) {
            for (e in entries) {
                val loser = e.post.song
                val keeper = e.keep.song
                if (loser != null && keeper != null) store.moveReferences(loser, keeper)
            }
            deleteChatPosts(chat, entries)
        }
        store.announceChange()
    }

    private suspend fun deleteChatPosts(chat: Long, entries: List<Pending>) {
        var retries = 0
        while (true) {
            try {
                val left = deletePostsBest(chat, entries.map { it.post.messageId }).toSet()
                for (e in entries) {
                    if (e.post.messageId in left) {
                        set(
                            e.key, DeleteStatus.Failed,
                            if (account.isLoggedIn) "Telegram wouldn't let this post be deleted."
                            else "Too old for the bot. Log in your account in Settings, Library.",
                        )
                    } else {
                        store.removePost(e.post.chatId, e.post.messageId)
                        set(e.key, DeleteStatus.Done)
                    }
                }
                return
            } catch (e: TdError) {
                val wait = e.retryAfter
                if (wait != null && retries++ < 5) {
                    pause((wait + 1) * 1000L)
                    continue
                }
                for (p in entries) set(p.key, DeleteStatus.Failed, e.message)
                return
            }
        }
    }

    private suspend fun deletePosts(chat: Long, ids: List<Long>) {
        if (deletePostsBest(chat, ids).isEmpty()) return
        throw TdError(
            400,
            if (account.isLoggedIn) "Telegram wouldn't let this post be deleted."
            else "The bot can't delete posts older than 48 hours. Log in your account in Settings, Library, to allow it.",
        )
    }

    /**
     * Deletes as many of [ids] as it can, in calls of up to 100 at each step, and returns the ones
     * nobody could remove. Bot first, then the Bot API, then the owner's account. A 429 from the bot is thrown.
     */
    private suspend fun deletePostsBest(chat: Long, ids: List<Long>): List<Long> {
        try {
            td.call(
                request("deleteMessages") {
                    put("chat_id", chat)
                    put("message_ids", JsonArray(ids.map { JsonPrimitive(it) }))
                    put("revoke", true)
                },
            )
            return emptyList()
        } catch (e: TdError) {
            if (e.code == 429) throw e
        }
        // TDLib refuses the whole call if one post can't go. Ask Telegram directly, which skips those it can't delete.
        for (part in ids.chunked(BATCH)) bot.deleteMessages(chat, part)
        val alive = postsExist(ids.map { chat to it })
        val left = ids.filter { chat to it in alive }
        log(
            "Bot refused ${ids.size}; the Bot API removed ${ids.size - left.size}, ${left.size} left" + when {
                left.isEmpty() -> ""
                account.isLoggedIn -> ", asking your account"
                else -> ", your account is not logged in"
            },
        )
        if (left.isEmpty()) return emptyList()
        val rest = account.deleteBatch(chat, left)
        log("Your account removed ${left.size - rest.size} of ${left.size}")
        return rest
    }
}
