package io.max.paperplay.core.app

import io.max.paperplay.core.library.Song
import io.max.paperplay.core.telegram.TdError
import io.max.paperplay.player.PlayerEngine
import io.max.paperplay.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Repeat { OFF, ALL, ONE }

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
    private val gate = Mutex()

    // Listen counting for the song that is currently playing.
    private var listening: Song? = null
    private var listenedMs = 0L

    private val timing = combine(engine.positionMs, engine.durationMs, engine.bufferedMs) { pos, dur, buf ->
        Triple(pos, dur, buf)
    }

    val nowPlaying: StateFlow<NowPlaying> = combine(
        engine.state, timing, engine.currentIndex, meta,
    ) { state, (pos, dur, buf), idx, m ->
        val song = m.queue.getOrNull(idx)
        NowPlaying(
            song, pos, if (dur > 0) dur else (song?.duration_sec ?: 0) * 1000, state == PlayerState.Playing,
            idx, m.queue, m.shuffle, m.repeat, m.error, buf,
        )
    }.stateIn(scope, SharingStarted.Eagerly, NowPlaying())

    init {
        scope.launch {
            engine.currentIndex.collect { idx ->
                val song = meta.value.queue.getOrNull(idx)
                if (song?.unique_id != listening?.unique_id) {
                    finishListening()
                    listening = song
                }
                runCatching { resolver.window(song, meta.value.queue.getOrNull(idx + 1)) }
                if (song != null) launch { learnAudioDetails(song) }
            }
        }
        scope.launch { engine.positionMs.collect { listenedMs = maxOf(listenedMs, it) } }
        scope.launch {
            engine.state.collect { state ->
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
        repeat(15) {
            delay(2_000)
            if (engine.currentIndex.value < 0 || meta.value.queue.getOrNull(engine.currentIndex.value)?.unique_id != song.unique_id) return
            val head = runCatching { resolver.headOf(song) }.getOrNull() ?: return@repeat
            val h = parseAudioHeader(head)
            if (h != null) repo.setAudioDetails(song.unique_id, h)
            return
        }
    }

    /** Called when a song stops, with how long it was heard. Set by the owner, for scrobbling. */
    @kotlin.concurrent.Volatile
    var onListened: ((Song, Long) -> Unit)? = null

    private fun finishListening() {
        val song = listening ?: return
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
        val order = if (meta.value.shuffle) listOf(songs[start]) + (songs - songs[start]).shuffled() else songs
        apply(order, if (meta.value.shuffle) 0 else start, 0)
    }

    fun play() = engine.play()
    fun pause() = engine.pause()
    fun seek(positionMs: Long) = engine.seek(positionMs)
    fun setVolume(volume: Float) = engine.setVolume(volume)
    fun setEqualizer(gainsDb: List<Float>?) = engine.setEqualizer(gainsDb)

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
        val order = if (on) listOf(song) + (original - song).shuffled() else original
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
        var error: String? = null
        try {
            resolver.warm(order[index])
        } catch (e: TdError) {
            error = e.message
        }
        gate.withLock {
            // A newer command arrived while the file was looked up: it wins.
            currentCoroutineContext().ensureActive()
            resolver.retain(order)
            meta.value = Meta(order, shuffle, meta.value.repeat, error)
            engine.setQueue(order.map(resolver::resolve), index)
            if (seekMs > 0) engine.seek(seekMs)
        }
    }

    /** Stops playback and releases the player. */
    suspend fun release() {
        finishListening()
        engine.release()
        resolver.retain(emptyList())
    }
}
