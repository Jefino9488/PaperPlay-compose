package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.components.LocalCoverLoader
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.theme.PaperPlayTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Link between the main window and the desktop mini player window. The shell publishes playback state and
 * controls here, the desktop entry point shows [DesktopMiniContent] in its own always-on-top window while [visible].
 */
object DesktopMini {
    val visible = MutableStateFlow(false)
    internal val state = MutableStateFlow(PlaybackUiState())
    internal val controls = MutableStateFlow<PlayerControls?>(null)
    fun toggle() { visible.value = !visible.value }
}

/** Keeps the mini window in step with the main window's playback. Place once in the desktop shell. */
@Composable
fun PublishMiniState(state: PlaybackUiState, backend: PlayerControls) {
    LaunchedEffect(state) { DesktopMini.state.value = state }
    LaunchedEffect(backend) { DesktopMini.controls.value = backend }
}

/** The button for the desktop player bar that shows or hides the mini player window. */
@Composable
fun MiniPlayerToggle(modifier: Modifier = Modifier) {
    val on by DesktopMini.visible.collectAsState()
    IconButton(DesktopMini::toggle, modifier) {
        AppIcon(Icons.Rounded.PictureInPictureAlt, if (on) "Close mini player" else "Open mini player", Modifier.size(22.dp), tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Content of the mini window: cover, title and the transport controls, tinted by the cover. */
@Composable
fun DesktopMiniContent(onClose: (() -> Unit)? = null) {
    val loader = SharedCovers.loader
    val state by DesktopMini.state.collectAsState()
    val controls by DesktopMini.controls.collectAsState()
    PaperPlayTheme(dark = true, dynamic = false) {
        CompositionLocalProvider(LocalCoverLoader provides loader) {
            MiniBody(state, controls, onClose)
        }
    }
}

@Composable
internal fun MiniBody(state: PlaybackUiState, controls: PlayerControls?, onClose: (() -> Unit)? = null) {
    val song = state.current
    CoverTinted(song?.coverUri) {
        val ink = MaterialTheme.colorScheme.onPrimaryContainer
        // Frameless: rounded card, cover running the full height on the left, a small close button on the right.
        val shape = androidx.compose.ui.graphics.RectangleShape // Windows rounds the window
        // graphicsLayer clipping keeps the corners anti-aliased against the transparent window; a plain clip left dark fringes.
        Box(Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (song != null) CoverImage(song.coverUri, song.id, size = 110.dp, radius = 0.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 12.dp)) {
                    Text(song?.title ?: "Nothing is playing", Modifier.padding(end = 22.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, color = ink)
                    if (song != null) Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.7f))
                    val posNow = controls?.position?.collectAsState()?.value
                    Row(Modifier.padding(top = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton({ controls?.previous() }, Modifier.size(36.dp), enabled = song != null) { AppIcon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(24.dp), tint = ink) }
                        FilledIconButton(
                            { controls?.togglePlay() }, Modifier.size(42.dp), enabled = song != null, shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = ink, contentColor = MaterialTheme.colorScheme.primaryContainer),
                        ) { AppIcon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(24.dp)) }
                        IconButton({ controls?.next() }, Modifier.size(36.dp), enabled = song != null) { AppIcon(Icons.Rounded.SkipNext, "Next", Modifier.size(24.dp), tint = ink) }
                        // Time on the right, so the card has no dead space beside the controls.
                        if (song != null) Text(
                            io.max.paperplay.ui.logic.formatDuration((posNow?.positionMs ?: 0) / 1000) + " / " + io.max.paperplay.ui.logic.formatDuration(((if (state.durationMs > 0) state.durationMs else song.durationSec * 1000L)) / 1000),
                            Modifier.weight(1f).padding(end = 4.dp), style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.7f), textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1,
                        )
                    }
                }
            }
            if (onClose != null) IconButton(onClose, Modifier.align(Alignment.TopEnd).padding(2.dp).size(26.dp)) {
                AppIcon(Icons.Rounded.Close, "Close mini player", Modifier.size(16.dp), tint = ink.copy(alpha = 0.7f))
            }
            val pos = controls?.position?.collectAsState()
            val fill = ink
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                    .drawBehind { drawRect(fill, size = Size(size.width * (pos?.value?.progress(state.durationMs) ?: 0f), size.height)) },
            )
        }
    }
}

@Preview(name = "Mini player", widthDp = 360, heightDp = 100)
@Composable
private fun MiniBodyPreview() = io.max.paperplay.ui.PreviewFrame(dark = true) {
    MiniBody(io.max.paperplay.ui.previewPlayback(), io.max.paperplay.ui.FakePlayerControls())
}

/** Window full screen on desktop: the player toggles [on], the desktop entry point applies it. [supported] stays false elsewhere. */
object WindowFullscreen {
    val on = MutableStateFlow(false)
    val supported = MutableStateFlow(false)
    fun toggle() { on.value = !on.value }
}
