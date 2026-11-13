package io.max.paperplay.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import io.max.paperplay.ui.app.ListActions
import io.max.paperplay.ui.app.PlayerControls
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.state.PositionUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.max.paperplay.ui.app.UiPrefs
import io.max.paperplay.ui.state.SongUi
import io.max.paperplay.ui.theme.PaperPlayTheme

/** Shared sample data and wrapper for `@Preview` functions. Not used by the app itself. */
@Composable
internal fun PreviewFrame(dark: Boolean = false, content: @Composable () -> Unit) {
    PaperPlayTheme(dark = dark, dynamic = false) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

internal val previewSongs = listOf(
    SongUi("1", "Supermassive Black Hole", "Muse", "Black Holes and Revelations", genre = "Rock", durationSec = 209, qualityLabel = "FLAC 16/44", lossless = true),
    SongUi("2", "Starlight", "Muse", "Black Holes and Revelations", genre = "Rock", durationSec = 240, favorite = true),
    SongUi("3", "Bohemian Rhapsody", "Queen", "A Night at the Opera", genre = "Rock", durationSec = 354, qualityLabel = "320 kbps"),
)

internal fun previewActions() = ListActions(play = { _, _ -> }, shuffle = {}, setFavorite = { _, _ -> }, addToPlaylist = {}, notice = {})

internal fun previewPrefs() = UiPrefs({ null }, { _, _ -> })

/** Player controls that do nothing, for previews. */
internal class FakePlayerControls(
    position: PositionUi = PositionUi(positionMs = 84_000, bufferedMs = 150_000),
    sleepAt: Long? = null,
    private val lyrics: String? = "[00:10.00]First line of the song\n[00:20.00]Second line of the song\n[00:30.00]Third line of the song",
) : PlayerControls {
    override val position: StateFlow<PositionUi> = MutableStateFlow(position)
    override val sleepAt: StateFlow<Long?> = MutableStateFlow(sleepAt)
    override val speed: StateFlow<Float> = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = MutableStateFlow(0.8f)
    override val outputInfo: StateFlow<io.max.paperplay.player.OutputInfo?> = MutableStateFlow(
        io.max.paperplay.player.OutputInfo(
            codec = "ALAC", sampleRateHz = 96000, channels = 2, bitrateKbps = 2400, bitDepth = 24, device = "Speakers", outputRateHz = 48000,
            effects = listOf("Equalizer"), note = "Windows mixes sound at the format set in Sound settings.",
        ),
    )
    override fun setSpeed(speed: Float) {}
    override fun removeFromQueue(index: Int) {}
    override fun moveInQueue(from: Int, to: Int) {}
    override fun togglePlay() {}
    override fun next() {}
    override fun previous() {}
    override fun seekTo(fraction: Float) {}
    override fun seekToMs(ms: Long) {}
    override fun setVolume(v: Float) {}
    override fun toggleShuffle() {}
    override fun cycleRepeat() {}
    override fun playQueueIndex(index: Int) {}
    override fun setSleepTimer(minutes: Int?) {}
    override suspend fun lyricsFor(songId: String): String? = lyrics
}

internal fun previewPlayback(playing: Boolean = true) = PlaybackUiState(
    current = previewSongs[0], playing = playing, durationMs = 209_000, volume = 0.8f,
    queue = previewSongs, queueIndex = 0,
)
