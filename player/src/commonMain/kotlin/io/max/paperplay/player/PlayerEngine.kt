package io.max.paperplay.player

import kotlinx.coroutines.flow.StateFlow

enum class PlayerState { Idle, Loading, Playing, Paused, Ended, Error }

/** Random-access byte source, e.g. a partially downloaded Telegram file. */
interface StreamSource {
    /** Total size in bytes. */
    val size: Long

    /**
     * Reads up to [length] bytes at [offset]. May block until the range is available.
     * Returns fewer bytes only at end of data; an empty array means offset >= size.
     */
    fun read(offset: Long, length: Int): ByteArray
}

/**
 * [uri] is played directly unless a stream source is supplied; then [uri] is only a unique key
 * (URL-safe, e.g. "tg-123") and bytes come from [source]. [sourceFactory] resolves the stream lazily
 * on first access so large queues do not allocate sources for unplayed tracks.
 */
class QueueItem(
    val uri: String,
    val title: String = "",
    val artist: String = "",
    val artworkUri: String? = null,
    source: StreamSource? = null,
    private val sourceFactory: (() -> StreamSource)? = null,
) {
    private val lazySource: StreamSource? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        source ?: sourceFactory?.invoke()
    }
    val source: StreamSource? get() = lazySource
}

/** Centre frequencies of the five equalizer bands every device can approximate. */
val EQ_BANDS_HZ = listOf(60, 230, 910, 3600, 14000)

interface PlayerEngine {
    val state: StateFlow<PlayerState>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val bufferedMs: StateFlow<Long>
    /** Index into the current queue, -1 when empty. */
    val currentIndex: StateFlow<Int>

    /** Replaces the queue and starts playing at [startIndex]. */
    fun setQueue(items: List<QueueItem>, startIndex: Int = 0)
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seek(positionMs: Long)
    /** 0 to 1. */
    fun setVolume(volume: Float)
    /** Gains in dB for the five bands of [EQ_BANDS_HZ], or null to turn the equalizer off. */
    fun setEqualizer(gainsDb: List<Float>?) {}
    /** Playback speed, 1 is normal. */
    fun setSpeed(speed: Float) {}
    /** Evens out loud and quiet songs. */
    fun setNormalize(on: Boolean) {}
    fun release()

    fun play(uri: String) = setQueue(listOf(QueueItem(uri)))
}

expect fun createPlayerEngine(): PlayerEngine
