package io.max.paperplay.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.state.SongUi
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Synced lyrics for the current song: the current line is bold and kept in view, any line seeks when clicked.
 * Plain (untimed) lyrics scroll as text. Colours come from the surrounding theme, so wrap it in [CoverTinted] for the cover wash.
 */
@Composable
fun LyricsPane(song: SongUi, backend: PlayerControls, large: Boolean, modifier: Modifier = Modifier) {
    val id = song.id
    var text by remember(id) { mutableStateOf<String?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) { text = backend.lyricsFor(id); loaded = true }
    val lines = remember(text) { text?.let { io.max.paperplay.core.metadata.parseLrc(it) }.orEmpty() }
    val pos = backend.position.collectAsState()
    val current by remember(lines) { derivedStateOf { lines.indexOfLast { it.timeMs <= pos.value.positionMs } } }
    val list = rememberLazyListState()
    LaunchedEffect(current) { if (current >= 0) list.animateScrollToItem(maxOf(0, current - 2)) }
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    val style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge
    when {
        !loaded -> Text("Looking for lyrics", modifier.padding(24.dp), style = style, color = ink.copy(alpha = 0.6f))
        text == null -> Text("No lyrics found for this song.", modifier.padding(24.dp), style = style, color = ink.copy(alpha = 0.6f))
        lines.isEmpty() -> Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp)) { Text(text.orEmpty(), style = style, color = ink) }
        else -> LazyColumn(modifier, state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 48.dp)) {
            itemsIndexed(lines) { i, l ->
                Text(
                    l.text.ifBlank { " " },
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { backend.seekToMs(l.timeMs) }.padding(horizontal = 8.dp, vertical = 10.dp),
                    style = style.copy(fontWeight = if (i == current) FontWeight.ExtraBold else FontWeight.SemiBold),
                    color = if (i == current) ink else ink.copy(alpha = 0.4f),
                )
            }
        }
    }
}

/** Lyrics filling the whole content area, with the song named at the top and a way back. */
@Composable
fun FullLyricsView(song: SongUi, backend: PlayerControls, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
            IconButton(onClose) { AppIcon(Icons.Rounded.KeyboardArrowDown, "Close lyrics", tint = ink) }
            Column(Modifier.padding(start = 8.dp)) {
                Text(song.title, maxLines = 1, style = MaterialTheme.typography.titleMedium, color = ink)
                Text(song.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.7f))
            }
        }
        LyricsPane(song, backend, large = true, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 48.dp))
    }
}

@Preview(name = "Full lyrics", widthDp = 900, heightDp = 600)
@Composable
private fun FullLyricsViewPreview() = io.max.paperplay.ui.PreviewFrame(dark = true) {
    FullLyricsView(io.max.paperplay.ui.previewSongs[0], io.max.paperplay.ui.FakePlayerControls(), onClose = {})
}
