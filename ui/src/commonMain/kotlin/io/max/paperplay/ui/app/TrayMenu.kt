package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.components.LocalCoverLoader
import io.max.paperplay.ui.theme.PaperPlayTheme

/** Size of the tray menu window: the card plus room for its shadow. Shared so the desktop entry point can place it. */
val TrayMenuWidth = 256.dp
val TrayMenuHeight = 352.dp

/**
 * The system tray menu, drawn by the app instead of the native Windows one: the playing cover's colours, rounded card,
 * now playing header and the same actions as before. The desktop entry point shows it in a small undecorated window.
 */
@Composable
fun TrayMenuContent(
    onOpen: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onMini: () -> Unit, onQuit: () -> Unit,
    dismiss: () -> Unit,
) {
    val loader = remember { FileCoverLoader(budgetBytes = 8L * 1024 * 1024) }
    val state by DesktopMini.state.collectAsState()
    val song = state.current
    PaperPlayTheme(dark = true, dynamic = false) {
        CompositionLocalProvider(LocalCoverLoader provides loader) {
            CoverTinted(song?.coverUri) {
                val ink = MaterialTheme.colorScheme.onPrimaryContainer
                val card = MaterialTheme.colorScheme.primaryContainer
                val shape = RoundedCornerShape(18.dp)
                Box(Modifier.fillMaxSize().padding(8.dp)) {
                    Column(Modifier.fillMaxSize().shadow(10.dp, shape).clip(shape).background(card).padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (song != null) CoverImage(song.coverUri, song.id, size = 44.dp, radius = 10.dp)
                            Column(Modifier.weight(1f).padding(start = if (song != null) 10.dp else 0.dp)) {
                                Text(song?.title ?: "Nothing is playing", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = ink)
                                if (song != null) Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.7f))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        TrayItem(Icons.Rounded.OpenInFull, "Open Paper Play", ink) { dismiss(); onOpen() }
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(ink.copy(alpha = 0.14f)))
                        TrayItem(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", ink, enabled = song != null) { dismiss(); onPlayPause() }
                        TrayItem(Icons.Rounded.SkipNext, "Next", ink, enabled = song != null) { dismiss(); onNext() }
                        TrayItem(Icons.Rounded.SkipPrevious, "Previous", ink, enabled = song != null) { dismiss(); onPrevious() }
                        TrayItem(Icons.Rounded.PictureInPictureAlt, "Mini player", ink) { dismiss(); onMini() }
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(ink.copy(alpha = 0.14f)))
                        TrayItem(Icons.Rounded.PowerSettingsNew, "Quit", ink) { dismiss(); onQuit() }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrayItem(icon: ImageVector, label: String, ink: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(10.dp))
            .background(if (hovered && enabled) ink.copy(alpha = 0.14f) else Color.Transparent)
            .hoverable(hover).clickable(enabled = enabled, onClick = onClick)
            .height(38.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, null, Modifier.size(20.dp), tint = ink.copy(alpha = if (enabled) 1f else 0.4f))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = if (enabled) 1f else 0.4f))
    }
}
