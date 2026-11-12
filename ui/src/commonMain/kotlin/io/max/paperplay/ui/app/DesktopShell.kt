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
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DriveFileMove
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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

/** Library tools that open as a settings page. */
internal val toolPages = listOf(
    SidePage("sort", "Sort General", Icons.Rounded.DriveFileMove), SidePage("dupes", "Duplicates", Icons.Rounded.ContentCopy),
    SidePage("missing", "Song info left", Icons.Rounded.Rule),
)

/** Tints the native title bar with the colour the page gradient starts from, so the two meet without a seam. */
@Composable
fun WindowTint(color: Color) {
    val windowTheme = io.max.paperplay.ui.LocalAppEnv.current?.windowTheme
    androidx.compose.runtime.LaunchedEffect(color, windowTheme) {
        val dark = color.luminance() < 0.5f
        windowTheme?.invoke(dark, color.toArgb(), (if (dark) Color.White else Color.Black).toArgb())
    }
}

/** The desktop card: rounded, a shade above the page background. */
@Composable
fun Modifier.deskCard(): Modifier = partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))

@Composable
fun Sidebar(
    page: String, playlistId: String?, playlists: List<GroupUi>,
    expanded: Boolean, onToggle: () -> Unit,
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
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        ) {
            icon()
            if (expanded) {
                Text(label, Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Bold else null)
            }
        }
    }
    @Composable
    fun label(text: String, trailing: (@Composable () -> Unit)? = null) {
        if (expanded) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                trailing?.invoke()
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
    }
    LazyColumn(modifier.deskCard().padding(vertical = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = if (expanded) Arrangement.End else Arrangement.Center) {
                IconButton(onToggle) { AppIcon(Icons.Rounded.Menu, "Toggle Sidebar") }
            }
        }
        items(mainPages, key = { it.id }) { p -> item(p.id, p.label, { AppIcon(p.icon, null, Modifier.size(22.dp)) }, page == p.id && playlistId == null) { onPage(p.id) } }
        item { label("Your library") }
        items(libraryPages, key = { it.id }) { p -> item(p.id, p.label, { AppIcon(p.icon, null, Modifier.size(22.dp)) }, page == p.id && playlistId == null) { onPage(p.id) } }
        item { label("Tools") }
        items(toolPages, key = { it.id }) { p -> item(p.id, p.label, { AppIcon(p.icon, null, Modifier.size(22.dp)) }, false) { onPage(p.id) } }
        item { item("settings", "Settings", { AppIcon(Icons.Rounded.Settings, null, Modifier.size(22.dp)) }, false) { onPage("settings") } }
        item { label("Playlists") { IconButton(onNewPlaylist, Modifier.size(32.dp)) { AppIcon(Icons.Rounded.Add, "New playlist", Modifier.size(20.dp)) } } }
        items(playlists, key = { it.id }) { g -> item(g.id, g.name, { CoverGrid(g.coverUris, g.id, 28.dp, 6.dp) }, playlistId == g.id) { onPlaylist(g) } }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** The bar along the bottom of a desktop window: song, transport and seek, then panel, queue, volume and full screen. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun PlayerBar(
    state: PlaybackUiState, volume: Float, panelOn: Boolean, backend: PlayerControls?, actions: ListActions,
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
                IconButton({ backend?.toggleShuffle() }) { AppIcon(Icons.Rounded.Shuffle, "Shuffle", tint = if (state.shuffle) scheme.primary else scheme.onSurface) }
                IconButton({ backend?.previous() }) { AppIcon(Icons.Rounded.SkipPrevious, "Previous") }
                FilledIconButton(
                    { backend?.togglePlay() }, Modifier.size(44.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.onSurface, contentColor = scheme.surface),
                ) { AppIcon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play") }
                IconButton({ backend?.next() }) { AppIcon(Icons.Rounded.SkipNext, "Next") }
                IconButton({ backend?.cycleRepeat() }) {
                    AppIcon(if (state.repeat == RepeatUi.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (state.repeat != RepeatUi.Off) scheme.primary else scheme.onSurface)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ElapsedSeek(backend, state.durationMs, muted, Modifier.weight(1f), enabled = song != null)
                Text(formatDuration(state.durationMs / 1000), style = muted)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            var showLyrics by remember { mutableStateOf(false) }
            var showSleep by remember { mutableStateOf(false) }
            val on = scheme.primary
            val off = scheme.onSurfaceVariant
            val sz = Modifier.size(22.dp)
            IconButton(onTogglePanel) { AppIcon(Icons.Rounded.ViewSidebar, if (panelOn) "Hide the now playing panel" else "Show the now playing panel", sz, tint = if (panelOn) on else off) }
            IconButton(onOpen) { AppIcon(Icons.Rounded.QueueMusic, "Queue", sz, tint = off) }
            IconButton({ showLyrics = true }, enabled = song != null) { AppIcon(Icons.Outlined.Lyrics, "Lyrics", sz, tint = off.copy(alpha = if (song != null) 1f else 0.4f)) }
            IconButton({ showSleep = true }) { AppIcon(Icons.Outlined.Bedtime, "Sleep timer", sz, tint = off) }
            if (showLyrics && backend != null) song?.let { LyricsDialog(it.id, it.title, backend) { showLyrics = false } }
            if (showSleep && backend != null) SleepTimerDialog(backend) { showSleep = false }
            Spacer(Modifier.width(8.dp))
            IconButton({ backend?.setVolume(if (volume > 0f) 0f else 1f) }) { AppIcon(if (volume > 0f) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, "Mute", sz, tint = off) }
            Slider(
                volume, { backend?.setVolume(it) }, Modifier.width(100.dp),
                thumb = { Box(Modifier.size(10.dp).clip(CircleShape).background(scheme.onSurface)) },
                track = { st -> SliderDefaults.Track(st, Modifier.height(4.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                    colors = SliderDefaults.colors(activeTrackColor = scheme.onSurface, inactiveTrackColor = scheme.onSurface.copy(alpha = 0.2f))) },
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onOpen) { AppIcon(Icons.Rounded.Fullscreen, "Full screen player", Modifier.size(26.dp), tint = off) }
        }
    }
}

/** Elapsed time and the thin seek bar; the only part of the player bar that follows the position. */
@Composable
private fun ElapsedSeek(backend: PlayerControls?, durationMs: Long, style: androidx.compose.ui.text.TextStyle, modifier: Modifier, enabled: Boolean) {
    val pos = backend?.position?.collectAsState()?.value ?: io.max.paperplay.ui.state.PositionUi(0, 0)
    Text(formatDuration(pos.positionMs / 1000), style = style)
    ThinSeek({ pos.progress(durationMs) }, { backend?.seekTo(it) }, modifier.padding(horizontal = 10.dp), enabled = enabled)
}

/** A thin seek bar: tap or drag; the seek is sent when the drag ends. */
@Composable
fun ThinSeek(progress: () -> Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
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
            val fill = scheme.onSurface
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).drawBehind { drawRect(fill, size = androidx.compose.ui.geometry.Size(size.width * (dragging ?: progress()).coerceIn(0f, 1f), size.height)) })
        }
    }
}

