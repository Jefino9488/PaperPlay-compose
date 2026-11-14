package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Desktop keyboard control of playback. Keys a text field handles never reach it. */
class PlayerKeys(private val backend: PlayerControls) {
    var help by mutableStateOf(false)
    var volume = 1f
    private var lastVolume = 1f

    fun handle(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        when (e.key) {
            Key.Spacebar -> backend.togglePlay()
            Key.DirectionRight -> if (e.isCtrlPressed) backend.next() else backend.seekToMs(backend.position.value.positionMs + 5_000)
            Key.DirectionLeft -> if (e.isCtrlPressed) backend.previous() else backend.seekToMs((backend.position.value.positionMs - 5_000).coerceAtLeast(0))
            Key.DirectionUp -> backend.setVolume((volume + 0.05f).coerceAtMost(1f))
            Key.DirectionDown -> backend.setVolume((volume - 0.05f).coerceAtLeast(0f))
            Key.M -> if (volume > 0f) { lastVolume = volume; backend.setVolume(0f) } else backend.setVolume(lastVolume)
            Key.S -> backend.toggleShuffle()
            Key.R -> backend.cycleRepeat()
            Key.F1 -> help = !help
            Key.Slash -> if (e.isShiftPressed) help = !help else return false
            Key.Escape -> if (help) help = false else return false
            else -> return false
        }
        return true
    }
}

@Composable
fun rememberPlayerKeys(backend: PlayerControls, volume: Float): PlayerKeys {
    val keys = remember(backend) { PlayerKeys(backend) }
    keys.volume = volume
    return keys
}

/** Takes keyboard focus for the window and routes playback keys to [keys]. */
@Composable
fun Modifier.playerKeys(keys: PlayerKeys): Modifier {
    val focus = remember { FocusRequester() }
    var inside by remember { mutableStateOf(false) }
    // Clicking an empty spot clears focus and the keys would go nowhere, so take it back. A text field or button
    // inside the window counts as focus here, and its own key handling runs first (typing a space types a space).
    LaunchedEffect(inside) {
        if (!inside) { kotlinx.coroutines.delay(60); runCatching { focus.requestFocus() } }
    }
    return this.onFocusChanged { inside = it.hasFocus }.focusRequester(focus).focusable().onKeyEvent(keys::handle)
}

private val shortcutList = listOf(
    "Space" to "Play or pause",
    "Left / Right" to "Back or forward 5 seconds",
    "Ctrl + Left / Right" to "Previous or next song",
    "Up / Down" to "Volume",
    "M" to "Mute",
    "S" to "Shuffle",
    "R" to "Repeat",
    "? or F1" to "Show or hide this list",
)

/** The shortcut list, shown over the window while [PlayerKeys.help] is on. */
@Composable
fun ShortcutsOverlay(keys: PlayerKeys, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        keys.help,
        enter = androidx.compose.animation.fadeIn(io.max.paperplay.ui.theme.motionTween(io.max.paperplay.ui.theme.Motion.FAST)),
        exit = androidx.compose.animation.fadeOut(io.max.paperplay.ui.theme.motionTween(io.max.paperplay.ui.theme.Motion.FAST)),
    ) {
        Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { keys.help = false }, contentAlignment = Alignment.Center) {
            ShortcutsCard()
        }
    }
}

@Composable
private fun ShortcutsCard() {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp) {
        Column(Modifier.width(380.dp).padding(24.dp)) {
            Text("Keyboard shortcuts", style = MaterialTheme.typography.titleLarge)
            shortcutList.forEach { (k, what) ->
                Row(Modifier.padding(top = 12.dp)) {
                    Text(k, Modifier.width(160.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(what, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Preview(name = "Shortcuts", widthDp = 440, heightDp = 400)
@Composable
private fun ShortcutsCardPreview() = io.max.paperplay.ui.PreviewFrame { ShortcutsCard() }
