package io.max.paperplay.core.account

import io.max.paperplay.core.app.UploadStatus
import io.max.paperplay.core.app.Uploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How lost songs get back where the bot can read them. */
enum class RecoveryMode {
    /** The owner's account copies each post into the main chat. Fast, needs no download. */
    CopyByAccount,

    /** The owner's account downloads each song and the main bot uploads it again, as itself. */
    ReuploadByBot,
}

/** Progress of a recovery run. [total] is 0 until the songs are counted. */
data class RecoveryProgress(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val removed: Int = 0,
    val text: String = "",
    val errors: List<String> = emptyList(),
)

/**
 * Brings back songs of the main chat that the bot cannot read (posted by other bots as themselves) by posting them
 * again so the main bot sees them, then optionally deleting the originals so the chat holds one copy of each.
 *
 * Both modes need the owner's account, since only it can see those posts. Nothing is deleted before its new post
 * exists: a copy that Telegram refused, or an upload that failed, leaves the original alone.
 */
class SongRecovery(
    private val index: ChatIndexApi,
    private val account: AccountApi,
    private val uploader: Uploader,
    private val mainChat: () -> Long?,
    private val mainTitle: () -> String,
    /** Downloads the post with the owner's account: the local file path and TDLib's file id, or null. */
    private val download: suspend (ChatPost) -> Pair<String, Int>?,
    /** Lets TDLib drop a downloaded file after it was sent. */
    private val release: suspend (Int) -> Unit,
    /** Deletes a post with the owner's account. False when Telegram refuses. */
    private val deletePost: suspend (chatId: Long, messageId: Long) -> Boolean,
    private val scope: CoroutineScope,
    /** Posts per minute. */
    private val perMinute: () -> Int = { 20 },
    private val onFinished: suspend () -> Unit = {},
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val _progress = MutableStateFlow(RecoveryProgress())
    val progress: StateFlow<RecoveryProgress> = _progress
    private var job: Job? = null

    /** Posts of the main chat that the library lacks, from the index. Index the main chat first. */
    suspend fun candidates(): List<ChatPost> = index.missingFromMainChat()

    fun cancel() { job?.cancel() }

    fun start(mode: RecoveryMode, deleteOriginals: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            _progress.value = RecoveryProgress(running = true, text = "Counting songs the bot cannot read")
            try {
                val main = mainChat() ?: throw IllegalStateException("No main chat is set.")
                if (!account.loggedIn.value) throw IllegalStateException("Log in with your own account first.")
                val posts = index.missingFromMainChat()
                _progress.value = _progress.value.copy(total = posts.size, text = if (posts.isEmpty()) "Nothing to recover" else "Recovering ${posts.size} songs")
                if (posts.isNotEmpty()) when (mode) {
                    RecoveryMode.CopyByAccount -> copyAll(main, posts, deleteOriginals)
                    RecoveryMode.ReuploadByBot -> reuploadAll(main, posts, deleteOriginals)
                }
                runCatching { onFinished() }
                _progress.value = _progress.value.copy(running = false, text = "Done")
            } catch (e: CancellationException) {
                _progress.value = _progress.value.copy(running = false, text = "Stopped")
                throw e
            } catch (e: Exception) {
                _progress.value = _progress.value.copy(running = false, text = "Stopped: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    private fun change(f: (RecoveryProgress) -> RecoveryProgress) { _progress.value = f(_progress.value) }

    private fun fail(post: ChatPost, why: String?) = change { p ->
        p.copy(failed = p.failed + 1, errors = (p.errors + "${post.title}: ${why ?: "failed"}").takeLast(20))
    }

    private suspend fun gap() = pause(60_000L / perMinute().coerceIn(1, 120))

    private suspend fun copyAll(main: Long, posts: List<ChatPost>, deleteOriginals: Boolean) {
        for (post in posts) {
            try {
                account.copyPost(post.chatId, post.messageId, main)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(post, e.message)
                gap()
                continue
            }
            // The copy exists now, so the original is a duplicate.
            var removed = 0
            if (deleteOriginals && deletePost(post.chatId, post.messageId)) removed = 1
            change { it.copy(done = it.done + 1, removed = it.removed + removed) }
            gap()
        }
    }

    private suspend fun reuploadAll(main: Long, posts: List<ChatPost>, deleteOriginals: Boolean) {
        // Local path to (original post, TDLib file id) for every upload waiting to finish.
        val sent = LinkedHashMap<String, Pair<ChatPost, Int>>()
        for (post in posts) {
            val got = try {
                download(post)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(post, e.message)
                null
            }
            if (got == null) { gap(); continue }
            val r = uploader.enqueue(listOf(got.first), main, mainTitle())
            if (r.queued == 0) {
                fail(post, "the bot could not queue it (already in the library, or not an audio file)")
                runCatching { release(got.second) }
            } else sent[got.first] = post to got.second
            change { it.copy(text = "Downloaded ${sent.size} of ${posts.size}, uploading") }
            gap()
        }
        // Wait for the bot to finish, then settle each song.
        var waited = 0
        while (sent.isNotEmpty() && waited < MAX_WAIT_SECONDS) {
            val jobs = uploader.jobs.value
            val finished = sent.keys.filter { p ->
                val s = jobs.firstOrNull { it.path == p }?.status
                s == UploadStatus.Done || s == UploadStatus.Failed
            }
            for (path in finished) {
                val (post, fileId) = sent.remove(path) ?: continue
                val ok = jobs.first { it.path == path }.status == UploadStatus.Done
                runCatching { release(fileId) }
                if (!ok) { fail(post, "the bot could not upload it"); continue }
                var removed = 0
                if (deleteOriginals && deletePost(post.chatId, post.messageId)) removed = 1
                change { it.copy(done = it.done + 1, removed = it.removed + removed) }
            }
            if (sent.isNotEmpty()) { delay(2_000); waited += 2 }
        }
        for ((_, v) in sent) fail(v.first, "still uploading when the wait ended; check the upload queue")
    }

    private companion object {
        const val MAX_WAIT_SECONDS = 6 * 60 * 60
    }
}
