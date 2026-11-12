package io.max.paperplay.core.filing

import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.TdError
import io.max.paperplay.core.telegram.request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

enum class MoveStatus { Queued, Moving, Done, Skipped }

class MoveJob(val song: Song, val topicId: Long, val topicName: String, val status: MoveStatus = MoveStatus.Queued, val note: String? = null) {
    val key get() = song.chat_id to song.message_id
    fun with(status: MoveStatus, note: String? = null) = MoveJob(song, topicId, topicName, status, note)
}

private fun JsonObject.obj(k: String) = this[k] as? JsonObject
private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

/** A move that did not happen, with why. Thrown inside the mover only. */
private class Skip(message: String) : Exception(message)

/**
 * Moves songs out of General. Telegram cannot move a post, so each song is posted again in its new topic
 * from the same remote file (no re-upload) and the old post is deleted. A failed repost keeps the original
 * and stops the queue; a refused delete leaves the song moved. Slow on purpose to stay under rate limits.
 */
class TopicMover(
    private val scope: CoroutineScope,
    private val client: () -> TdClient?,
    private val repo: LibraryRepo,
    /** (chat, message) of the song playing now, which is left in General. */
    private val playingNow: () -> Pair<Long, Long>? = { null },
    /** A sentence saying why the bot cannot post and delete in the chat, or null when it can. */
    private val moveProblem: suspend (Long) -> String? = { null },
) {
    private val checkedChats = HashSet<Long>()
    private val _jobs = MutableStateFlow<List<MoveJob>>(emptyList())
    val jobs: StateFlow<List<MoveJob>> = _jobs
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem
    private var running = false
    private var cancelled = false

    val active get() = running

    fun start(moves: List<MoveJob>) {
        val live = setOf(MoveStatus.Queued, MoveStatus.Moving)
        val known = _jobs.value.filter { it.status in live }.map { it.key }.toSet()
        val fresh = moves.filter { it.key !in known }
        _jobs.update { old -> old.filter { it.status in live } + fresh }
        cancelled = false
        _problem.value = null
        _paused.value = false
        checkedChats.clear()
        if (running) return
        running = true
        scope.launch { try { run() } finally { running = false } }
    }

    fun setPaused(on: Boolean) {
        _paused.value = on
        if (!on) _problem.value = null
    }

    fun cancel() { cancelled = true; _paused.value = false }

    private fun set(from: MoveJob, to: MoveJob) = _jobs.update { l -> l.map { if (it === from) to else it } }

    private suspend fun run() {
        var first = true
        while (true) {
            if (cancelled) { _jobs.update { l -> l.filter { it.status != MoveStatus.Queued } }; return }
            if (_paused.value) { delay(500); continue }
            val job = _jobs.value.firstOrNull { it.status == MoveStatus.Queued } ?: return
            if (!first) delay(Random.nextLong(6000, 12000))
            first = false
            val moving = job.with(MoveStatus.Moving)
            set(job, moving)
            try {
                val c = client() ?: throw TdError(0, "The bot is not connected.")
                set(moving, moving.with(MoveStatus.Done, moveOne(c, moving)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Skip) {
                set(moving, moving.with(MoveStatus.Skipped, e.message))
            } catch (e: Throwable) {
                set(moving, moving.with(MoveStatus.Queued))
                val wait = (e as? TdError)?.retryAfter
                if (wait != null) {
                    delay((wait + 1) * 1000L)
                } else {
                    _problem.value = e.message
                    _paused.value = true
                }
            }
        }
    }

    /** Null when moved and the old post is gone; a note when the old post could not be deleted. */
    private suspend fun moveOne(c: TdClient, job: MoveJob): String? {
        val now = repo.db.songsQueries.song(job.song.chat_id, job.song.message_id).executeAsOneOrNull()
            ?: throw Skip("The song is no longer in the library")
        if (now.topic_id != null && now.topic_id != GENERAL_TOPIC_ID) throw Skip("Already in a topic")
        if (playingNow() == now.chat_id to now.message_id) throw Skip("Playing now, left in General")
        if (repo.db.miscQueries.postCopiesOf(now.unique_id).executeAsList().any { it.topic_id != null && it.topic_id != GENERAL_TOPIC_ID }) {
            throw Skip("Also posted in another topic")
        }
        if (now.chat_id !in checkedChats) {
            moveProblem(now.chat_id)?.let { throw Skip(it) }
            checkedChats += now.chat_id
        }
        val sentId = CompletableDeferred<Long>()
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) {
            c.updates.first { u ->
                val t = u["@type"]?.jsonPrimitive?.content
                (t == "updateMessageSendSucceeded" || t == "updateMessageSendFailed") && u.long("old_message_id") == sentId.await()
            }
        }
        try {
            val asDocument = now.mime.isNotEmpty() && !now.mime.startsWith("audio/")
            val file = buildJsonObject { put("@type", "inputFileRemote"); put("id", now.remote_id) }
            val sent = c.call(request("sendMessage") {
                put("chat_id", now.chat_id)
                put("topic_id", buildJsonObject { put("@type", "messageTopicForum"); put("forum_topic_id", job.topicId) })
                put("options", buildJsonObject { put("@type", "messageSendOptions"); put("disable_notification", true) })
                put("input_message_content", buildJsonObject {
                    put("@type", if (asDocument) "inputMessageDocument" else "inputMessageAudio")
                    put(if (asDocument) "document" else "audio", file)
                })
            }, 60.seconds)
            sentId.complete(sent.long("id") ?: throw TdError(0, "Telegram did not accept the repost"))
            val done = withTimeoutOrNull(120.seconds) { outcome.await() } ?: throw TdError(408, "Repost timed out")
            if (done["@type"]?.jsonPrimitive?.content == "updateMessageSendFailed") {
                val err = done.obj("error")
                throw TdError(err?.long("code")?.toInt() ?: 0, err?.str("message") ?: "Telegram refused the repost")
            }
            val posted = done.obj("message") ?: throw TdError(0, "Repost not confirmed")
            val newId = posted.long("id") ?: throw TdError(0, "Repost not confirmed")
            val content = posted.obj("content")
            val remote = (content?.obj("audio")?.obj("audio") ?: content?.obj("document")?.obj("document"))
                ?.obj("remote")?.str("id") ?: now.remote_id
            val landed = posted.obj("topic_id")?.long("forum_topic_id")
            if (landed != null && landed != job.topicId) throw Skip("Repost landed in the wrong topic; nothing deleted")
            repo.movePostTo(now, newId, remote, job.topicId)
        } finally {
            outcome.cancel()
        }
        return try {
            c.call(request("deleteMessages") {
                put("chat_id", now.chat_id)
                put("message_ids", JsonArray(listOf(JsonPrimitive(now.message_id))))
                put("revoke", true)
            }, 30.seconds)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            "Moved, but the old post in General could not be deleted"
        }
    }
}
