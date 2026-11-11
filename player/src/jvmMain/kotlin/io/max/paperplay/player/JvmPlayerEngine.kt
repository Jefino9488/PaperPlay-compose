package io.max.paperplay.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.util.concurrent.Executors

/**
 * VLCJ-backed engine for desktop JVM playback.
 *
 * Supports every audio format and lossless container that libvlc provides:
 * FLAC, ALAC (Apple Lossless), AAC / M4A, MP3, Opus, Vorbis (OGG), WAV, AIFF,
 * WMA, TTA, Musepack, Matroska audio, and more — all decoded via the bundled
 * libvlc runtime without requiring VLC to be installed by the end user.
 *
 * [VlcLoader.init] is called once per process (before the first factory) to
 * locate the bundled libvlc and point libvlc at its own plugin directory.
 * All libvlc calls run on one dedicated executor thread because vlcj forbids
 * calling the player from its own event thread.
 *
 * [vlcArgs] are extra libvlc options; tests pass "--aout=dummy" to prevent
 * real audio device access.
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

    private val factory = run {
        val loaderArgs = VlcLoader.init()
        // Prepend plugin-path and no-video before any caller-supplied vlcArgs.
        MediaPlayerFactory("--no-video", *loaderArgs.toTypedArray(), *vlcArgs)
    }
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
    override fun setVolume(volume: Float) = cmd { player.audio().setVolume((volume.coerceIn(0f, 1f) * 100).toInt()) }

    override fun setEqualizer(gainsDb: List<Float>?) = cmd {
        if (gainsDb == null) {
            player.audio().setEqualizer(null)
        } else {
            val eq = factory.equalizer().newEqualizer()
            // VLC has ten bands; each takes the gain of the nearest of ours.
            val vlcHz = listOf(60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000)
            vlcHz.forEachIndexed { b, hz ->
                val i = EQ_BANDS_HZ.indices.minByOrNull { kotlin.math.abs(kotlin.math.ln(EQ_BANDS_HZ[it].toDouble() / hz)) } ?: 0
                eq.setAmp(b, gainsDb.getOrElse(i) { 0f }.coerceIn(-12f, 12f))
            }
            player.audio().setEqualizer(eq)
        }
    }

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
