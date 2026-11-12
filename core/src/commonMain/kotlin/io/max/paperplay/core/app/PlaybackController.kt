package io.max.paperplay.core.app

import io.max.paperplay.core.library.Song
import io.max.paperplay.core.telegram.TdError
import io.max.paperplay.player.PlayerEngine
import io.max.paperplay.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Repeat { OFF, ALL, ONE }

private const val SESSION_QUEUE = "session_queue"
private const val SESSION_INDEX = "session_index"
private const val SESSION_POS = "session_pos"

/** What the player UI shows. [index] is into [queue], which is in play order (shuffled when [shuffle]). */
data class NowPlaying(
    val song: Song? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val index: Int = -1,
    val queue: List<Song> = emptyList(),
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.OFF,
    /** Text of the last failure to start a track; null otherwise. */
    val error: String? = null,
    val bufferedMs: Long = 0,
)

/**
 * Plays lists of songs on a [PlayerEngine]. Commands that change the queue or track are
 * last-tap-wins: a newer one cancels an older one that has not reached the engine yet.
 */
class PlaybackController(
    private val engine: PlayerEngine,
    private val resolver: TrackResolver,
    private val repo: LibraryRepository,
    private val scope: CoroutineScope,
) {
    private class Meta(val queue: List<Song>, val shuffle: Boolean, val repeat: Repeat, val error: String?)

    private val meta = MutableStateFlow(Meta(emptyList(), false, Repeat.OFF, null))
    private var original: List<Song> = emptyList()
    private var job: Job? = null
    private var neighborJob: Job? = null
    private val gate = Mutex()

    // Listen counting for the song that is currently playing.
    private var listening: Song? = null
    private var listenedMs = 0L
    private var prefetchedFor: String? = null

    /** Everything in [NowPlaying] except the ticking position and buffer; does not change on position ticks. */
    val nowPlayingStatic: StateFlow<NowPlaying> = combine(
        engine.state, engine.durationMs, engine.currentIndex, meta,
    ) { state, dur, idx, m ->
        val song = m.queue.getOrNull(idx)
        NowPlaying(
            song, 0, if (dur > 0) dur else (song?.duration_sec ?: 0) * 1000, state == PlayerState.Playing,
            idx, m.queue, m.shuffle, m.repeat, m.error, 0,
        )
    }.stateIn(scope, SharingStarted.Eagerly, NowPlaying())

    /** Position and buffered ms, for views that tick. */
    val positionMs: StateFlow<Long> get() = engine.positionMs
    val bufferedMs: StateFlow<Long> get() = engine.bufferedMs

    val nowPlaying: StateFlow<NowPlaying> = combine(
        nowPlayingStatic, engine.positionMs, engine.bufferedMs,
    ) { base, pos, buf -> base.copy(positionMs = pos, bufferedMs = buf) }
        .stateIn(scope, SharingStarted.Eagerly, NowPlaying())

    init {
        // The queue is saved when it changes; position and index are saved every few seconds while playing.
        scope.launch {
            var saved: List<Song>? = null
            meta.collect { m ->
                if (m.queue.isNotEmpty() && m.queue !== saved) {
                    saved = m.queue
                    runCatching { repo.setPref(SESSION_QUEUE, m.queue.joinToString(",") { it.unique_id }) }
                }
            }
        }
        scope.launch {
            while (true) {
                delay(8_000)
                if (engine.state.value == PlayerState.Playing) saveSession()
            }
        }
        scope.launch { engine.state.collect { if (it == PlayerState.Paused) saveSession() } }
        scope.launch {
            engine.currentIndex.collect { idx ->
                val song = meta.value.queue.getOrNull(idx)
                if (song?.unique_id != listening?.unique_id) {
                    finishListening()
                    listening = song
                }
                prefetchedFor = null
                val q = meta.value.queue
                val next = q.getOrNull(idx + 1)
                val prev = q.getOrNull(idx - 1)
                neighborJob?.cancel()
                neighborJob = launch {
                    val active = engine.state.value == PlayerState.Playing || engine.state.value == PlayerState.Loading
                    runCatching { resolver.window(song, next, playing = active, previous = prev) }
                    if (active) {
                        if (next != null) launch { runCatching { resolver.prefetch(next) } }
                        if (prev != null) {
                            delay(1_500)
                            runCatching { resolver.prefetch(prev) }
                        }
                    }
                }
                if (song != null) launch { learnAudioDetails(song) }
            }
        }
        scope.launch {
            engine.positionMs.collect { pos ->
                listenedMs = maxOf(listenedMs, pos)
                if (engine.state.value != PlayerState.Playing) return@collect
                val idx = engine.currentIndex.value
                val cur = meta.value.queue.getOrNull(idx) ?: return@collect
                val next = meta.value.queue.getOrNull(idx + 1) ?: return@collect
                if (prefetchedFor == cur.unique_id) return@collect
                val dur = engine.durationMs.value.takeIf { it > 0 } ?: (cur.duration_sec * 1000L)
                if (dur > 0 && pos > 0 && dur - pos <= resolver.windowSeconds * 1000L) {
                    prefetchedFor = cur.unique_id
                    launch { runCatching { resolver.prefetch(next) } }
                }
            }
        }
        scope.launch {
            engine.state.collect { state ->
                if (state == PlayerState.Paused || state == PlayerState.Idle || state == PlayerState.Ended || state == PlayerState.Error) {
                    launch { runCatching { resolver.pauseAll() } }
                }
                if (state != PlayerState.Ended) return@collect
                when (meta.value.repeat) {
                    Repeat.ONE -> engine.play()
                    Repeat.ALL -> restart()
                    Repeat.OFF -> Unit
                }
            }
        }
    }

    /**
     * Reads a song's real codec, bit depth and sample rate from the start of its file, once playback has fetched
     * that. Costs no download of its own. Tries for about half a minute, then gives up until the song plays again.
     */
    private suspend fun learnAudioDetails(song: Song) {
        if (repo.songByUniqueId(song.unique_id)?.sample_rate != null) return
        repeat(6) {
            delay(4_000)
            if (engine.currentIndex.value < 0 || meta.value.queue.getOrNull(engine.currentIndex.value)?.unique_id != song.unique_id) return
            val head = runCatching { resolver.headOf(song) }.getOrNull() ?: return@repeat
            val h = parseAudioHeader(head)
            // TODO: use the non-notifying (notify=false) variant once LibraryRepository has it.
            if (h != null) repo.setAudioDetails(song.unique_id, h)
            return
        }
    }

    /** Called when a song stops, with how long it was heard. Set by the owner, for scrobbling. */
    @kotlin.concurrent.Volatile
    var onListened: ((Song, Long) -> Unit)? = null

    private fun finishListening() {
        val song = listening ?: return
        if (song.remote_id == NETPLAY_REMOTE) { listenedMs = 0; return }
        val ms = listenedMs
        listenedMs = 0
        if (ms <= 0) return
        val full = song.duration_sec * 1000
        val finished = if (full > 0 && ms >= full * 9 / 10) true else if (ms >= 5_000) false else null
        runCatching { repo.recordListen(song, ms, finished) }
        runCatching { onListened?.invoke(song, ms) }
    }

    /** Replaces the queue with [songs] and plays [startIndex]. */
    fun setQueue(songs: List<Song>, startIndex: Int = 0) = command {
        if (songs.isEmpty()) return@command
        val start = startIndex.coerceIn(songs.indices)
        original = songs
        val order = if (meta.value.shuffle) {
            listOf(songs[start]) + songs.toMutableList().also { it.removeAt(start) }.shuffled()
        } else songs
        apply(order, if (meta.value.shuffle) 0 else start, 0)
    }

    fun play() = engine.play()
    fun pause() {
        engine.pause()
        scope.launch { runCatching { resolver.pauseAll() } }
    }
    fun seek(positionMs: Long) = engine.seek(positionMs)
    fun setVolume(volume: Float) = engine.setVolume(volume)
    fun setEqualizer(gainsDb: List<Float>?) = engine.setEqualizer(gainsDb)
    fun setSpeed(speed: Float) = engine.setSpeed(speed)
    fun setNormalize(on: Boolean) = engine.setNormalize(on)

    /** Takes the song at [index] out of the queue. The last song cannot be removed. Playing on keeps the position. */
    fun removeAt(index: Int) = command {
        val m = meta.value
        val idx = engine.currentIndex.value
        if (index !in m.queue.indices || m.queue.size < 2) return@command
        val wasPlaying = engine.state.value == PlayerState.Playing
        val gone = m.queue[index]
        val order = m.queue.toMutableList().also { it.removeAt(index) }
        original = original.toMutableList().also { o -> o.indexOfFirst { it.unique_id == gone.unique_id }.takeIf { it >= 0 }?.let(o::removeAt) }
        val removedCurrent = index == idx
        val newIdx = when {
            removedCurrent -> idx.coerceAtMost(order.lastIndex)
            index < idx -> idx - 1
            else -> idx
        }
        apply(order, newIdx, if (removedCurrent) 0 else engine.positionMs.value)
        if (!wasPlaying) engine.pause()
    }

    /** Moves the song at [from] to [to] in the queue, without restarting what is playing from the start. */
    fun moveInQueue(from: Int, to: Int) = command {
        val m = meta.value
        val idx = engine.currentIndex.value
        if (from !in m.queue.indices || to !in m.queue.indices || from == to) return@command
        val wasPlaying = engine.state.value == PlayerState.Playing
        val order = m.queue.toMutableList().also { it.add(to, it.removeAt(from)) }
        if (!m.shuffle) original = order
        val newIdx = when {
            idx == from -> to
            from < idx && to >= idx -> idx - 1
            from > idx && to <= idx -> idx + 1
            else -> idx
        }
        apply(order, newIdx, engine.positionMs.value)
        if (!wasPlaying) engine.pause()
    }

    /** Puts back the queue, song and position from the last time, paused. Does nothing when something already plays. */
    fun restoreSession() = command {
        if (meta.value.queue.isNotEmpty()) return@command
        val ids = repo.pref(SESSION_QUEUE)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
        val songs = ids.mapNotNull { repo.songByUniqueId(it) }
        if (songs.isEmpty()) return@command
        val idx = (repo.pref(SESSION_INDEX)?.toIntOrNull() ?: 0).coerceIn(songs.indices)
        val pos = repo.pref(SESSION_POS)?.toLongOrNull() ?: 0L
        original = songs
        apply(songs, idx, pos)
        engine.pause()
    }

    private fun saveSession() {
        val q = meta.value.queue
        if (q.isEmpty()) return
        runCatching {
            repo.setPref(SESSION_INDEX, engine.currentIndex.value.coerceAtLeast(0).toString())
            repo.setPref(SESSION_POS, engine.positionMs.value.toString())
        }
    }

    fun next() = command {
        val m = meta.value
        if (m.repeat == Repeat.ALL && engine.currentIndex.value == m.queue.lastIndex) apply(m.queue, 0, 0)
        else engine.next()
    }

    fun previous() = command { engine.previous() }

    /** Reorders the rest of the queue around the song now playing, without restarting it. */
    fun setShuffle(on: Boolean) = command {
        val m = meta.value
        if (m.shuffle == on) return@command
        val idx = engine.currentIndex.value
        val song = m.queue.getOrNull(idx)
        if (song == null) {
            meta.update { Meta(it.queue, on, it.repeat, it.error) }
            return@command
        }
        val order = if (on) {
            val rest = original.toMutableList()
            val at = rest.indexOfFirst { it.unique_id == song.unique_id }
            if (at >= 0) rest.removeAt(at)
            listOf(song) + rest.shuffled()
        } else original
        apply(order, order.indexOfFirst { it.unique_id == song.unique_id }, engine.positionMs.value, shuffle = on)
    }

    /** Puts [song] right after the one playing, or starts it when nothing is. Keeps the position and pause state. */
    fun playNext(song: Song) = command {
        val m = meta.value
        val idx = engine.currentIndex.value
        val cur = m.queue.getOrNull(idx)
        if (cur == null) {
            original = listOf(song)
            apply(listOf(song), 0, 0)
            return@command
        }
        val wasPlaying = engine.state.value == PlayerState.Playing
        val order = m.queue.toMutableList().also { it.add(idx + 1, song) }
        original = original.toMutableList().also {
            val at = it.indexOfFirst { s -> s.unique_id == cur.unique_id }
            it.add(if (at >= 0) at + 1 else it.size, song)
        }
        apply(order, idx, engine.positionMs.value)
        if (!wasPlaying) engine.pause()
    }

    fun setRepeat(repeat: Repeat) = meta.update { Meta(it.queue, it.shuffle, repeat, it.error) }

    private fun restart() = command { apply(meta.value.queue, 0, 0) }

    private fun command(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    private suspend fun apply(order: List<Song>, index: Int, seekMs: Long, shuffle: Boolean = meta.value.shuffle) {
        val song = order[index]
        coroutineScope {
            // The file lookup runs while the queue is handed to the engine; its error text is surfaced afterwards.
            val warm = async { try { resolver.warm(song); null } catch (e: TdError) { e.message } }
            gate.withLock {
                // A newer command arrived: it wins.
                currentCoroutineContext().ensureActive()
                resolver.retain(order)
                meta.value = Meta(order, shuffle, meta.value.repeat, null)
                engine.setQueue(order.map(resolver::resolve), index)
                if (seekMs > 0) engine.seek(seekMs)
            }
            scope.launch { runCatching { resolver.prefetch(song) } }
            warm.await()?.let { err ->
                // The look-up can fail early (the chat is still loading) and succeed when the player asks again. Show the
                // error only if the song does not start; a banner over a song that plays is wrong.
                val played = kotlinx.coroutines.withTimeoutOrNull(8_000) { engine.state.first { it == PlayerState.Playing } } != null
                if (!played) meta.update { Meta(it.queue, it.shuffle, it.repeat, err) }
            }
        }
    }

    /** Stops playback and releases the player. */
    suspend fun release() {
        finishListening()
        engine.release()
        resolver.retain(emptyList())
    }
}