/** What the app is doing right now; the full task list comes with the tasks work. */
@Composable
fun TasksPage(scan: ScanUi?, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    Column(modifier) {
        if (onBack != null) {
            BackTopBar("Tasks", onBack)
        } else {
            TopBar("Tasks")
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Icons.Rounded.TaskAlt, null)
            Text(scan?.text ?: "Nothing running", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
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

            val isLazy = page == "missing" || page == "index" || page == "log" || page?.startsWith("index:") == true
            Column(
                Modifier.fillMaxSize()
                    .then(if (isLazy) Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .padding(bottom = 24.dp)
            ) {
                if (page != null) SettingsPage(page, stack, prefs, conn, songCount, backend, onRestore)
            }
        }
    }
}


@Preview
@Composable
fun SidebarPreview() {
    Sidebar(
        page = "home", playlistId = null,
        playlists = listOf(GroupUi("1", "Chill Vibes", "10 songs"), GroupUi("2", "Workout", "25 songs")),
        expanded = true, onToggle = {},
        onPage = {}, onPlaylist = {}, onNewPlaylist = {}, modifier = Modifier.width(240.dp).height(640.dp),
    )
}

@Preview
@Composable
fun ThinSeekPreview() {
    ThinSeek(progress = { 0.4f }, onSeek = {}, modifier = Modifier.width(320.dp))
}

@Preview
@Composable
fun TasksPagePreview() {
    TasksPage(ScanUi("Reading channel 3 of 5", null))
}

@Composable
private fun PlayerBarPreviewContent(state: PlaybackUiState, dark: Boolean) = io.max.paperplay.ui.PreviewFrame(dark) {
    PlayerBar(
        state = state, volume = 0.8f, panelOn = true, backend = io.max.paperplay.ui.FakePlayerControls(),
        actions = io.max.paperplay.ui.previewActions(), onOpen = {}, onTogglePanel = {},
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Preview(name = "Player bar 1280", widthDp = 1280, heightDp = 84, showBackground = true)
@Composable
fun PlayerBarPreview() = PlayerBarPreviewContent(io.max.paperplay.ui.previewPlayback(), dark = false)

@Preview(name = "Player bar 900 dark", widthDp = 900, heightDp = 84, showBackground = true)
@Composable
fun PlayerBarNarrowPreview() = PlayerBarPreviewContent(io.max.paperplay.ui.previewPlayback(false), dark = true)

@Preview(name = "Player bar idle", widthDp = 1280, heightDp = 84, showBackground = true)
@Composable
fun PlayerBarIdlePreview() = PlayerBarPreviewContent(PlaybackUiState(), dark = false)
