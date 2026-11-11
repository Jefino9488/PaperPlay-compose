package io.max.paperplay.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
    coverUriOverride: String? = null,
    actions: SongActions? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val containerColor = when {
        selected -> scheme.primaryContainer
        current -> scheme.secondaryContainer.copy(alpha = 0.7f)
        else -> scheme.surfaceContainerLow
    }
    val effectiveCoverUri = coverUriOverride ?: song.coverUri
    val coverKey = song.album.ifBlank { song.title.ifBlank { song.id } }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = io.max.paperplay.ui.app.appShape(16.dp),
        color = containerColor,
        tonalElevation = if (current) 3.dp else 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    AppIcon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = scheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            } else {
                CoverImage(effectiveCoverUri, coverKey, size = 48.dp, radius = 10.dp, coverId = song.id)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (current) scheme.primary else scheme.onSurface,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (song.pinned) Badge("Pinned")
                    else if (song.offline) Badge("Offline")
                    song.qualityLabel?.substringBefore(' ')?.takeIf { it.isNotBlank() }?.let { Badge(it) }
                    Text(
                        text = song.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            val kbps = if (song.durationSec > 0) song.sizeBytes * 8 / song.durationSec / 1000 else 0L
            val detail = listOfNotNull(
                if (kbps > 0) "$kbps kbps" else null,
                if (song.sizeBytes > 0) "${song.sizeBytes / 1_000_000} MB" else null,
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            if (song.favorite) {
                AppIcon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = "Favorite",
                    tint = scheme.primary,
                    modifier = Modifier.size(16.dp).padding(end = 4.dp),
                )
            }
            if (actions != null) SongMenu(song, actions) else Box(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        shape = io.max.paperplay.ui.app.appShape(6.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun SongMenu(song: SongUi, actions: SongActions, icon: ImageVector = Icons.Rounded.MoreVert) {
    var open by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    if (info) androidx.compose.material3.AlertDialog(
        onDismissRequest = { info = false },
        title = { Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            val kbps = if (song.durationSec > 0) song.sizeBytes * 8 / song.durationSec / 1000 else 0L
            val rows = listOf(
                "Artist" to song.artist, "Album" to song.album, "Genre" to song.genre.orEmpty(),
                "Year" to (song.year?.toString() ?: ""),
                "Length" to "${song.durationSec / 60}:${(song.durationSec % 60).toString().padStart(2, '0')}",
                "Format" to song.qualityLabel.orEmpty(),
                "Bitrate" to if (kbps > 0) "$kbps kbps" else "",
                "Size" to if (song.sizeBytes > 0) "${song.sizeBytes / 100_000 / 10.0} MB" else "",
                "Plays" to song.plays.toString(),
            ).filter { it.second.isNotBlank() }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rows.forEach { (k, v) ->
                    Row { Text(k, Modifier.weight(0.35f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, Modifier.weight(0.65f)) }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton({ info = false }) { Text("Close") } },
    )
    Box {
        IconButton({ open = true }) { AppIcon(icon, "Song options") }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = io.max.paperplay.ui.app.overlayRim(RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            containerColor = io.max.paperplay.ui.app.overlayColor(),
        ) {
            @Composable
            fun item(text: String, run: () -> Unit) = DropdownMenuItem(
                text = { Text(text) },
                onClick = { open = false; run() },
            )
            item("Play next") { actions.playNext(song) }
            item(if (song.favorite) "Remove from favorites" else "Add to favorites") { actions.setFavorite(song, !song.favorite) }
            item(if (song.pinned) "Unpin" else "Pin for offline") { actions.setPinned(song, !song.pinned) }
            item("Add to playlist") { actions.addToPlaylist(song) }
            actions.telegramLink(song)?.let { link ->
                item("Open in Telegram") { runCatching { uri.openUri(link) }.onFailure { actions.notice("Could not open Telegram.") } }
            }
            item("Song info") { info = true }
            item("Find metadata") { actions.findMetadata(song) }
            item("Edit tags") { actions.notice("Editing tags is not built yet.") }
            item("Change cover") { actions.notice("Changing a cover is not built yet.") }
            actions.removeLabel?.let { label -> item(label) { actions.remove(song) } }
        }
    }
}

private val previewSong = SongUi(
    id = "1",
    title = "Bohemian Rhapsody",
    artist = "Queen",
    album = "A Night at the Opera",
    durationSec = 354,
    sizeBytes = 12_500_000,
    qualityLabel = "320 kbps",
    favorite = true,
    pinned = true,
    offline = true,
)

@Preview
@Composable
private fun SongRowNormalPreview() {
    SongRow(song = previewSong, onClick = {}, onLongClick = {})
}

@Preview
@Composable
private fun SongRowCurrentPreview() {
    SongRow(song = previewSong, onClick = {}, onLongClick = {}, current = true)
}

@Preview
@Composable
private fun SongRowSelectedPreview() {
    SongRow(song = previewSong, onClick = {}, onLongClick = {}, selected = true)
}

@Preview
@Composable
private fun SongMenuPreview() {
    val dummyActions = object : SongActions {
        override fun play(songs: List<SongUi>, index: Int) {}
        override fun playNext(song: SongUi) {}
        override fun setFavorite(song: SongUi, favorite: Boolean) {}
        override fun setPinned(song: SongUi, pinned: Boolean) {}
        override fun addToPlaylist(song: SongUi) {}
    }
    SongMenu(song = previewSong, actions = dummyActions)
}

