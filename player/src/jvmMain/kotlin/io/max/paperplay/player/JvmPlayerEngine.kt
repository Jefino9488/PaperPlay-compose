package io.max.paperplay.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.util.concurrent.Executors

/**
 * VLCJ-backed engine; needs a system VLC install (libvlc). All libvlc calls run on one
 * executor thread because vlcj forbids calling the player from its event thread.
 * [vlcArgs] are extra libvlc options (tests pass "--aout=dummy").
 */
class JvmPlayerEngine(vararg vlcArgs: String) : PlayerEngine {
    private val _state = MutableStateFlow(PlayerState.Idle)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _currentIndex = MutableStateFlow(-1)
    override val state: StateFlow<PlayerState> = _state
    override val positionMs: StateFlow<Long> = _positionMs
    override val durationMs: StateFlow<Long> = _durationMs
    override val currentIndex: StateFlow<Int> = _currentIndex

    private val factory = MediaPlayerFactory("--no-video", *vlcArgs)
    private val player = factory.mediaPlayers().newMediaPlayer()
    private val http = lazy { StreamHttpServer() }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-cmd").apply { isDaemon = true } }
    private var queue = emptyList<QueueItem>()

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) { _state.value = PlayerState.Playing }
            override fun paused(mp: MediaPlayer) { _state.value = PlayerState.Paused }
            override fun error(mp: MediaPlayer) { _state.value = PlayerState.Error }
            override fun lengthChanged(mp: MediaPlayer, newLength: Long) { _durationMs.value = newLength }
            override fun timeChanged(mp: MediaPlayer, newTime: Long) { _positionMs.value = newTime }
            override fun finished(mp: MediaPlayer) = cmd {
                if (_currentIndex.value + 1 < queue.size) start(_currentIndex.value + 1)
                else _state.value = PlayerState.Ended
            }
        })
    }

    private fun cmd(block: () -> Unit) { io.execute(block) }

    private fun start(index: Int) {
        val item = queue[index]
        _currentIndex.value = index
        _state.value = PlayerState.Loading
        _positionMs.value = 0
        _durationMs.value = 0
        val mrl = item.source?.let { http.value.register(item.uri, it) } ?: item.uri
        player.media().play(mrl)
    }

    override fun setQueue(items: List<QueueItem>, startIndex: Int) = cmd {
        queue = items
        if (items.isEmpty()) {
            player.controls().stop()
            _currentIndex.value = -1
            _state.value = PlayerState.Idle
        } else start(startIndex.coerceIn(items.indices))
    }

    override fun play() = cmd {
        if (_state.value == PlayerState.Ended) start(_currentIndex.value) else player.controls().play()
    }

    override fun pause() = cmd { player.controls().setPause(true) }

    override fun next() = cmd { if (_currentIndex.value + 1 < queue.size) start(_currentIndex.value + 1) }

    override fun previous() = cmd {
        val i = _currentIndex.value
        if (i > 0 && _positionMs.value <= RESTART_THRESHOLD_MS) start(i - 1) else if (i >= 0) player.controls().setTime(0)
    }

    override fun seek(positionMs: Long) = cmd { player.controls().setTime(positionMs) }

    override fun release() {
        io.shutdown()
        io.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        player.release()
        factory.release()
        if (http.isInitialized()) http.value.close()
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 3000L
    }
}

actual fun createPlayerEngine(): PlayerEngine = JvmPlayerEngine()
