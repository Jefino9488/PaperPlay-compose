package io.max.paperplay.player

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.abs
import kotlin.math.ln

/** Owns the [ExoPlayer], [Equalizer], and [MediaSession] + notification for background playback. */
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var eq: Equalizer? = null
    private var leveller: DynamicsProcessing? = null

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) { applyEqualizer(audioSessionId); applyNormalize(audioSessionId) }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (controller.packageName == packageName) {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(PlaybackCore.CMD_SET_EQUALIZER)
                    .add(PlaybackCore.CMD_SET_NORMALIZE)
                    .build()
                return MediaSession.ConnectionResult.accept(
                    sessionCommands,
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
                )
            }
            return super.onConnect(session, controller)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == PlaybackCore.ACTION_SET_EQUALIZER && controller.packageName == packageName) {
                PlaybackCore.eqGains = args.getFloatArray(PlaybackCore.EXTRA_EQ_GAINS)?.toList()
                applyEqualizer(player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == PlaybackCore.ACTION_SET_NORMALIZE && controller.packageName == packageName) {
                PlaybackCore.normalize = args.getBoolean(PlaybackCore.EXTRA_NORMALIZE, false)
                applyNormalize(player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // The media notification's small icon is the app's paper plane, not Media3's round play icon. The drawable
        // lives in the app module, so it is looked up by name.
        resources.getIdentifier("ic_stat_paperplay", "drawable", packageName).takeIf { it != 0 }?.let { icon ->
            setMediaNotificationProvider(androidx.media3.session.DefaultMediaNotificationProvider.Builder(this).build().also { it.setSmallIcon(icon) })
        }
        val exo = PlaybackCore.createPlayer(this)
        exo.addListener(playerListener)
        player = exo
        applyEqualizer(exo.audioSessionId)
        applyNormalize(exo.audioSessionId)

        // A tap on the notification opens the app on the player.
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?.putExtra("open", "player")
        val builder = MediaSession.Builder(this, exo)
            .setCallback(sessionCallback)
        if (open != null) {
            builder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    1,
                    open,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { eq?.release() }
        eq = null
        runCatching { leveller?.release() }
        leveller = null
        session?.release()
        session = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        super.onDestroy()
    }

    /** A limiter that pulls loud peaks down and lifts the rest, so songs mastered at different levels sound alike. Needs Android 9. */
    private fun applyNormalize(audioSessionId: Int) {
        runCatching { leveller?.release() }
        leveller = null
        if (!PlaybackCore.normalize || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) return
        runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, 2, false, 0, false, 0, false, 0, true,
            ).build()
            val dp = DynamicsProcessing(0, audioSessionId, config)
            dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 5f, 80f, 6f, -14f, 6f))
            dp.enabled = true
            leveller = dp
        }
    }

    /** Device equalizers have a handful of bands, so each reads the curve through [EQ_BANDS_HZ] at its own frequency. */
    private fun applyEqualizer(audioSessionId: Int) {
        runCatching { eq?.release() }
        eq = null
        val gains = PlaybackCore.eqGains ?: return
        if (gains.all { abs(it) < 0.05f }) return
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) return
        runCatching {
            val e = Equalizer(0, audioSessionId)
            val range = e.bandLevelRange
            for (b in 0 until e.numberOfBands) {
                val hz = e.getCenterFreq(b.toShort()) / 1000.0
                if (hz <= 0.0) continue
                val level = (eqGainAt(gains, hz) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                e.setBandLevel(b.toShort(), level.toShort())
            }
            e.enabled = true
            eq = e
        }
    }
}
