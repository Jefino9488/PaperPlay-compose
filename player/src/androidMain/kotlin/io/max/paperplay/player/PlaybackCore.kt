package io.max.paperplay.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.ConcurrentHashMap

/** Process-wide ExoPlayer shared by [AndroidPlayerEngine] and [PlaybackService]. */
internal object PlaybackCore {
    lateinit var appContext: Context
    var upstream: DataSource.Factory? = null
    val sources = ConcurrentHashMap<String, StreamSource>()

    private var exo: ExoPlayer? = null

    @Synchronized
    fun player(): ExoPlayer = exo ?: createPlayer().also { exo = it }

    @Synchronized
    fun release() {
        exo?.release()
        exo = null
        sources.clear()
    }

    private fun createPlayer(): ExoPlayer {
        val up = upstream ?: DefaultDataSource.Factory(appContext)
        val factory = DataSource.Factory { RoutingDataSource(sources, StreamDataSource(sources), up.createDataSource()) }
        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true, // audio focus
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}
