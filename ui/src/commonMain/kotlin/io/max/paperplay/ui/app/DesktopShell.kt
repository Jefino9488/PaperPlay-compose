package io.max.paperplay.ui.app

import io.max.paperplay.ui.components.AppIcon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.logic.formatDuration
import io.max.paperplay.ui.state.GroupUi
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.state.RepeatUi
import io.max.paperplay.ui.state.SongUi
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Desktop pages reachable from the sidebar. */
internal class SidePage(val id: String, val label: String, val icon: ImageVector)

internal val mainPages = listOf(
    SidePage("home", "Home", Icons.Rounded.Home), SidePage("search", "Search", Icons.Rounded.Search),
    SidePage("profile", "Profile", Icons.Rounded.AccountCircle), SidePage("tasks", "Tasks", Icons.Rounded.TaskAlt),
    SidePage("upload", "Upload", Icons.Rounded.Upload),
)
internal val libraryPages = listOf(
    SidePage("songs", "Songs", Icons.Rounded.MusicNote), SidePage("albums", "Albums", Icons.Rounded.Album),
    SidePage("artists", "Artists", Icons.Rounded.Person), SidePage("favorites", "Favorites", Icons.Rounded.Favorite),
    SidePage("recent", "Recently played", Icons.Rounded.History),
)

/** The desktop card: rounded, a shade above the page background. */
@Composable
fun Modifier.deskCard(): Modifier = partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))

@Composable
fun Sidebar(
    page: String, playlistId: String?, playlists: List<GroupUi>,
    onPage: (String) -> Unit, onPlaylist: (GroupUi) -> Unit, onNewPlaylist: () -> Unit, modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    @Composable
    fun item(id: String, label: String, icon: @Composable () -> Unit, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                .background(if (selected) scheme.surfaceContainerHighest else Color.Transparent)
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Text(label, Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Bold else null)
        }
    }
    @Composable
    fun label(text: String, trailing: (@Composable () -> Unit)? = null) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            trailing?.invoke()
        }
    }
    LazyColumn(modifier.deskCard().padding(vertical = 8.dp)) {
        items(mainPages, key = { it.id }) { p -> item(p.id, p.label, { AppIcon(p.icon, null, Modifier.size(22.dp)) }, page == p.id && playlistId == null) { onPage(p.id) } }
        item { label("Your library") }
        items(libraryPages, key = { it.id }) { p -> item(p.id, p.label, { AppIcon(p.icon, null, Modifier.size(22.dp)) }, page == p.id && playlistId == null) { onPage(p.id) } }
        item { label("Playlists") { IconButton(onNewPlaylist, Modifier.size(32.dp)) { AppIcon(Icons.Rounded.Add, "New playlist", Modifier.size(20.dp)) } } }
        items(playlists, key = { it.id }) { g -> item(g.id, g.name, { CoverGrid(g.coverUris, g.id, 28.dp, 6.dp) }, playlistId == g.id) { onPlaylist(g) } }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** The bar along the bottom of a desktop window: song, transport and seek, then panel, queue, volume and full screen. */
@Composable
fun PlayerBar(
    state: PlaybackUiState, volume: Float, panelOn: Boolean, backend: CoreBackend, actions: ListActions,
    onOpen: () -> Unit, onTogglePanel: () -> Unit, modifier: Modifier = Modifier,
) {
    val song = state.current
    val scheme = MaterialTheme.colorScheme
    val muted = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    Row(modifier.fillMaxWidth().height(84.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (song != null) {
                CoverImage(song.coverUri, song.id, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen), size = 56.dp, radius = 8.dp)
                Column(Modifier.weight(1f, fill = false).padding(start = 12.dp)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                IconButton({ actions.setFavorite(song, !song.favorite) }) {
                    AppIcon(if (song.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (song.favorite) "Remove from favorites" else "Add to favorites", tint = if (song.favorite) scheme.primary else scheme.onSurface)
                }
            }
        }
        Column(Modifier.weight(1.5f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(backend::toggleShuffle) { AppIcon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) scheme.primary else scheme.onSurface) }
                IconButton(backend::previous) { AppIcon(Icons.Rounded.SkipPrevious, "Previous") }
                FilledIconButton(
                    backend::togglePlay, Modifier.size(44.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.onSurface, contentColor = scheme.surface),
                ) { AppIcon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play") }
                IconButton(backend::next) { AppIcon(Icons.Rounded.SkipNext, "Next") }
                IconButton(backend::toggleShuffle) { AppIcon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) scheme.primary else scheme.onSurface) }
                IconButton(backend::cycleRepeat) {
                    AppIcon(if (state.repeat == RepeatUi.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (state.repeat != RepeatUi.Off) scheme.primary else scheme.onSurface)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(state.positionMs / 1000), style = muted)
                ThinSeek(state.progress, backend::seekTo, Modifier.weight(1f).padding(horizontal = 10.dp), enabled = song != null)
                Text(formatDuration(state.durationMs / 1000), style = muted)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onTogglePanel) { AppIcon(Icons.Rounded.ViewSidebar, if (panelOn) "Hide the now playing panel" else "Show the now playing panel", tint = if (panelOn) scheme.primary else scheme.onSurface) }
            IconButton(onOpen) { AppIcon(Icons.Rounded.QueueMusic, "Queue") }
            var showLyrics by remember { mutableStateOf(false) }
            var showSleep by remember { mutableStateOf(false) }
            IconButton({ showLyrics = true }, enabled = song != null) { AppIcon(Icons.Outlined.Lyrics, "Lyrics") }
            IconButton({ showSleep = true }) { AppIcon(Icons.Outlined.Bedtime, "Sleep timer") }
            if (showLyrics) song?.let { LyricsDialog(it.id, it.title, state.positionMs, backend) { showLyrics = false } }
            if (showSleep) SleepTimerDialog(backend) { showSleep = false }
            IconButton({ backend.setVolume(if (volume > 0f) 0f else 1f) }) { AppIcon(if (volume > 0f) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, "Mute") }
            Slider(
                volume, backend::setVolume, Modifier.width(110.dp),
                colors = SliderDefaults.colors(thumbColor = scheme.onSurface, activeTrackColor = scheme.onSurface, inactiveTrackColor = scheme.onSurface.copy(alpha = 0.2f)),
            )
            IconButton(onOpen) { AppIcon(Icons.Rounded.OpenInFull, "Full screen player", Modifier.size(20.dp)) }
        }
    }
}

