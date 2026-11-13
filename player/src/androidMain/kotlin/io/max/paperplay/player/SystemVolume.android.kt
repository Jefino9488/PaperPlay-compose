package io.max.paperplay.player

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/** The phone's media stream volume (what the volume keys change while music plays). */
actual object SystemVolume {
    private fun audio() = PlaybackCore.appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    actual fun get(): Float? {
        val a = audio() ?: return null
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) a.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max else null
    }

    actual fun set(volume: Float): Boolean {
        val a = audio() ?: return false
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return false
        return runCatching { a.setStreamVolume(AudioManager.STREAM_MUSIC, (volume.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max), 0) }.isSuccess
    }
}
