package io.max.paperplay.core.app

import io.max.paperplay.core.library.Song
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.TdError
import io.max.paperplay.core.telegram.request
import io.max.paperplay.player.QueueItem
import io.max.paperplay.player.StreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Serves byte ranges of a song out of TDLib's local file. Already-downloaded files are read
 * directly from disk with zero download requests. Otherwise, reads request a sliding byte
 * window sized to the track's own bitrate and the player's read demand (`limit > 0`) rather
 * than downloading the entire file at once, and pause/skip cancel active non-pinned downloads.
 */
class TdStreamSource(
    private val td: () -> TdClient?,
    val song: Song,
    private val windowSeconds: Long = DEFAULT_WINDOW_SECONDS,
) : StreamSource {
    override val size: Long get() = song.size

    @Volatile private var fileId = 0
    @Volatile private var path = ""
    @Volatile private var completed = false
    @Volatile private var requestedUntil = 0L
    @Volatile private var paused = false
    @Volatile private var prefix = 0L
    @Volatile private var active = true
    @Volatile private var progressTick = 0L
    @Volatile private var lastSize = 0L
    @Volatile private var block: Block? = null
    private val progress = Channel<Unit>(Channel.CONFLATED)

    private class Block(val start: Long, val data: ByteArray)

    /** TDLib file ids do not outlive a session, so the file is looked up through its message (with offline fallback). */
    private suspend fun client(): TdClient = td() ?: throw TdError(0, "Not logged in")

    private fun applyFile(file: JsonObject) {
        file.long("id")?.toInt()?.let { if (it != 0) fileId = it }
        val local = file.obj("local")
        local?.str("path")?.takeIf { it.isNotEmpty() }?.let { path = it }
        if ((local?.get("is_downloading_completed") as? JsonPrimitive)?.booleanOrNull == true) {
            completed = true
            requestedUntil = song.size
        }
        local?.long("downloaded_prefix_size")?.let { prefix = it }
        local?.long("downloaded_size")?.let { if (it != lastSize) { lastSize = it; progressTick++ } }
        (local?.get("is_downloading_active") as? JsonPrimitive)?.booleanOrNull?.let { active = it }
        progress.trySend(Unit)
    }

    /** Updates cached file state from TDLib `updateFile` events. */
    fun onFileUpdate(file: JsonObject) {
        val uid = file.obj("remote")?.str("unique_id")
        val id = file.long("id")?.toInt() ?: 0
        if (uid == song.unique_id || (fileId != 0 && id == fileId)) {
            applyFile(file)
        }
    }

    /** Looks the file up (getMessage, falling back to getRemoteFile when offline); a deleted post throws 404. */
    suspend fun resolve() {
        if (fileId != 0 && (completed || path.isNotEmpty())) return
        val c = client()
        val file = try {
            val message = c.call(request("getMessage") {
                put("chat_id", song.chat_id)
                put("message_id", song.message_id)
            })
            fileOfMessage(message) ?: throw TdError(404, "Message holds no audio")
        } catch (e: TdError) {
            if (e.code == 404 || song.remote_id.isEmpty()) throw e
            c.call(request("getRemoteFile") { put("remote_file_id", song.remote_id) })
        }
        applyFile(file)
        if (fileId == 0) throw TdError(0, "No file id")
    }

    private fun windowBytes(offset: Long, length: Int): Long {
        val remaining = (song.size - offset).coerceAtLeast(0L)
        if (remaining <= 0L || song.pinned || song.size <= WHOLE_FILE_BYTES) return 0L
        val demand = maxOf(length.toLong(), 1L)
        val fromBitrate = if (song.duration_sec > 0 && song.size > 0) {
            ((song.size + song.duration_sec - 1) / song.duration_sec) * windowSeconds
        } else {
            demand * UNKNOWN_DURATION_MULTIPLIER
        }
        return minOf(remaining, maxOf(demand, fromBitrate))
    }

    private suspend fun requestDownload(offset: Long, limit: Long, priority: Int): JsonObject {
        val r = client().call(request("downloadFile") {
            put("file_id", fileId)
            put("priority", priority)
            put("offset", offset)
            put("limit", limit)
            put("synchronous", false)
        })
        applyFile(r)
        requestedUntil = if (limit <= 0L) song.size else minOf(song.size, maxOf(requestedUntil, offset + limit))
        return r
    }

    private suspend fun requestWindow(offset: Long, length: Int, priority: Int): JsonObject =
        requestDownload(offset, windowBytes(offset, length), priority)

    /** Starts fetching the opening sliding window when the song is not already downloaded. */
    suspend fun prefetch() {
        resolve()
        if (completed) return
        val window = windowBytes(0L, 0)
        val have = prefix
        if (!song.pinned && window > 0L && have >= window) return
        paused = false
        val limit = if (song.pinned) 0L else maxOf(0L, window - have)
        requestDownload(have, limit, PREFETCH_PRIORITY)
    }

    /**
     * The first bytes of the song's file as downloaded so far, or null when none are on this device yet. Reads
     * what playback fetched; it starts no download of its own.
     */
    suspend fun head(maxBytes: Int = HEAD_BYTES): ByteArray? {
        if (fileId == 0 || path.isEmpty()) return null
        val have = if (completed) song.size else prefix
        if (have < 64 * 1024 && have < song.size) return null
        return readRange(path, 0, minOf(have, maxBytes.toLong()).toInt()).takeIf { it.isNotEmpty() }
    }

    /** Stops any active download for non-pinned songs. Partial bytes stay on disk. */
    suspend fun cancel() {
        paused = true
        requestedUntil = 0L
        if (fileId == 0 || completed || song.pinned) return
        runCatching { client().call(request("cancelDownloadFile") { put("file_id", fileId); put("only_if_pending", false) }) }
    }

    override fun read(offset: Long, length: Int): ByteArray = runBlocking { readAt(offset, length) }

    /** Bytes available from [offset] on disk: from the cached prefix when possible, else one TDLib lookup. */
    private suspend fun availableAt(offset: Long): Long {
        if (completed) return (song.size - offset).coerceAtLeast(0L)
        val p = prefix
        if (offset < p) return p - offset
        if (fileId == 0) return 0L
        return client().call(request("getFileDownloadedPrefixSize") {
            put("file_id", fileId)
            put("offset", offset)
        }).long("size") ?: 0L
    }

    /** Serves [want] bytes at [offset] from the read-ahead block, refilling it in 256 KB blocks from disk. */
    private fun serve(offset: Long, want: Int, available: Long): ByteArray? {
        val b = block
        if (b != null && offset >= b.start && offset + want <= b.start + b.data.size) {
            return b.data.copyOfRange((offset - b.start).toInt(), (offset - b.start).toInt() + want)
        }
        if (available <= 0 || path.isEmpty()) return null
        val n = minOf(available, maxOf(want.toLong(), BLOCK_BYTES.toLong()), song.size - offset).toInt()
        val bytes = readRange(path, offset, n)
        if (bytes.isEmpty()) return null
        block = Block(offset, bytes)
        return if (bytes.size <= want) bytes else bytes.copyOf(want)
    }

    private suspend fun readAt(offset: Long, length: Int): ByteArray {
        val end = minOf(song.size, offset + length)
        if (offset < 0 || offset >= end) return ByteArray(0)
        resolve()
        val want = (end - offset).toInt()
        val c = client()
        var avail = availableAt(offset)
        if (avail > 0) {
            val bytes = serve(offset, minOf(want.toLong(), avail).toInt(), avail)
            if (bytes != null) {
                val bufferedEnd = offset + avail
                if (!completed && !paused && !song.pinned && bufferedEnd < song.size &&
                    bufferedEnd >= requestedUntil - want && avail <= want * 2L
                ) {
                    runCatching { requestWindow(bufferedEnd, length, READ_PRIORITY) }
                }
                return bytes
            }
            if (completed) completed = false
        }

        paused = false
        var file = requestWindow(offset, length, READ_PRIORITY)
        var progressAt = TimeSource.Monotonic.markNow()
        while (true) {
            avail = availableAt(offset)
            if (avail > 0) {
                if (path.isEmpty()) requestWindow(offset, length, READ_PRIORITY)
                if (path.isNotEmpty()) {
                    val bytes = serve(offset, minOf(want.toLong(), avail).toInt(), avail)
                    if (bytes != null) return bytes
                }
            }
            if (progressAt.elapsedNow() > STALL) throw TdError(0, "Download stalled. Check your connection.")
            val before = progressTick
            withTimeoutOrNull(1000.milliseconds) { progress.receive() }
            if (progressTick != before) progressAt = TimeSource.Monotonic.markNow()
            if (!completed && !paused && !active) {
                requestWindow(offset, length, READ_PRIORITY)
            }
        }
    }

    companion object {
        const val DEFAULT_WINDOW_SECONDS = 40L
        private const val WHOLE_FILE_BYTES = 30L * 1024 * 1024
        private const val BLOCK_BYTES = 256 * 1024
        private val STALL = 20.seconds
        private const val HEAD_BYTES = 256 * 1024
        private const val UNKNOWN_DURATION_MULTIPLIER = 8L
        private const val READ_PRIORITY = 32
        private const val PREFETCH_PRIORITY = 16
    }
}

