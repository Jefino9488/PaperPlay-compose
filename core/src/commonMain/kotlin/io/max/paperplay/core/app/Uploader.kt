package io.max.paperplay.core.app

import io.max.paperplay.core.library.newUuid
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

enum class UploadStatus { Queued, Uploading, Done, Failed }

/** One file waiting to go to [chatId] (and [topicId] when the chat has topics). */
data class UploadJob(
    val id: String,
    val path: String,
    val name: String,
    val size: Long,
    val chatId: Long,
    val chatTitle: String,
    val topicId: Long? = null,
    val topicName: String? = null,
    val status: UploadStatus = UploadStatus.Queued,
    val progress: Float = 0f,
    val error: String? = null,
    val attempts: Int = 0,
    /** A copy made for the upload (a shared or picked file); deleted once it is sent or removed. */
    val temporary: Boolean = false,
)

/** Result of [Uploader.enqueue]: queued, left out as not audio, or skipped as already in the library. */
data class EnqueueResult(val queued: Int, val notAudio: Int, val known: Int)

/**
 * Sends audio files to a Telegram chat or topic as the bot, in a queue. Starts are spaced out (8-20 s, or 30-60 s
 * when "upload_slower" is on, and never faster than the "rate_uploads" setting) and a failed file retries a few times.
 */