/** A thin seek bar: tap or drag; the seek is sent when the drag ends. */
@Composable
fun ThinSeek(progress: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.height(20.dp)) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        Box(
            Modifier.fillMaxSize()
                .pointerInput(w, enabled) { if (enabled) detectTapGestures { onSeek((it.x / w).coerceIn(0f, 1f)) } }
                .pointerInput(w, enabled) {
                    if (enabled) detectHorizontalDragGestures(
                        onDragEnd = { dragging?.let(onSeek); dragging = null }, onDragCancel = { dragging = null },
                    ) { change, _ -> change.consume(); dragging = (change.position.x / w).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.2f)))
            Box(Modifier.fillMaxWidth((dragging ?: progress).coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(scheme.onSurface))
        }
    }
}

/** What the app is doing right now; the full task list comes with the tasks work. */
@Composable
fun TasksPage(conn: ConnectionUi, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    Column(modifier) {
        if (onBack != null) {
            BackTopBar("Tasks", onBack)
        } else {
            TopBar("Tasks")
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Icons.Rounded.TaskAlt, null)
            Text(conn.scanText ?: "Nothing running", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Settings on a desktop: categories on the left, the chosen page on the right. */
@Composable
fun DesktopSettings(
    stack: MutableList<String>, prefs: UiPrefs, conn: ConnectionUi, songCount: Int, backend: CoreBackend,
    onRestore: () -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val page = stack.lastOrNull()
    val root = stack.firstOrNull()
    Row(modifier) {
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onExit) { AppIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Settings", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.headlineMedium)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (root == "profile") scheme.surfaceContainerHighest else Color.Transparent)
                            .clickable { stack.clear(); stack.add("profile") }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(prefs.profilePhoto, prefs.profileName, 32.dp)
                        Text(prefs.profileName, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleSmall)
                    }
                }
                items(categories.filter { !it.phoneOnly }, key = { it.id }) { c ->
                    val on = root == c.id
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) scheme.surfaceContainerHighest else Color.Transparent)
                            .clickable { stack.clear(); stack.add(c.id) }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(c.icon, null, Modifier.size(22.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(c.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (on) FontWeight.Bold else null)
                            Text(c.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 16.dp).background(scheme.outlineVariant.copy(alpha = 0.5f)))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val title = categories.firstOrNull { it.id == page }?.title ?: settingsPageTitle(page) ?: "Settings"
            if (stack.size > 1) BackTopBar(title, onBack = { stack.removeAt(stack.lastIndex) })
            else Text(title, Modifier.padding(start = 20.dp, end = 16.dp, top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.headlineMedium)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                if (page != null) SettingsPage(page, stack, prefs, conn, songCount, backend, onRestore)
            }
        }
    }
}


@Preview
@Composable
private fun SidebarPreview() {
    Sidebar(
        page = "home", playlistId = null,
        playlists = listOf(GroupUi("1", "Chill Vibes", "10 songs"), GroupUi("2", "Workout", "25 songs")),
        onPage = {}, onPlaylist = {}, onNewPlaylist = {}, modifier = Modifier.width(240.dp).height(640.dp),
    )
}

@Preview
@Composable
private fun ThinSeekPreview() {
    ThinSeek(progress = 0.4f, onSeek = {}, modifier = Modifier.width(320.dp))
}

@Preview
@Composable
private fun TasksPagePreview() {
    TasksPage(ConnectionUi(ready = true, scanText = "Reading channel 3 of 5"))
}
