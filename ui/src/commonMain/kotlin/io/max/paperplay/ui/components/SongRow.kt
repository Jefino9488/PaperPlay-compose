package io.max.paperplay.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.max.paperplay.ui.logic.formatDuration
import io.max.paperplay.ui.state.SongActions
import io.max.paperplay.ui.state.SongUi

/** One song in a list. Tap plays (or toggles while selecting), long-press starts selecting. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: SongUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    selected: Boolean = false,
    actions: SongActions? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth()
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Text("✓", color = scheme.primary, fontSize = 28.sp)
            }
        } else {
            CoverImage(song.coverUri, song.id, size = 56.dp, radius = 12.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = if (current) scheme.primary else scheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.pinned) Badge("Pinned")
                else if (song.offline) Badge("Offline")
                if (song.qualityLabel != null) {
                    Text(
                        song.qualityLabel.substringBefore(' '), style = MaterialTheme.typography.labelSmall,
                        color = if (song.lossless) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Text(
                    song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (song.sizeBytes > 0) {
            val kbps = if (song.durationSec > 0) song.sizeBytes * 8 / song.durationSec / 1000 else 0
            Text(
                (if (kbps > 0) "$kbps kbps · " else "") + "${song.sizeBytes / 1_000_000} MB",
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1,
            )
        }
        if (song.favorite) Text("♥", color = scheme.primary, fontSize = 14.sp, modifier = Modifier.padding(end = 4.dp))
        if (actions != null) SongMenu(song, actions) else Box(Modifier.size(8.dp))
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(end = 6.dp),
    )
}

@Composable
private fun SongMenu(song: SongUi, actions: SongActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(Icons.Rounded.MoreVert, "Song options") }
        DropdownMenu(open, { open = false }) {
            @Composable
            fun item(text: String, run: () -> Unit) = DropdownMenuItem(
                text = { Text(text) }, onClick = { open = false; run() },
            )
            item("Play next") { actions.playNext(song) }
            item(if (song.favorite) "Remove from favorites" else "Add to favorites") { actions.setFavorite(song, !song.favorite) }
            item(if (song.pinned) "Unpin" else "Pin for offline") { actions.setPinned(song, !song.pinned) }
            item("Add to playlist") { actions.addToPlaylist(song) }
        }
    }
}
