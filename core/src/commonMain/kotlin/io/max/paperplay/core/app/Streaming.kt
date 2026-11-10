package io.max.paperplay.core.app

import io.max.paperplay.core.library.Song
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.TdError
import io.max.paperplay.core.telegram.request
import io.max.paperplay.player.QueueItem
import io.max.paperplay.player.StreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Serves byte ranges of a song out of TDLib's partially downloaded file. Each read moves the
 * download to the requested offset, so seeking works on files that are not downloaded yet.
 * Nothing is requested until the first read or [prefetch]. [read] blocks, as [StreamSource] requires.
 */
class TdStreamSource(private val td: () -> TdClient?, val song: Song) : StreamSource {
    override val size: Long get() = song.size

    private var fileId = 0
    private var path = ""

    /** TDLib file ids do not outlive a session, so the file is looked up through its message. */
    private suspend fun client(): TdClient = td() ?: throw TdError(0, "Not logged in")

    /** Looks the file up (getMessage); a deleted post throws a 404 [TdError]. */
    suspend fun resolve() {
        if (fileId != 0) return
        val message = client().call(request("getMessage") {
            put("chat_id", song.chat_id)
            put("message_id", song.message_id)
        })
        val file = fileOfMessage(message) ?: throw TdError(404, "Message holds no audio")
        fileId = file.long("id")?.toInt() ?: throw TdError(0, "No file id")
        path = file.obj("local")?.str("path").orEmpty()
    }

    private suspend fun requestDownload(offset: Long, limit: Long, priority: Int): JsonObject {
        val r = client().call(request("downloadFile") {
            put("file_id", fileId)
            put("priority", priority)
            put("offset", offset)
            put("limit", limit)
            put("synchronous", false)
        })
        r.obj("local")?.str("path")?.takeIf { it.isNotEmpty() }?.let { path = it }
        return r
    }

    /** Starts downloading the first [bytes] so the track can start at once. */
    suspend fun prefetch(bytes: Long = PREFETCH_BYTES) {
        resolve()
        requestDownload(0, bytes, PREFETCH_PRIORITY)
    }

    /** Stops any download this source started. */
    suspend fun cancel() {
        if (fileId == 0) return
        runCatching { client().call(request("cancelDownloadFile") { put("file_id", fileId); put("only_if_pending", false) }) }
    }

    override fun read(offset: Long, length: Int): ByteArray = runBlocking { readAt(offset, length) }

    private suspend fun readAt(offset: Long, length: Int): ByteArray {
        val end = minOf(song.size, offset + length)
        if (offset < 0 || offset >= end) return ByteArray(0)
        resolve()
        var file = requestDownload(offset, 0, READ_PRIORITY)
        val c = client()
        var progressAt = TimeSource.Monotonic.markNow()
        while (true) {
            val available = c.call(request("getFileDownloadedPrefixSize") {
                put("file_id", fileId)
                put("offset", offset)
            }).long("size") ?: 0L
            if (available > 0) {
                if (path.isEmpty()) requestDownload(offset, 0, READ_PRIORITY)
                if (path.isNotEmpty()) {
                    val bytes = readRange(path, offset, minOf(available, end - offset, MAX_READ).toInt())
                    if (bytes.isNotEmpty()) return bytes
                }
            }
            if (progressAt.elapsedNow() > STALL) throw TdError(0, "Download stalled. Check your connection.")
            val update = withTimeoutOrNull(500.milliseconds) {
                c.updates.first { it.type == "updateFile" && it.obj("file")?.long("id")?.toInt() == fileId }
            }?.obj("file")
            if (update != null) {
                file = update
                val local = update.obj("local")
                if ((local?.long("downloaded_size") ?: 0L) > 0L) progressAt = TimeSource.Monotonic.markNow()
                local?.str("path")?.takeIf { it.isNotEmpty() }?.let { path = it }
            }
            // Another request may have moved or stopped this download.
            if ((file.obj("local")?.get("is_downloading_active") as? JsonPrimitive)?.booleanOrNull == false) {
                requestDownload(offset, 0, READ_PRIORITY)
            }
        }
    }

    private companion object {
        val STALL = 20.seconds
        const val MAX_READ = 256L * 1024
        const val PREFETCH_BYTES = 512L * 1024
        const val READ_PRIORITY = 32
        const val PREFETCH_PRIORITY = 16
    }
}

/**
 * Turns songs into [QueueItem]s backed by [TdStreamSource]s, one per song, and limits downloading
 * to the current and next track.
 */
class TrackResolver(private val td: () -> TdClient?) {
    private val sources = MutableStateFlow<Map<String, TdStreamSource>>(emptyMap())

    private fun sourceOf(song: Song): TdStreamSource {
        sources.value[song.unique_id]?.let { return it }
        val fresh = TdStreamSource(td, song)
        sources.update { it + (song.unique_id to (it[song.unique_id] ?: fresh)) }
        return sources.value.getValue(song.unique_id)
    }

    /** No network until the player reads. */
    fun resolve(song: Song) = QueueItem(
        uri = "tg-${song.unique_id}",
        title = song.title,
        artist = song.artist,
        artworkUri = song.cover_path,
        source = sourceOf(song),
    )

    /** Looks up the file so playback can start without a round trip. */
    suspend fun warm(song: Song) = sourceOf(song).resolve()

    /** Keeps downloads only for [current] and [next]; starts [next]'s head. */
    suspend fun window(current: Song?, next: Song?) {
        val keep = setOfNotNull(current?.unique_id, next?.unique_id)
        sources.value.filterKeys { it !in keep }.values.forEach { it.cancel() }
        if (next != null) runCatching { sourceOf(next).prefetch() }
    }

    /** Cancels and forgets the sources of songs not in [songs], e.g. after the queue changed. */
    suspend fun retain(songs: List<Song>) {
        val ids = songs.map { it.unique_id }.toSet()
        val gone = sources.value.filterKeys { it !in ids }
        sources.update { it - gone.keys }
        gone.values.forEach { it.cancel() }
    }
}
