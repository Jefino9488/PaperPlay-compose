package io.max.paperplay.ui.app

import io.max.paperplay.ui.components.AppIcon

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NightsStay
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import io.max.paperplay.ui.nowMoment
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
import org.jetbrains.compose.ui.tooling.preview.Preview
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
fun FullPlayer(state: PlaybackUiState, error: String?, backend: PlayerControls, actions: ListActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
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
                        IconButton(onClose, Modifier.padding(8.dp)) { AppIcon(Icons.Rounded.KeyboardArrowDown, "Close player", Modifier.size(32.dp)) }
                        PlayerBody(song, state, error, backend, actions, onClose, grabber = false, Modifier.weight(1f))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().padding(end = 16.dp)) {
                        Text("Queue", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                        QueueList(state, backend, Modifier.weight(1f))
                    }
                }
                showQueue -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ showQueue = false }) { AppIcon(Icons.Rounded.KeyboardArrowDown, "Back to the song", Modifier.size(32.dp)) }
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
    song: SongUi, state: PlaybackUiState, error: String?, backend: PlayerControls, actions: ListActions, onClose: () -> Unit,
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
                                dy < -80f && dy < -kotlin.math.abs(dx) -> onQueue?.invoke()
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
                    val standbyPrefs = if (io.max.paperplay.ui.isPhone) LocalUiPrefs.current?.standby else null
                    if (standbyPrefs != null) {
                        var standby by remember { mutableStateOf(false) }
                        IconButton({ standby = true }) { AppIcon(Icons.Rounded.NightsStay, "Standby mode") }
                        if (standby) StandbyScreen(state, backend, standbyPrefs) { standby = false }
                    }
                    IconButton({ actions.setFavorite(song, !song.favorite) }) {
                        AppIcon(
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
                    IconButton(backend::previous, Modifier.size(64.dp)) { AppIcon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(44.dp)) }
                    PlayPauseButton(state.playing, backend::togglePlay)
                    IconButton(backend::next, Modifier.size(64.dp)) { AppIcon(Icons.Rounded.SkipNext, "Next", Modifier.size(44.dp)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    var showLyrics by remember { mutableStateOf(false) }
                    IconButton({ showLyrics = true }, enabled = state.current != null) { AppIcon(Icons.Outlined.Lyrics, "Lyrics") }
                    if (showLyrics) state.current?.let { LyricsDialog(it.id, it.title, backend) { showLyrics = false } }
                    IconButton(backend::toggleShuffle) {
                        AppIcon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) scheme.primary else scheme.onSurface)
                    }
                    IconButton(backend::cycleRepeat) {
                        AppIcon(
                            if (state.repeat == RepeatUi.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                            tint = if (state.repeat != RepeatUi.Off) scheme.primary else scheme.onSurface,
                        )
                    }
                    var showSleep by remember { mutableStateOf(false) }
                    val sleepAt by backend.sleepAt.collectAsState()
                    IconButton({ showSleep = true }) { AppIcon(Icons.Outlined.Bedtime, "Sleep timer", tint = if (sleepAt != null) scheme.primary else scheme.onSurface) }
                    if (showSleep) SleepTimerDialog(backend) { showSleep = false }
                    var showSpeed by remember { mutableStateOf(false) }
                    val speed by backend.speed.collectAsState()
                    TextButton({ showSpeed = true }) {
                        Text(speedLabel(speed), color = if (speed != 1f) scheme.primary else scheme.onSurface, style = MaterialTheme.typography.labelLarge)
                    }
                    if (showSpeed) SpeedDialog(backend) { showSpeed = false }
                    if (onQueue != null) IconButton(onQueue) { AppIcon(Icons.Rounded.QueueMusic, "Queue") }
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
        AppIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(36.dp))
    }
}

@Composable
private fun Progress(song: SongUi, state: PlaybackUiState, backend: PlayerControls) {
    val scheme = MaterialTheme.colorScheme
    val muted = MaterialTheme.typography.labelMedium.copy(color = scheme.onSurface.copy(alpha = 0.65f))
    var info by remember { mutableStateOf(false) }
    val total = if (state.durationMs > 0) state.durationMs else song.durationSec * 1000L
    val pos by backend.position.collectAsState()
    WavySeek(
        progress = { pos.progress(state.durationMs) }, buffered = { pos.bufferedProgress(state.durationMs) }, playing = state.playing,
        onSeek = backend::seekTo,
        modifier = Modifier.fillMaxWidth(),
        label = formatDuration(pos.positionMs / 1000),
        longPressLabel = { fraction -> formatDuration((total * fraction / 1000L).toLong()) },
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatDuration(pos.positionMs / 1000), Modifier.weight(1f), style = muted)
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).background(scheme.onSurface.copy(alpha = 0.12f)).clickable { info = true }
                .height(24.dp).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(song.qualityLabel ?: if (song.lossless) "Lossless" else "Lossy", style = muted)
        }
        Text("-" + formatDuration(((total - pos.positionMs) / 1000).coerceAtLeast(0)), Modifier.weight(1f), style = muted, textAlign = TextAlign.End)
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

