package io.max.paperplay.ui.app

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import io.max.paperplay.ui.GlassSurface
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.state.PlaybackUiState

private class NavEntry(val icon: ImageVector, val selected: ImageVector, val label: String)

private val entries = listOf(
    NavEntry(Icons.Outlined.Home, Icons.Rounded.Home, "Home"),
    NavEntry(Icons.Rounded.Search, Icons.Rounded.ManageSearch, "Search"),
    NavEntry(Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic, "Library"),
    NavEntry(Icons.Outlined.Person, Icons.Rounded.Person, "Profile"),
)

/** Floating glass bar: the selected tab is a tonal pill showing its name. */
@Composable
fun NavBar(selected: Int, backdrop: Backdrop, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(backdrop, modifier.fillMaxWidth().height(64.dp), cornerRadius = 32.dp) {
        Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            entries.forEachIndexed { i, e ->
                val on = i == selected
                val ink = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                val scale by animateFloatAsState(if (on) 1.18f else 1f)
                Row(
                    Modifier.clip(RoundedCornerShape(28.dp))
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onSelect(i) }
                        .animateContentSize()
                        .padding(horizontal = if (on) 20.dp else 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (on) e.selected else e.icon, e.label, Modifier.size(27.dp).scale(scale), tint = ink)
                    if (on) {
                        Spacer(Modifier.width(8.dp))
                        Text(e.label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Solid card in the cover's tone (the theme's secondary container until cover colours exist). */
@Composable
fun MiniPlayer(
    state: PlaybackUiState, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = state.current ?: return
    val bg = MaterialTheme.colorScheme.secondaryContainer
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).clickable(onClick = onOpen)) {
        Column {
            Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(song.coverUri, song.id, size = 44.dp, radius = 8.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = ink)
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.75f))
                }
                IconButton(onPrevious) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = ink) }
                IconButton(onToggle) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(32.dp), tint = ink) }
                IconButton(onNext) { Icon(Icons.Rounded.SkipNext, "Next", tint = ink) }
            }
            Box(Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(ink.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(state.progress.coerceIn(0f, 1f)).height(2.dp).background(ink))
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
