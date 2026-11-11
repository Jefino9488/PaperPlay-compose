package io.max.paperplay.ui.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.PlatformBackHandler
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.components.SongMenu
import io.max.paperplay.ui.components.WavySeek
import io.max.paperplay.ui.logic.formatDuration
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.state.RepeatUi
import io.max.paperplay.ui.state.SongUi
import io.max.paperplay.ui.theme.schemeFromSeed

/**
 * The full-screen player: a dark theme in the cover's colours over a gradient to black. On a
 * phone the queue replaces the song; from 900dp the two sit side by side.
 */
@Composable
fun FullPlayer(state: PlaybackUiState, error: String?, backend: CoreBackend, actions: ListActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val song = state.current ?: return
    val base = MaterialTheme.colorScheme
    val scheme = coverScheme(song.coverUri) ?: remember(base.primary) { schemeFromSeed(base.primary.toArgb(), dark = true) }
    var showQueue by remember { mutableStateOf(false) }
    PlatformBackHandler(showQueue) { showQueue = false }
    val top by animateColorAsState(lerp(scheme.primaryContainer, Color.Black, 0.25f), tween(500))
    val mid by animateColorAsState(lerp(scheme.surfaceContainerLow, Color.Black, 0.75f), tween(500))
    val insets = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    MaterialTheme(colorScheme = scheme) {
        BoxWithConstraints(
            modifier.fillMaxSize()
                .background(Brush.verticalGradient(0f to top, 0.6f to mid, 1f to Color.Black))
                .padding(top = insets, bottom = bottom),
        ) {
            val wide = maxWidth >= 900.dp
            when {
                wide -> Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        IconButton(onClose, Modifier.padding(8.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Close player", Modifier.size(32.dp)) }
                        PlayerBody(song, state, error, backend, actions, onClose, grabber = false, Modifier.weight(1f))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().padding(end = 16.dp)) {
                        Text("Queue", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                        QueueList(state, backend, Modifier.weight(1f))
                    }
                }
                showQueue -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ showQueue = false }) { Icon(Icons.Rounded.KeyboardArrowDown, "Back to the song", Modifier.size(32.dp)) }
                        Text("Queue", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("${state.queue.size} songs", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                    }
                    QueueList(state, backend, Modifier.weight(1f))
                }
                else -> PlayerBody(song, state, error, backend, actions, onClose, grabber = true, Modifier.fillMaxSize(), onQueue = { showQueue = true })
            }
        }
    }
}

@Composable
private fun PlayerBody(
    song: SongUi, state: PlaybackUiState, error: String?, backend: CoreBackend, actions: ListActions, onClose: () -> Unit,
    grabber: Boolean, modifier: Modifier = Modifier, onQueue: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        // Room the controls need, so short screens shrink the cover, not them.
        val art = minOf(maxWidth - 48.dp, maxHeight - 25.dp - 300.dp).coerceAtLeast(120.dp)
        Column(Modifier.widthIn(max = 560.dp).fillMaxHeight().padding(horizontal = 12.dp)) {
            // Drag down on the cover to close, sideways to change song.
            Column(
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    var dx = 0f; var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = {
                            when {
                                dy > 80f && dy > kotlin.math.abs(dx) -> onClose()
                                dx < -80f -> backend.next()
                                dx > 80f -> backend.previous()
                            }
                        },
                    ) { change, drag -> change.consume(); dx += drag.x; dy += drag.y }
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (grabber) Box(Modifier.padding(vertical = 10.dp).size(40.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(scheme.onSurface.copy(alpha = 0.35f)).clickable(onClick = onClose))
                else Spacer(Modifier.height(8.dp))
                Artwork(song, state.playing, art)
            }
            // Everything below shares the leftover height evenly.
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = scheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    IconButton({ actions.setFavorite(song, !song.favorite) }) {
                        Icon(
                            if (song.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (song.favorite) "Remove from favorites" else "Add to favorites",
                            tint = if (song.favorite) scheme.primary else scheme.onSurface,
                        )
                    }
                    SongMenu(song, actions.rowActions(), Icons.Rounded.MoreHoriz)
                }
                Column(Modifier.padding(horizontal = 12.dp)) {
                    if (error != null) Text(error, color = scheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                    Progress(song, state, backend)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(backend::previous, Modifier.size(64.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(44.dp)) }
                    PlayPauseButton(state.playing, backend::togglePlay)
                    IconButton(backend::next, Modifier.size(64.dp)) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(44.dp)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ actions.notice("Lyrics are not built yet.") }) { Icon(Icons.Outlined.Lyrics, "Lyrics") }
                    IconButton(backend::toggleShuffle) {
                        Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) scheme.primary else scheme.onSurface)
                    }
                    IconButton(backend::cycleRepeat) {
                        Icon(
                            if (state.repeat == RepeatUi.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                            tint = if (state.repeat != RepeatUi.Off) scheme.primary else scheme.onSurface,
                        )
                    }
                    IconButton({ actions.notice("The sleep timer is not built yet.") }) { Icon(Icons.Outlined.Bedtime, "Sleep timer and speed") }
                    if (onQueue != null) IconButton(onQueue) { Icon(Icons.Rounded.QueueMusic, "Queue") }
                }
            }
        }
    }
}

