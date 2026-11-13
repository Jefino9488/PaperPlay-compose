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

/** Centre frequencies of the ten equalizer bands, one octave apart. Devices with fewer bands read the curve between them. */
val EQ_BANDS_HZ = listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

private val OLD_EQ_BANDS_HZ = listOf(60, 230, 910, 3600, 14000)

private fun curveAt(centres: List<Int>, gains: List<Float>, hz: Double): Float {
    val x = kotlin.math.ln(hz.coerceIn(centres.first().toDouble(), centres.last().toDouble()))
    val i = centres.indices.last { it == 0 || kotlin.math.ln(centres[it].toDouble()) <= x }.coerceAtMost(centres.size - 2)
    val a = kotlin.math.ln(centres[i].toDouble()); val b = kotlin.math.ln(centres[i + 1].toDouble())
    return gains[i] + (gains[i + 1] - gains[i]) * ((x - a) / (b - a)).toFloat()
}

/** The gain of the curve through [gains] (one per [EQ_BANDS_HZ]) at [hz], interpolated on a log frequency axis. */
fun eqGainAt(gains: List<Float>, hz: Double): Float = if (gains.size != EQ_BANDS_HZ.size) 0f else curveAt(EQ_BANDS_HZ, gains, hz)

/** Gains saved by an older build (five bands) become ten by following their curve; anything else not of ten values is flat. */
fun migrateEqGains(saved: List<Float>?): List<Float> = when (saved?.size) {
    EQ_BANDS_HZ.size -> saved
    OLD_EQ_BANDS_HZ.size -> EQ_BANDS_HZ.map { curveAt(OLD_EQ_BANDS_HZ, saved, it.toDouble()) }
    else -> List(EQ_BANDS_HZ.size) { 0f }
}

/** What is being played and how it reaches the speakers, for the output info screen. Null fields are unknown on this platform. */
data class OutputInfo(
    val codec: String? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val bitrateKbps: Int? = null,
    val bitDepth: Int? = null,
    /** Where the sound goes: a device name, or the engine's description of the output. */
    val device: String? = null,
    /** Sample rate the output runs at, when the platform says. */
    val outputRateHz: Int? = null,
    /** Everything in the path that changes the sound: equalizer, normalise, speed, volume. */
    val effects: List<String> = emptyList(),
    /** True only when nothing alters the samples between the file and the output. */
    val bitPerfect: Boolean = false,
    val note: String? = null,
)

internal val NoOutputInfo: StateFlow<OutputInfo?> = kotlinx.coroutines.flow.MutableStateFlow(null)

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
    /** Describes the current track's source format and output path; null until known. */
    val outputInfo: StateFlow<OutputInfo?> get() = NoOutputInfo
    /** Gains in dB for the bands of [EQ_BANDS_HZ], or null to turn the equalizer off. */
    fun setEqualizer(gainsDb: List<Float>?) {}
    /** Playback speed, 1 is normal. */
    fun setSpeed(speed: Float) {}
    /** Evens out loud and quiet songs. */
    fun setNormalize(on: Boolean) {}
    fun release()

    fun play(uri: String) = setQueue(listOf(QueueItem(uri)))
}

expect fun createPlayerEngine(): PlayerEngine
