package io.max.paperplay.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Call once (e.g. Activity/Application onCreate) before [createPlayerEngine].
 * [upstream] is the hook for non-stream URIs; Telegram files should use [QueueItem.source] instead.
 */
fun initPlayer(context: Context, upstream: DataSource.Factory? = null) {
    PlaybackCore.appContext = context.applicationContext
    PlaybackCore.upstream = upstream
}

/** Main-thread Media3 [MediaController] client driving [PlaybackService]'s shared session. */
class AndroidPlayerEngine : PlayerEngine {
    private val context = requireNotNull(PlaybackCore.appContext) { "Call initPlayer(context) before createPlayerEngine()" }
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { runnable ->
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run() else handler.post(runnable)
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var fullQueue: List<QueueItem> = emptyList()
    private var windowStart = 0
    private var windowEnd = 0
    private var pendingSlideIndex = -1
    private var released = false
    private var failed = false
    private var tickerRunning = false
    private var volumeLevel = 1f

    private val _state = MutableStateFlow(PlayerState.Idle)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _bufferedMs = MutableStateFlow(0L)
    private val _currentIndex = MutableStateFlow(-1)
    override val state: StateFlow<PlayerState> = _state
    override val positionMs: StateFlow<Long> = _positionMs
    override val durationMs: StateFlow<Long> = _durationMs
    override val bufferedMs: StateFlow<Long> = _bufferedMs
    override val currentIndex: StateFlow<Int> = _currentIndex
    private val _info = MutableStateFlow<OutputInfo?>(null)
    override val outputInfo: StateFlow<OutputInfo?> = _info

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.playerError == null && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)) {
                failed = false
            }
            if (events.containsAny(Player.EVENT_TRACKS_CHANGED, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, Player.EVENT_DEVICE_VOLUME_CHANGED)) publishInfo(player)
            sync()
        }

        override fun onPlayerError(error: PlaybackException) {
            failed = true
            sync()
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(mc: MediaController) {
            if (controller === mc) {
                mc.removeListener(playerListener)
                controller = null
                controllerFuture = null
                stopTicker()
                _state.value = PlayerState.Idle
                _positionMs.value = 0L
                _durationMs.value = 0L
                _bufferedMs.value = 0L
                _currentIndex.value = -1
            }
        }
    }

    /** Set by the UI while a full player view needs smooth progress; otherwise the position ticks at 500 ms. */
    @Volatile var fastTicks: Boolean = false

    private fun pollMs() = if (fastTicks) FAST_POLL_MS else POSITION_POLL_MS

    private val ticker = object : Runnable {
        override fun run() {
            val mc = controller
            if (mc == null || !mc.isConnected || released || !shouldTick(mc)) {
                tickerRunning = false
                return
            }
            _positionMs.value = mc.currentPosition.coerceAtLeast(0L)
            _bufferedMs.value = mc.bufferedPosition.coerceAtLeast(0L)
            handler.postDelayed(this, pollMs())
        }
    }

    private val slideRunnable = Runnable {
        val idx = pendingSlideIndex
        pendingSlideIndex = -1
        val mc = controller ?: return@Runnable
        if (!released && mc.isConnected && idx in fullQueue.indices) {
            slideWindow(mc, idx)
        }
    }

    init {
        onMain { connect() }
    }

    private fun shouldTick(p: Player): Boolean =
        p.isPlaying || p.playbackState == Player.STATE_BUFFERING

    private fun updateTicker(p: Player?) {
        val want = p != null && !released && shouldTick(p)
        if (want && !tickerRunning) {
            tickerRunning = true
            handler.postDelayed(ticker, pollMs())
        } else if (!want && tickerRunning) {
            stopTicker()
        }
    }

    private fun stopTicker() {
        tickerRunning = false
        handler.removeCallbacks(ticker)
    }

    private fun connect() {
        if (released || controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setApplicationLooper(Looper.getMainLooper())
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener({
            if (released) {
                pending.clear()
                return@addListener
            }
            val mc = runCatching { future.get() }.getOrNull()
            if (mc == null) {
                if (controllerFuture === future) controllerFuture = null
                failed = true
                sync()
                pending.clear()
                return@addListener
            }
            controller = mc
            mc.addListener(playerListener)
            if (volumeLevel < 1f) mc.volume = volumeLevel
            PlaybackCore.eqGains?.let { sendEqualizer(mc, it) }
            if (PlaybackCore.normalize) sendNormalize(mc, true)
            if (PlaybackCore.speed != 1f) mc.setPlaybackSpeed(PlaybackCore.speed)
            while (pending.isNotEmpty()) {
                pending.removeFirst().invoke(mc)
            }
            sync()
        }, mainExecutor)
    }

    private fun currentFullIndex(mc: MediaController): Int {
        if (mc.mediaItemCount == 0 || fullQueue.isEmpty()) return -1
        val id = mc.currentMediaItem?.mediaId
        val parsed = id?.substringBefore(':')?.toIntOrNull()
        if (parsed != null && parsed in fullQueue.indices) return parsed
        return (windowStart + mc.currentMediaItemIndex).coerceIn(fullQueue.indices)
    }

    private fun scheduleSlide(fullIndex: Int) {
        pendingSlideIndex = fullIndex
        handler.removeCallbacks(slideRunnable)
        handler.post(slideRunnable)
    }

    private fun sync() {
        val mc = controller
        if (mc == null || !mc.isConnected) {
            _state.value = if (failed) PlayerState.Error else PlayerState.Idle
            _durationMs.value = 0L
            _bufferedMs.value = 0L
            _currentIndex.value = -1
            _positionMs.value = 0L
            updateTicker(null)
            return
        }
        val hasError = failed || mc.playerError != null
        _state.value = when {
            hasError -> PlayerState.Error
            mc.mediaItemCount == 0 -> PlayerState.Idle
            else -> when (mc.playbackState) {
                Player.STATE_BUFFERING -> PlayerState.Loading
                Player.STATE_READY -> if (mc.playWhenReady) PlayerState.Playing else PlayerState.Paused
                Player.STATE_ENDED -> PlayerState.Ended
                else -> PlayerState.Idle
            }
        }
        _durationMs.value = mc.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        _bufferedMs.value = mc.bufferedPosition.coerceAtLeast(0L)
        val fullIdx = currentFullIndex(mc)
        _currentIndex.value = fullIdx
        _positionMs.value = mc.currentPosition.coerceAtLeast(0L)
        if (fullIdx in fullQueue.indices) scheduleSlide(fullIdx)
        updateTicker(mc)
    }

    /** MediaController may only be used from its application (main) thread; callers may be on any thread. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun withController(block: (MediaController) -> Unit) = onMain {
        if (released) return@onMain
        val mc = controller
        if (mc != null && mc.isConnected) {
            block(mc)
        } else {
            pending.addLast(block)
            connect()
        }
    }

    override fun setQueue(items: List<QueueItem>, startIndex: Int) = withController { mc ->
        setQueueNow(mc, items, startIndex)
    }

    private fun streamKey(item: QueueItem): String = "stream://${item.uri}"

    private fun toMediaItem(index: Int, item: QueueItem): MediaItem {
        val src = item.source
        val uri = if (src != null) streamKey(item) else item.uri
        return MediaItem.Builder()
            .setMediaId("$index:${item.uri}")
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setArtworkUri(parseArtworkUri(item.artworkUri))
                    .build(),
            )
            .build()
    }

    private fun registerRange(start: Int, endExclusive: Int) {
        for (i in start until endExclusive) {
            val item = fullQueue[i]
            val src = item.source ?: continue
            PlaybackCore.sources[streamKey(item)] = src
        }
    }

    private fun retainActiveWindowSources() {
        val keep = HashSet<String>()
        for (i in windowStart until windowEnd) {
            val item = fullQueue[i]
            if (item.source != null) keep.add(streamKey(item))
        }
        PlaybackCore.sources.keys.retainAll(keep)
    }

    /**
     * Keeps only `[centerIndex - 1, centerIndex + 1]` loaded in [MediaController], sliding smoothly
     * as playback moves so queues of any size stay lightweight and never exceed Binder IPC limits.
     */
    private fun slideWindow(mc: MediaController, centerIndex: Int) {
        if (fullQueue.isEmpty() || centerIndex !in fullQueue.indices) return
        val wantStart = maxOf(0, centerIndex - 1)
        val wantEnd = minOf(fullQueue.size, centerIndex + 2)
        if (wantStart == windowStart && wantEnd == windowEnd) return

        if (wantStart < windowStart) registerRange(wantStart, windowStart)
        if (wantEnd > windowEnd) registerRange(windowEnd, wantEnd)

        if (wantEnd > windowEnd) {
            val append = (windowEnd until wantEnd).map { toMediaItem(it, fullQueue[it]) }
            windowEnd = wantEnd
            mc.addMediaItems(append)
        }
        if (wantStart < windowStart) {
            val prepend = (wantStart until windowStart).map { toMediaItem(it, fullQueue[it]) }
            windowStart = wantStart
            mc.addMediaItems(0, prepend)
        }
        if (wantEnd < windowEnd) {
            val from = wantEnd - windowStart
            val to = windowEnd - windowStart
            windowEnd = wantEnd
            if (from in 0 until to && to <= mc.mediaItemCount) mc.removeMediaItems(from, to)
        }
        if (wantStart > windowStart) {
            val count = minOf(wantStart - windowStart, mc.mediaItemCount)
            windowStart += count
            if (count > 0) mc.removeMediaItems(0, count)
        }
        retainActiveWindowSources()
    }

    private fun setQueueNow(mc: MediaController, items: List<QueueItem>, startIndex: Int) {
        failed = false
        handler.removeCallbacks(slideRunnable)
        pendingSlideIndex = -1
        if (items.isEmpty()) {
            fullQueue = emptyList()
            windowStart = 0
            windowEnd = 0
            PlaybackCore.sources.clear()
            mc.stop()
            mc.clearMediaItems()
            sync()
            return
        }
        fullQueue = items
        val target = startIndex.coerceIn(items.indices)
        windowStart = maxOf(0, target - 1)
        windowEnd = minOf(items.size, target + 2)
        registerRange(windowStart, windowEnd)
        retainActiveWindowSources()
        val mediaItems = (windowStart until windowEnd).map { toMediaItem(it, items[it]) }
        mc.setMediaItems(mediaItems, target - windowStart, 0L)
        mc.prepare()
        mc.play()
    }

    override fun play() = withController { mc ->
        failed = false
        if (mc.playbackState == Player.STATE_ENDED && mc.mediaItemCount > 0) {
            mc.seekToDefaultPosition(mc.currentMediaItemIndex.coerceAtLeast(0))
        } else if (mc.playbackState == Player.STATE_IDLE && mc.mediaItemCount > 0) {
            mc.prepare()
        }
        mc.play()
    }

    override fun pause() = withController { it.pause() }

    override fun next() = withController { mc ->
        failed = false
        val cur = currentFullIndex(mc)
        if (cur in fullQueue.indices) {
            if (mc.hasNextMediaItem()) {
                mc.seekToNextMediaItem()
                slideWindow(mc, cur + 1)
            } else if (cur + 1 < fullQueue.size) {
                setQueueNow(mc, fullQueue, cur + 1)
            }
        }
    }

    override fun previous() = withController { mc ->
        failed = false
        val cur = currentFullIndex(mc)
        if (cur in fullQueue.indices) {
            val threshold = mc.maxSeekToPreviousPosition
            if (mc.currentPosition <= threshold && !mc.hasPreviousMediaItem() && cur > 0) {
                setQueueNow(mc, fullQueue, cur - 1)
            } else {
                val target = if (mc.currentPosition <= threshold && mc.hasPreviousMediaItem()) cur - 1 else cur
                mc.seekToPrevious()
                slideWindow(mc, target)
            }
        } else {
            mc.seekToPrevious()
        }
    }

    override fun seek(positionMs: Long) = withController { failed = false; it.seekTo(positionMs.coerceAtLeast(0L)) }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        onMain {
            volumeLevel = clamped
            controller?.takeIf { it.isConnected }?.let { it.volume = clamped; publishInfo(it) }
        }
    }

    override fun setEqualizer(gainsDb: List<Float>?) {
        PlaybackCore.eqGains = gainsDb
        onMain {
            val mc = controller ?: return@onMain
            if (mc.isConnected) sendEqualizer(mc, gainsDb)
            publishInfo(mc)
        }
    }

    override fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.5f, 2f)
        PlaybackCore.speed = s
        onMain { controller?.takeIf { it.isConnected }?.setPlaybackSpeed(s) }
    }

    override fun setNormalize(on: Boolean) {
        PlaybackCore.normalize = on
        onMain {
            val mc = controller ?: return@onMain
            if (mc.isConnected) sendNormalize(mc, on)
            publishInfo(mc)
        }
    }

    /** The decoded track's format from Media3, plus what Android says about the output device and the effects in the path. */
    private fun publishInfo(player: Player) {
        val format = player.currentTracks.groups
            .firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val outputs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val external = outputs.firstOrNull {
            it.type in setOf(
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            )
        }
        val outRate = am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        val effects = buildList {
            if (PlaybackCore.eqGains?.any { kotlin.math.abs(it) >= 0.05f } == true) add("Equalizer")
            if (PlaybackCore.normalize) add("Normalise")
            if (PlaybackCore.speed != 1f) add("Speed ${PlaybackCore.speed}x")
            if (volumeLevel < 0.995f) add("Volume ${(volumeLevel * 100).toInt()}%")
        }
        val rate = format?.sampleRate?.takeIf { it > 0 }
        val depth = when (format?.pcmEncoding) {
            androidx.media3.common.C.ENCODING_PCM_16BIT -> 16
            androidx.media3.common.C.ENCODING_PCM_24BIT -> 24
            androidx.media3.common.C.ENCODING_PCM_32BIT, androidx.media3.common.C.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
        val matches = rate != null && outRate != null && rate == outRate
        _info.value = OutputInfo(
            codec = format?.sampleMimeType?.substringAfter('/')?.uppercase(),
            sampleRateHz = rate,
            channels = format?.channelCount?.takeIf { it > 0 },
            bitrateKbps = format?.averageBitrate?.takeIf { it > 0 }?.let { it / 1000 },
            bitDepth = depth,
            device = external?.productName?.toString()?.takeIf { it.isNotBlank() } ?: (if (external != null) "Headphones" else android.os.Build.MODEL),
            outputRateHz = outRate,
            effects = effects,
            bitPerfect = effects.isEmpty() && matches && external == null,
            note = when {
                effects.isNotEmpty() -> "Effects change the sound before it reaches the output."
                !matches -> "The device mixes at ${outRate ?: "an unknown"} Hz, so this track is resampled."
                else -> "Android's mixer can still convert the format; only a bit-perfect USB DAC route skips it."
            },
        )
    }

    private fun sendNormalize(mc: MediaController, on: Boolean) {
        mc.sendCustomCommand(PlaybackCore.CMD_SET_NORMALIZE, Bundle().apply { putBoolean(PlaybackCore.EXTRA_NORMALIZE, on) })
    }

    private fun sendEqualizer(mc: MediaController, gainsDb: List<Float>?) {
        val args = Bundle().apply {
            if (gainsDb != null) putFloatArray(PlaybackCore.EXTRA_EQ_GAINS, gainsDb.toFloatArray())
        }
        mc.sendCustomCommand(PlaybackCore.CMD_SET_EQUALIZER, args)
    }

    override fun release() = onMain {
        released = true
        stopTicker()
        handler.removeCallbacks(slideRunnable)
        pending.clear()
        fullQueue = emptyList()
        windowStart = 0
        windowEnd = 0
        controller?.let { mc ->
            mc.removeListener(playerListener)
            if (mc.isConnected) {
                mc.stop()
                mc.clearMediaItems()
            }
        }
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        PlaybackCore.sources.clear()
        _state.value = PlayerState.Idle
        _positionMs.value = 0L
        _durationMs.value = 0L
        _bufferedMs.value = 0L
        _currentIndex.value = -1
    }

    private fun parseArtworkUri(raw: String?): Uri? {
        if (raw.isNullOrBlank()) return null
        val parsed = Uri.parse(raw)
        return if (parsed.scheme == null) Uri.fromFile(File(raw)) else parsed
    }

    private companion object {
        const val POSITION_POLL_MS = 500L
        const val FAST_POLL_MS = 250L
    }
}

actual fun createPlayerEngine(): PlayerEngine = AndroidPlayerEngine()
