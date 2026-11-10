package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import io.max.paperplay.ui.GlassSurface
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.components.GlyphButton
import io.max.paperplay.ui.components.IconTap
import io.max.paperplay.ui.components.TransportGlyph
import io.max.paperplay.ui.components.TransportIcon
import io.max.paperplay.ui.components.WavySeek
import io.max.paperplay.ui.logic.formatDuration
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.state.RepeatUi

@Composable
fun MiniPlayer(state: PlaybackUiState, backdrop: Backdrop, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val song = state.current ?: return
    GlassSurface(backdrop, modifier.fillMaxWidth().height(68.dp), cornerRadius = 34.dp) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().clickable(onClick = onOpen).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(song.coverUri, song.id, size = 48.dp, radius = 24.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconTap(if (state.playing) "Pause" else "Play", onToggle) {
                    TransportGlyph(if (state.playing) TransportIcon.Pause else TransportIcon.Play)
                }
                IconTap("Next", onNext) { TransportGlyph(TransportIcon.Next) }
            }
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 28.dp).fillMaxWidth().height(2.dp),
            )
        }
    }
}

@Composable
fun FullPlayer(state: PlaybackUiState, error: String?, backend: CoreBackend, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var showQueue by remember { mutableStateOf(false) }
    val song = state.current
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("Close") }
            Text(
                if (showQueue) "Queue" else "Now playing", Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton({ showQueue = !showQueue }) { Text(if (showQueue) "Cover" else "Queue") }
        }
        if (showQueue) {
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(state.queue) { i, s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { backend.playQueueIndex(i) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverImage(s.coverUri, s.id, size = 44.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                s.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (i == state.queueIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(formatDuration(s.durationSec.toLong()), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight).coerceAtMost(420.dp)
                if (song != null) CoverImage(song.coverUri, song.id, size = side, radius = 24.dp)
            }
        }
        if (song != null) {
            Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
            Text(
                listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (song.qualityLabel != null) Text(song.qualityLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        WavySeek(
            value = state.progress, buffered = state.bufferedProgress, playing = state.playing,
            onSeek = backend::seekTo, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(state.positionMs / 1000), style = MaterialTheme.typography.labelSmall)
            Text(formatDuration(state.durationMs / 1000), style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            GlyphButton("⇄", "Shuffle", backend::toggleShuffle, tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 24)
            IconTap("Previous", backend::previous, size = 56.dp) { TransportGlyph(TransportIcon.Previous, 30.dp) }
            IconTap(if (state.playing) "Pause" else "Play", backend::togglePlay, size = 72.dp) {
                TransportGlyph(if (state.playing) TransportIcon.Pause else TransportIcon.Play, 42.dp)
            }
            IconTap("Next", backend::next, size = 56.dp) { TransportGlyph(TransportIcon.Next, 30.dp) }
            GlyphButton(
                if (state.repeat == RepeatUi.One) "↻1" else "↻", "Repeat", backend::cycleRepeat,
                tint = if (state.repeat != RepeatUi.Off) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 24,
            )
        }
        Box(Modifier.size(12.dp))
    }
}