/** The cover: full size while playing and slightly smaller when paused, in a slot that never moves. */
@Composable
private fun Artwork(song: SongUi, playing: Boolean, size: androidx.compose.ui.unit.Dp) {
    val scale by animateFloatAsState(if (playing) 1f else 0.92f, tween(500))
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        CoverImage(song.coverUri, song.id, Modifier.scale(scale).shadow(24.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black, spotColor = Color.Black), size = size, radius = 28.dp)
    }
}

@Composable
private fun PlayPauseButton(playing: Boolean, onToggle: () -> Unit) {
    FilledIconButton(
        onToggle, Modifier.size(72.dp), shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) {
        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(36.dp))
    }
}

@Composable
private fun Progress(song: SongUi, state: PlaybackUiState, backend: CoreBackend) {
    val scheme = MaterialTheme.colorScheme
    val muted = MaterialTheme.typography.labelMedium.copy(color = scheme.onSurface.copy(alpha = 0.65f))
    var info by remember { mutableStateOf(false) }
    val total = if (state.durationMs > 0) state.durationMs else song.durationSec * 1000L
    WavySeek(
        value = state.progress, buffered = state.bufferedProgress, playing = state.playing,
        onSeek = backend::seekTo, modifier = Modifier.fillMaxWidth(), label = formatDuration(state.positionMs / 1000),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatDuration(state.positionMs / 1000), Modifier.weight(1f), style = muted)
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).background(scheme.onSurface.copy(alpha = 0.12f)).clickable { info = true }
                .height(24.dp).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(song.qualityLabel ?: if (song.lossless) "Lossless" else "Lossy", style = muted)
        }
        Text("-" + formatDuration(((total - state.positionMs) / 1000).coerceAtLeast(0)), Modifier.weight(1f), style = muted, textAlign = TextAlign.End)
    }
    if (info) SongInfoDialog(song) { info = false }
}

@Composable
private fun SongInfoDialog(song: SongUi, onDismiss: () -> Unit) {
    val rows = listOfNotNull(
        "Artist" to song.artist, "Album" to song.album.ifBlank { null }, "Genre" to song.genre,
        "Year" to song.year?.toString(), "Length" to formatDuration(song.durationSec.toLong()),
        "Size" to "${song.sizeBytes / 100_000 / 10.0} MB", "Quality" to song.qualityLabel, "Plays" to song.plays.toString(),
    ).filter { it.second != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = overlayRim(RoundedCornerShape(28.dp)), shape = RoundedCornerShape(28.dp), containerColor = overlayColor(),
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
        title = { Text(song.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((k, v) in rows) Row {
                    Text(k, Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v!!, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
    )
}

/** The queue; tap to play. Reorder and swipe-to-remove come with the queue work in the player engine. */
@Composable
private fun QueueList(state: PlaybackUiState, backend: CoreBackend, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        itemsIndexed(state.queue, key = { i, s -> "${s.id}#$i" }) { i, s ->
            val on = i == state.queueIndex
            Row(
                Modifier.fillMaxWidth().background(if (on) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable { backend.playQueueIndex(i) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(s.coverUri, s.id, size = 48.dp, radius = 8.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(formatDuration(s.durationSec.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The cover's dark scheme applied over the normal theme, for surfaces tinted by the current song. */
@Composable
fun CoverTinted(uri: String?, content: @Composable (ColorScheme?) -> Unit) {
    val cover = coverScheme(uri)
    if (cover == null) content(null) else MaterialTheme(colorScheme = cover) { content(cover) }
}

/** The desktop side panel: the current song large, with what comes next. Shown on wide windows while the pref is on. */
@Composable
fun NowPlayingPanel(state: PlaybackUiState, backend: CoreBackend, actions: ListActions, onOpen: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val song = state.current ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val next = if (state.queueIndex < 0) emptyList() else state.queue.drop(state.queueIndex + 1).take(10)
    LazyColumn(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (song.album.isEmpty() || song.album.startsWith(song.title)) "Now playing" else song.album,
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onHide) { Icon(Icons.Rounded.Close, "Hide this panel", Modifier.size(20.dp)) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                CoverImage(song.coverUri, song.id, Modifier.clickable(onClick = onOpen), size = maxWidth, radius = 12.dp)
            }
            Row(Modifier.padding(top = 14.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleLarge)
                    Text(song.artist, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                IconButton({ actions.setFavorite(song, !song.favorite) }) {
                    Icon(if (song.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (song.favorite) "Remove from favorites" else "Add to favorites")
                }
            }
            val facts = listOfNotNull(song.qualityLabel, song.year?.toString(), song.genre).joinToString(" · ")
            if (facts.isNotEmpty()) Text(facts, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = muted)
            if (next.isNotEmpty()) Text("Next up", Modifier.padding(top = 20.dp, bottom = 6.dp), style = MaterialTheme.typography.titleSmall)
        }
        itemsIndexed(next) { i, s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { backend.playQueueIndex(state.queueIndex + 1 + i) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(s.coverUri, s.id, size = 40.dp, radius = 6.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Text(formatDuration(s.durationSec.toLong()), style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}