/** The queue; tap to play, the row menu moves or removes a song. */
@Composable
private fun QueueList(state: PlaybackUiState, backend: PlayerControls, modifier: Modifier = Modifier) {
    val keys = remember(state.queue) {
        val seen = HashMap<String, Int>()
        state.queue.map { s -> val n = seen.merge(s.id, 1, Int::plus)!!; "${s.id}#$n" }
    }
    LazyColumn(modifier) {
        itemsIndexed(state.queue, key = { i, _ -> keys[i] }) { i, s ->
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
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton({ menu = true }) { AppIcon(Icons.Rounded.MoreVert, "Queue options") }
                    androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                        if (i > 0) androidx.compose.material3.DropdownMenuItem({ Text("Move up") }, { menu = false; backend.moveInQueue(i, i - 1) })
                        if (i < state.queue.lastIndex) androidx.compose.material3.DropdownMenuItem({ Text("Move down") }, { menu = false; backend.moveInQueue(i, i + 1) })
                        if (i > state.queueIndex + 1) androidx.compose.material3.DropdownMenuItem({ Text("Play next") }, { menu = false; backend.moveInQueue(i, state.queueIndex + 1) })
                        if (state.queue.size > 1) androidx.compose.material3.DropdownMenuItem({ Text("Remove from queue") }, { menu = false; backend.removeFromQueue(i) })
                    }
                }
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
fun NowPlayingPanel(state: PlaybackUiState, backend: PlayerControls, actions: ListActions, onOpen: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val song = state.current ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val next = remember(state.queue, state.queueIndex) { if (state.queueIndex < 0) emptyList() else state.queue.drop(state.queueIndex + 1).take(10) }
    LazyColumn(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (song.album.isEmpty() || song.album.startsWith(song.title)) "Now playing" else song.album,
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onHide) { AppIcon(Icons.Rounded.Close, "Hide this panel", Modifier.size(20.dp)) }
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
                    AppIcon(if (song.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (song.favorite) "Remove from favorites" else "Add to favorites")
                }
                SongMenu(song, actions.rowActions(), Icons.Rounded.MoreHoriz)
            }
            // Format, bitrate, year and genre as chips; "FLAC 24/96" becomes FLAC and 24/96.
            val facts = (song.qualityLabel?.split(' ').orEmpty() + listOfNotNull(song.year?.toString(), song.genre)).filter { it.isNotBlank() }
            if (facts.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { facts.forEach { io.max.paperplay.ui.components.FormatChip(it) } }
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

private val previewPlayerSong = SongUi(
    id = "1", title = "Knights of Cydonia", artist = "Muse", album = "Black Holes and Revelations",
    durationSec = 366, sizeBytes = 14_000_000, qualityLabel = "FLAC 24/96", favorite = true,
)

private val previewPlayerState = PlaybackUiState(
    current = previewPlayerSong, playing = true, durationMs = 366000,
    queue = listOf(previewPlayerSong), queueIndex = 0,
)

private val previewPlayerActions = ListActions(play = { _, _ -> }, shuffle = {}, setFavorite = { _, _ -> }, addToPlaylist = {}, notice = {})

@Preview
@Composable
private fun CoverTintedPreview() {
    CoverTinted(uri = null) {
        Text("Tinted Content", modifier = Modifier.padding(16.dp))
    }
}

@Preview
@Composable
private fun PlayPauseButtonPreview() {
    PlayPauseButton(playing = true, onToggle = {})
}

@Preview
@Composable
private fun SongInfoDialogPreview() {
    SongInfoDialog(song = previewPlayerSong, onDismiss = {})
}


/** The song's lyrics. Timed lyrics follow the song; plain ones just scroll. */
@Composable
internal fun LyricsDialog(id: String, title: String, backend: PlayerControls, onClose: () -> Unit) {
    var text by remember(id) { mutableStateOf<String?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(id) { text = backend.lyricsFor(id); loaded = true }
    val lines = remember(text) { text?.let { io.max.paperplay.core.metadata.parseLrc(it) }.orEmpty() }
    val pos = backend.position.collectAsState()
    val current by remember(lines) { androidx.compose.runtime.derivedStateOf { lines.indexOfLast { it.timeMs <= pos.value.positionMs } } }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(current) { if (current >= 0) list.animateScrollToItem(maxOf(0, current - 2)) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { AppIcon(Icons.Rounded.KeyboardArrowDown, "Back") }
                Text(title, Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
            }
            val big = MaterialTheme.typography.headlineSmall
            when {
                !loaded -> Text("Looking for lyrics", Modifier.padding(24.dp), style = big)
                text == null -> Text("No lyrics found for this song.", Modifier.padding(24.dp), style = big)
                lines.isEmpty() -> Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp)) { Text(text.orEmpty(), style = big) }
                else -> androidx.compose.foundation.lazy.LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 24.dp)) {
                    itemsIndexed(lines) { i, l ->
                        Text(
                            l.text.ifBlank { " " }, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { backend.seekToMs(l.timeMs) }.padding(vertical = 10.dp),
                            style = big.copy(fontWeight = if (i == current) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium),
                            color = if (i == current) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
}

private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun speedLabel(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else s.toString().trimEnd('0')) + "x"

/** Playback speed. */
@Composable
internal fun SpeedDialog(backend: PlayerControls, onClose: () -> Unit) {
    val speed by backend.speed.collectAsState()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Playback speed") },
        text = {
            Column {
                speeds.forEach { s ->
                    TextButton({ backend.setSpeed(s); onClose() }) {
                        Text(speedLabel(s) + if (s == speed) " (selected)" else "")
                    }
                }
            }
        },
        confirmButton = { TextButton({ backend.setSpeed(1f); onClose() }) { Text("Normal") } },
        dismissButton = { TextButton(onClose) { Text("Close") } },
    )
}

/** Pauses playback after a chosen time. */
@Composable
internal fun SleepTimerDialog(backend: PlayerControls, onClose: () -> Unit) {
    val sleepAt by backend.sleepAt.collectAsState()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Sleep timer") },
        text = {
            Column {
                sleepAt?.let { end -> Text("Pauses in about ${maxOf(1L, (end - nowMoment().epochMs + 59_999) / 60_000)} min", Modifier.padding(bottom = 8.dp)) }
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    androidx.compose.material3.TextButton({ backend.setSleepTimer(m); onClose() }) { Text("$m minutes") }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton({ backend.setSleepTimer(null); onClose() }) { Text("Turn off") } },
        dismissButton = { androidx.compose.material3.TextButton(onClose) { Text("Close") } },
    )
}

@Preview(name = "Artwork", showBackground = true)
@Composable
private fun ArtworkPreview() = io.max.paperplay.ui.PreviewFrame {
    Artwork(io.max.paperplay.ui.previewSongs[0], playing = true, size = 240.dp)
}

@Preview(name = "Full player phone", widthDp = 390, heightDp = 844)
@Composable
private fun FullPlayerPhonePreview() = io.max.paperplay.ui.PreviewFrame(dark = true) {
    FullPlayer(io.max.paperplay.ui.previewPlayback(), null, io.max.paperplay.ui.FakePlayerControls(), io.max.paperplay.ui.previewActions(), onClose = {})
}

@Preview(name = "Full player error", widthDp = 390, heightDp = 844)
@Composable
private fun FullPlayerErrorPreview() = io.max.paperplay.ui.PreviewFrame(dark = true) {
    FullPlayer(io.max.paperplay.ui.previewPlayback(false), "Could not load the song", io.max.paperplay.ui.FakePlayerControls(), io.max.paperplay.ui.previewActions(), onClose = {})
}

@Preview(name = "Full player desktop", widthDp = 1100, heightDp = 700)
@Composable
private fun FullPlayerDesktopPreview() = io.max.paperplay.ui.PreviewFrame(dark = true) {
    FullPlayer(io.max.paperplay.ui.previewPlayback(), null, io.max.paperplay.ui.FakePlayerControls(), io.max.paperplay.ui.previewActions(), onClose = {})
}

@Preview(name = "Progress", widthDp = 390, showBackground = true)
@Composable
private fun ProgressPreview() = io.max.paperplay.ui.PreviewFrame {
    Column(Modifier.padding(16.dp)) { Progress(io.max.paperplay.ui.previewSongs[0], io.max.paperplay.ui.previewPlayback(), io.max.paperplay.ui.FakePlayerControls()) }
}

@Preview(name = "Queue", widthDp = 390, heightDp = 500, showBackground = true)
@Composable
private fun QueueListPreview() = io.max.paperplay.ui.PreviewFrame {
    QueueList(io.max.paperplay.ui.previewPlayback(), io.max.paperplay.ui.FakePlayerControls())
}

@Preview(name = "Now playing panel", widthDp = 320, heightDp = 640, showBackground = true)
@Composable
private fun NowPlayingPanelPreview() = io.max.paperplay.ui.PreviewFrame {
    NowPlayingPanel(io.max.paperplay.ui.previewPlayback(), io.max.paperplay.ui.FakePlayerControls(), io.max.paperplay.ui.previewActions(), onOpen = {}, onHide = {})
}

@Preview(name = "Lyrics dialog")
@Composable
private fun LyricsDialogPreview() = io.max.paperplay.ui.PreviewFrame {
    LyricsDialog("1", "Supermassive Black Hole", io.max.paperplay.ui.FakePlayerControls(), onClose = {})
}

@Preview(name = "Sleep timer dialog")
@Composable
private fun SleepTimerDialogPreview() = io.max.paperplay.ui.PreviewFrame {
    SleepTimerDialog(io.max.paperplay.ui.FakePlayerControls(sleepAt = 1L), onClose = {})
}