class Uploader(
    private val scope: CoroutineScope,
    private val client: () -> TdClient?,
    private val pref: (String) -> String?,
    /** (file name, size) of every song already in the library. */
    private val knownSongs: () -> Set<Pair<String, Long>>,
    /** Extra bots that share the sending; null sends everything with [client]. */
    private val pool: io.max.paperplay.core.telegram.BotPool? = null,
) {
    private val _jobs = MutableStateFlow<List<UploadJob>>(emptyList())
    val jobs: StateFlow<List<UploadJob>> = _jobs
    private val paused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = paused
    private var running = false
    private val gate = Mutex()
    private var lastStart = 0L

    fun setPaused(on: Boolean) {
        paused.value = on
        if (!on) pump()
    }

    fun enqueue(
        paths: List<String>, chatId: Long, chatTitle: String, topicId: Long? = null, topicName: String? = null, temporary: Boolean = false,
    ): EnqueueResult {
        val known = if (pref("upload_skip_known") != "false") knownSongs() else emptySet()
        var notAudio = 0
        var skipped = 0
        val fresh = mutableListOf<UploadJob>()
        for (p in paths.distinct()) {
            val name = p.substringAfterLast('/').substringAfterLast('\\')
            val size = fileLength(p)
            if (size <= 0 || name.substringAfterLast('.', "").lowercase() !in audioExtensions) {
                notAudio++
                if (temporary) deleteFile(p)
                continue
            }
            if ((name to size) in known) {
                skipped++
                if (temporary) deleteFile(p)
                continue
            }
            val waiting = _jobs.value.any { it.path == p && it.chatId == chatId && it.status != UploadStatus.Done && it.status != UploadStatus.Failed }
            if (waiting) continue
            fresh += UploadJob(newUuid(), p, name, size, chatId, chatTitle, topicId, topicName, temporary = temporary)
        }
        _jobs.update { it + fresh }
        pump()
        return EnqueueResult(fresh.size, notAudio, skipped)
    }

    fun retry(id: String) {
        update(id) { it.copy(status = UploadStatus.Queued, error = null, attempts = 0, progress = 0f) }
        pump()
    }

    fun retryAll() {
        _jobs.update { l -> l.map { if (it.status == UploadStatus.Failed) it.copy(status = UploadStatus.Queued, error = null, attempts = 0, progress = 0f) else it } }
        pump()
    }

    /** Takes a waiting or failed file out of the queue. A file being sent is left alone. */
    fun remove(id: String) {
        val j = _jobs.value.firstOrNull { it.id == id } ?: return
        if (j.status == UploadStatus.Uploading) return
        if (j.temporary && j.status != UploadStatus.Done) deleteFile(j.path)
        _jobs.update { l -> l.filter { it.id != id } }
    }

    fun clearFinished() {
        _jobs.value.filter { it.status == UploadStatus.Done }.forEach { remove(it.id) }
    }

    private fun update(id: String, change: (UploadJob) -> UploadJob) = _jobs.update { l -> l.map { if (it.id == id) change(it) else it } }

    private fun pump() {
        if (running || paused.value) return
        running = true
        scope.launch {
            try {
                val parallel = (pref("upload_parallel")?.toIntOrNull() ?: 6).coerceIn(1, 3)
                List(parallel) { launch { worker() } }.joinAll()
            } finally {
                running = false
            }
            if (!paused.value && _jobs.value.any { it.status == UploadStatus.Queued }) pump()
        }
    }

    private suspend fun worker() {
        while (!paused.value) {
            val job = gate.withLock {
                val next = _jobs.value.firstOrNull { it.status == UploadStatus.Queued } ?: return
                update(next.id) { it.copy(status = UploadStatus.Uploading, progress = 0f) }
                next
            }
            spaceOut()
            send(job)
        }
    }

    /** Waits until enough time has passed since the last start. */
    private suspend fun spaceOut() = gate.withLock {
        val rate = (pref("rate_uploads")?.toIntOrNull() ?: 6).coerceAtLeast(1)
        val base = if (pref("upload_slower") == "true") Random.nextLong(30_000, 60_000) else Random.nextLong(8_000, 20_000)
        val wait = lastStart + maxOf(base, 60_000L / rate) - nowMs()
        if (lastStart != 0L && wait > 0) delay(wait)
        lastStart = nowMs()
    }

    private fun nowMs() = Clock.System.now().toEpochMilliseconds()

    private suspend fun send(job: UploadJob) {
        var botId = 0L
        try {
            val picked = pool?.pick()
            val c = picked?.client ?: client() ?: throw TdError(0, "The bot is not connected.")
            botId = picked?.id ?: 0L
            if (botId != 0L) runCatching { c.call(request("getChat") { put("chat_id", job.chatId) }) }
            sendOne(c, job)
            pool?.recordSent(botId)
            update(job.id) { it.copy(status = UploadStatus.Done, progress = 1f, error = null) }
            if (job.temporary) deleteFile(job.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val attempts = job.attempts + 1
            val td = e as? TdError
            td?.retryAfter?.let { pool?.recordFlood(botId, it) }
            val retryable = attempts < MAX_ATTEMPTS && (td == null || td.code == 429 || td.code == 408 || td.code == 0 || td.code >= 500)
            if (retryable) {
                update(job.id) { it.copy(status = UploadStatus.Queued, attempts = attempts, error = e.message, progress = 0f) }
                delay(((td?.retryAfter ?: (10 * attempts)) + 1) * 1000L)
            } else {
                update(job.id) { it.copy(status = UploadStatus.Failed, attempts = attempts, error = e.message ?: "Failed") }
            }
        }
    }

    private suspend fun sendOne(c: TdClient, job: UploadJob) {
        val sentId = CompletableDeferred<Long>()
        val fileId = CompletableDeferred<Long>()
        val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) {
            c.updates.first { u ->
                val type = u["@type"]?.jsonPrimitive?.content
                if (type == "updateFile") {
                    val f = u.obj("file")
                    val mine = if (fileId.isCompleted) fileId.await() else null
                    if (f != null && mine != null && f.long("id") == mine && job.size > 0) {
                        val up = f.obj("remote")?.long("uploaded_size") ?: 0L
                        update(job.id) { it.copy(progress = (up.toFloat() / job.size).coerceIn(0f, 1f)) }
                    }
                    false
                } else if (type == "updateMessageSendSucceeded" || type == "updateMessageSendFailed") {
                    u.long("old_message_id") == sentId.await()
                } else {
                    false
                }
            }
        }
        try {
            val sent = c.call(request("sendMessage") {
                put("chat_id", job.chatId)
                if (job.topicId != null && job.topicId > 1L) {
                    put("topic_id", buildJsonObject { put("@type", "messageTopicForum"); put("forum_topic_id", job.topicId) })
                }
                put("input_message_content", buildJsonObject {
                    put("@type", "inputMessageAudio")
                    put("audio", buildJsonObject { put("@type", "inputFileLocal"); put("path", job.path) })
                    val (artist, title) = parseFileName(job.name)
                    if (title.isNotEmpty()) put("title", title)
                    if (artist.isNotEmpty()) put("performer", artist)
                })
            }, 2.minutes)
            sent.obj("content")?.obj("audio")?.obj("audio")?.long("id")?.let { fileId.complete(it) }
            sentId.complete(sent.long("id") ?: throw TdError(0, "Telegram did not accept the file."))
            val done = withTimeoutOrNull(30.minutes) { outcome.await() } ?: throw TdError(408, "Upload timed out")
            if (done["@type"]?.jsonPrimitive?.content == "updateMessageSendFailed") {
                val err = done.obj("error")
                throw TdError(err?.long("code")?.toInt() ?: 0, err?.str("message") ?: "Telegram refused the file.")
            }
        } finally {
            outcome.cancel()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
    }
}