/**
 * Turns songs into lazy [QueueItem]s backed by [TdStreamSource]s created only when opened,
 * keeping active downloads strictly to the current playback window.
 */
/**
 * Songs whose posts the bot cannot read (for example posted by another bot) are added to the library through the
 * owner's account and carry this value as their remote id. They are streamed with the account's TDLib client.
 */
const val ACCOUNT_REMOTE = "account"

class TrackResolver(
    private val td: () -> TdClient?,
    val windowSeconds: Long,
    private val accountTd: () -> TdClient? = { null },
) {
    constructor(td: () -> TdClient?) : this(td, TdStreamSource.DEFAULT_WINDOW_SECONDS)
    constructor(td: () -> TdClient?, accountTd: () -> TdClient?) : this(td, TdStreamSource.DEFAULT_WINDOW_SECONDS, accountTd)
    private val sources = MutableStateFlow<Map<String, TdStreamSource>>(emptyMap())

    private fun sourceOf(song: Song): TdStreamSource {
        sources.value[song.unique_id]?.let { return it }
        val fresh = TdStreamSource(if (song.remote_id == ACCOUNT_REMOTE) accountTd else td, song, windowSeconds)
        sources.update { it + (song.unique_id to (it[song.unique_id] ?: fresh)) }
        return sources.value.getValue(song.unique_id)
    }

    /** Creates a lazy [QueueItem]; no TDLib calls or stream sources are created until opened. */
    fun resolve(song: Song) = QueueItem(
        uri = "tg-${song.unique_id}",
        title = song.title,
        artist = song.artist,
        artworkUri = song.cover_path,
        sourceFactory = { sourceOf(song) },
    )

    /** Looks up the file so playback can start without a round trip. */
    suspend fun warm(song: Song) = sourceOf(song).resolve()

    /** The start of [song]'s file, once playback has fetched it; see [TdStreamSource.head]. */
    suspend fun headOf(song: Song): ByteArray? = sources.value[song.unique_id]?.head()

    /** Keeps active downloads strictly to [current] and [next] (or pauses all when not playing). */
    suspend fun window(current: Song?, next: Song?, playing: Boolean = true, previous: Song? = null) {
        val keep = setOfNotNull(current?.unique_id, next?.unique_id, previous?.unique_id)
        val stale = sources.value.filterKeys { it !in keep }
        if (stale.isNotEmpty()) {
            sources.update { it - stale.keys }
            stale.values.forEach { it.cancel() }
        }
        if (!playing) pauseAll()
    }

    /** Prefetches the opening sliding window of [song] when it is not already downloaded. */
    suspend fun prefetch(song: Song) = sourceOf(song).prefetch()

    /** Cancels all active non-pinned downloads (e.g., on pause or stop). */
    suspend fun pauseAll() {
        sources.value.values.forEach { it.cancel() }
    }

    /** Updates active stream sources when TDLib emits `updateFile`. */
    fun onFileUpdate(file: JsonObject) {
        sources.value.values.forEach { it.onFileUpdate(file) }
    }

    /** Unique ids currently held by active stream sources (protected from cache eviction). */
    fun activeIds(): Set<String> = sources.value.keys

    private var idsFor: List<Song>? = null
    private var ids: Set<String> = emptySet()

    private fun idsOf(songs: List<Song>): Set<String> {
        if (idsFor === songs) return ids
        return songs.mapTo(HashSet(songs.size * 2)) { it.unique_id }.also { ids = it; idsFor = songs }
    }

    /** Cancels and forgets sources for songs not in [songs], e.g. after the queue changed. */
    suspend fun retain(songs: List<Song>) {
        if (sources.value.isEmpty()) return
        val ids = idsOf(songs)
        val gone = sources.value.filterKeys { it !in ids }
        sources.update { it - gone.keys }
        gone.values.forEach { it.cancel() }
    }
}
