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
 * [uri] is played directly unless [source] is set; then [uri] is only a unique key
 * (URL-safe, e.g. "tg-123") and bytes come from [source].
 */
class QueueItem(
    val uri: String,
    val title: String = "",
    val artist: String = "",
    val artworkUri: String? = null,
    val source: StreamSource? = null,
)

interface PlayerEngine {
    val state: StateFlow<PlayerState>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    /** Index into the current queue, -1 when empty. */
    val currentIndex: StateFlow<Int>

    /** Replaces the queue and starts playing at [startIndex]. */
    fun setQueue(items: List<QueueItem>, startIndex: Int = 0)
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seek(positionMs: Long)
    fun release()

    fun play(uri: String) = setQueue(listOf(QueueItem(uri)))
}

expect fun createPlayerEngine(): PlayerEngine
