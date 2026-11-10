package io.max.paperplay.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
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

/** Main-thread only. Playback runs in [PlaybackService]'s session on the shared player. */
class AndroidPlayerEngine : PlayerEngine {
    private val context = PlaybackCore.appContext
    private val player = PlaybackCore.player()
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(PlayerState.Idle)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _currentIndex = MutableStateFlow(-1)
    override val state: StateFlow<PlayerState> = _state
    override val positionMs: StateFlow<Long> = _positionMs
    override val durationMs: StateFlow<Long> = _durationMs
    override val currentIndex: StateFlow<Int> = _currentIndex

    private var failed = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
        override fun onPlayerError(error: PlaybackException) {
            failed = true
            sync()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            _positionMs.value = player.currentPosition
            handler.postDelayed(this, POSITION_POLL_MS)
        }
    }

    init {
        player.addListener(listener)
        sync()
        handler.post(ticker)
    }

    private fun sync() {
        _state.value = when {
            failed -> PlayerState.Error
            player.mediaItemCount == 0 -> PlayerState.Idle
            else -> when (player.playbackState) {
                Player.STATE_BUFFERING -> PlayerState.Loading
                Player.STATE_READY -> if (player.playWhenReady) PlayerState.Playing else PlayerState.Paused
                Player.STATE_ENDED -> PlayerState.Ended
                else -> PlayerState.Idle
            }
        }
        _durationMs.value = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        _currentIndex.value = if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex
        _positionMs.value = player.currentPosition
    }

    override fun setQueue(items: List<QueueItem>, startIndex: Int) {
        failed = false
        PlaybackCore.sources.clear()
        val mediaItems = items.map { item ->
            val uri = if (item.source != null) {
                "stream://${item.uri}".also { PlaybackCore.sources[it] = item.source }
            } else item.uri
            MediaItem.Builder()
                .setMediaId(item.uri)
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .setArtworkUri(item.artworkUri?.let(Uri::parse))
                        .build(),
                )
                .build()
        }
        context.startService(Intent(context, PlaybackService::class.java))
        player.setMediaItems(mediaItems, startIndex.coerceIn(0, maxOf(0, items.lastIndex)), 0L)
        player.prepare()
        player.play()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun next() = player.seekToNextMediaItem()
    override fun previous() = player.seekToPreviousMediaItem()
    override fun seek(positionMs: Long) = player.seekTo(positionMs)

    override fun release() {
        handler.removeCallbacks(ticker)
        player.removeListener(listener)
        context.stopService(Intent(context, PlaybackService::class.java))
        PlaybackCore.release()
    }

    private companion object {
        const val POSITION_POLL_MS = 250L
    }
}

actual fun createPlayerEngine(): PlayerEngine = AndroidPlayerEngine()
