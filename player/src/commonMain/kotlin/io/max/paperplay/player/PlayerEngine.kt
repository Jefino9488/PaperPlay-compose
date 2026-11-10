package io.max.paperplay.player

import kotlinx.coroutines.flow.StateFlow

enum class PlayerState { Idle, Loading, Playing, Paused, Ended, Error }

interface PlayerEngine {
    val state: StateFlow<PlayerState>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>

    fun play(uri: String)
    fun pause()
    fun seek(positionMs: Long)
    fun release()
}

expect fun createPlayerEngine(): PlayerEngine
