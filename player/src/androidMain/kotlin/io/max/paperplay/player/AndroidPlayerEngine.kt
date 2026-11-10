package io.max.paperplay.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidPlayerEngine : PlayerEngine {
    override val state: StateFlow<PlayerState> = MutableStateFlow(PlayerState.Idle)
    override val positionMs: StateFlow<Long> = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = MutableStateFlow(0L)

    override fun play(uri: String) = TODO()
    override fun pause() = TODO()
    override fun seek(positionMs: Long) = TODO()
    override fun release() {}
}

actual fun createPlayerEngine(): PlayerEngine = AndroidPlayerEngine()
