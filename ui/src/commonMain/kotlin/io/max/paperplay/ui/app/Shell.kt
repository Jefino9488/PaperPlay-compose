package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.max.paperplay.ui.PlatformBackHandler
import io.max.paperplay.ui.components.LocalCoverLoader
import io.max.paperplay.ui.components.LocalOnMissingCover
import io.max.paperplay.ui.isPhone
import io.max.paperplay.ui.state.Load
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun MainShell(backend: CoreBackend, prefs: UiPrefs, onRestore: () -> Unit, open: MutableStateFlow<String?>? = null) {
    val lib by backend.library.collectAsState()
    val favorites by backend.favorites.collectAsState()
    val recents by backend.recents.collectAsState()
    val home by backend.home.collectAsState()
    val pb by backend.playback.collectAsState()
    val pbError by backend.playbackError.collectAsState()
    val conn by backend.connection.collectAsState()
    val volume by backend.volume.collectAsState()

    // Phone: four tabs. Desktop: a sidebar page id.
    var tab by remember { mutableIntStateOf(0) }
    var libraryTab by remember { mutableStateOf<Int?>(null) }
    var profilePage by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf("home") }
    var detail by remember { mutableStateOf<Detail?>(null) }
    var settings by remember { mutableStateOf(false) }
    val settingsStack = remember { mutableStateListOf<String>() }
    var showPlayer by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<Set<String>?>(null) }
    var newPlaylist by remember { mutableStateOf(false) }
    // Songs waiting for a playlist that is about to be created.
    var pendingAdd by remember { mutableStateOf<Set<String>?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val loader = remember { FileCoverLoader() }
    val backdrop = rememberLayerBackdrop()

    val tagDialogs = remember { TagDialogState() }
    val actions = remember(backend) {
        ListActions(
            play = backend::play,
            shuffle = backend::shuffleAll,
            setFavorite = { s, f -> backend.setFavorite(s.id, f) },
            addToPlaylist = { pickFor = it },
            notice = { scope.launch { snack.showSnackbar(it) } },
            playNext = { s -> backend.playNext(s); scope.launch { snack.showSnackbar("Plays next") } },
            telegramLink = backend::telegramLink,
            findMetadata = { s -> tagDialogs.match = s },
            editTags = { s -> tagDialogs.tags = s },
            changeCover = { s -> tagDialogs.cover = s },
        )
    }

    // A notification tap: the player, or Profile (where tasks are listed) for the background notification.
    val openRequest = open?.collectAsState()?.value
    LaunchedEffect(openRequest) {
        when (openRequest) {
            "player" -> showPlayer = true
            "upload" -> { page = "upload"; detail = null; settings = false; showPlayer = false }
            "tasks" -> { tab = 3; profilePage = "tasks"; page = "tasks"; detail = null; settings = false; showPlayer = false }
        }
        if (openRequest != null) open?.value = null
    }
    // One handler: several enabled handlers would let the last one win.
    PlatformBackHandler(showPlayer || settings || detail != null || profilePage != null || tab != 0) {
        when {
            showPlayer -> showPlayer = false
            settings -> if (settingsStack.isEmpty()) settings = false else settingsStack.removeAt(settingsStack.lastIndex)
            detail != null -> detail = null
            tab == 3 && profilePage != null -> profilePage = null
            else -> tab = 0
        }
    }

    val songs = (lib.songs as? Load.Ready)?.value.orEmpty()
    val currentId = pb.current?.id

    // Cover gradient: the current song's colours wash down from the top of every page.
    val base = MaterialTheme.colorScheme.background
    val dark = base.luminance() < 0.5f
    val tint = (if (prefs.coverGradient) coverScheme(pb.current?.coverUri, dark) else null)?.primaryContainer ?: MaterialTheme.colorScheme.primaryContainer
    val g0 by androidx.compose.animation.animateColorAsState(tint.copy(alpha = 0.85f).compositeOver(base), androidx.compose.animation.core.tween(700))
    val g1 by androidx.compose.animation.animateColorAsState(tint.copy(alpha = 0.32f).compositeOver(base), androidx.compose.animation.core.tween(700))
    val pageBg = if (prefs.coverGradient) Modifier.background(Brush.verticalGradient(0f to g0, 0.45f to g1, 1f to base)) else Modifier.background(base)

    CompositionLocalProvider(LocalCoverLoader provides loader, LocalOnMissingCover provides backend::ensureCover) {
        TagDialogHost(backend, tagDialogs) { m -> scope.launch { snack.showSnackbar(m) } }
        Box(Modifier.fillMaxSize().then(pageBg)) {
            if (isPhone) {
                val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val navHeight = if (prefs.navLayout == NavLayout.Labels || prefs.navLayout == NavLayout.Dock) 76.dp else 64.dp
                val barsHeight = (if (pb.current != null) 70.dp + 8.dp else 0.dp) + navHeight + (if (prefs.floatingNav) 24.dp else 0.dp) + bottomInset
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop).padding(top = top)) {
                    val d = detail
                    when {
                        settings -> SettingsHost(settingsStack, prefs, conn, songs.size, backend, onRestore, { settings = false }, barsHeight)
                        d != null -> DetailScreen(d, backend, currentId, actions, { detail = null }, barsHeight)
                        tab == 0 -> HomeScreen(lib, home, favorites, pb, conn, actions, { detail = it }, barsHeight)
                        tab == 1 -> SearchScreen(backend, songs, currentId, actions, { detail = it }, barsHeight)
                        tab == 2 -> LibraryScreen(lib, favorites, currentId, actions, { detail = it }, { newPlaylist = true }, barsHeight, selectedTab = libraryTab,
                            recents = recents, onImportSpotify = { settings = true; settingsStack.clear(); settingsStack.add("spotify") })
                        tab == 3 && profilePage == "tasks" -> TasksScreen(backend.tasks, Modifier.fillMaxSize().padding(bottom = barsHeight), onBack = { profilePage = null })
                        else -> ProfileScreen(
                            songs.size, favorites.size, lib.playlists.size, conn, prefs,
                            conn.chats.firstOrNull { it.id == backend.mainChatId() }?.title,
                            onSettings = { id ->
                                settings = true
                                settingsStack.clear()
                                id?.let(settingsStack::add)
                            },
                            onTasks = { profilePage = "tasks" },
                            onLibraryTab = { selected ->
                                profilePage = null
                                libraryTab = selected
                                tab = 2
                            },
                            barsHeight,
                        )
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(if (prefs.floatingNav) Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp + bottomInset) else Modifier),
                ) {
                    if (pb.current != null) {
                        MiniPlayer(pb, { showPlayer = true }, backend::togglePlay, backend::next, backend::previous, if (prefs.floatingNav) Modifier else Modifier.padding(horizontal = 8.dp))
                        Box(Modifier.height(8.dp))
                    }
                    NavBar(
                        selected = if (settings) 3 else tab,
                        backdrop = backdrop,
                        prefs = prefs,
                        inset = bottomInset,
                        onSelect = { tab = it; detail = null; settings = false; settingsStack.clear() },
                        taskRunning = conn.scanText != null,
                    )
                }
                if (showPlayer) FullPlayer(pb, pbError, backend, actions, { showPlayer = false })
                SnackbarHost(snack, Modifier.align(Alignment.TopCenter).padding(top = top))
            } else {
                // Desktop: sidebar, content card, now playing panel, player bar.
                LaunchedEffect(settings) { if (settings && settingsStack.isEmpty()) settingsStack.add("style") }
                val playlistId = (detail as? Detail.Playlist)?.id
                val panel = prefs.nowPlayingPanel && pb.current != null
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp)) {
                        Sidebar(
                            page, playlistId, lib.playlists,
                            onPage = { page = it; detail = null; settings = false; settingsStack.clear() },
                            onPlaylist = { detail = Detail.Playlist(it.id, it.name); settings = false },
                            onNewPlaylist = { newPlaylist = true },
                            modifier = Modifier.width(240.dp).fillMaxHeight(),
                        )
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f).fillMaxHeight().deskCard()) {
                            val d = detail
                            val pad = 16.dp
                            when {
                                settings -> DesktopSettings(settingsStack, prefs, conn, songs.size, backend, onRestore, { settings = false; settingsStack.clear() }, Modifier.fillMaxSize())
                                d != null -> DetailScreen(d, backend, currentId, actions, { detail = null }, pad)
                                page == "home" -> HomeScreen(lib, home, favorites, pb, conn, actions, { detail = it }, pad)
                                page == "search" -> SearchScreen(backend, songs, currentId, actions, { detail = it }, pad)
                                page == "profile" -> ProfileScreen(
                                    songs.size, favorites.size, lib.playlists.size, conn, prefs,
                                    conn.chats.firstOrNull { it.id == backend.mainChatId() }?.title,
                                    onSettings = { id ->
                                        settings = true
                                        settingsStack.clear()
                                        id?.let(settingsStack::add)
                                    },
                                    onTasks = { page = "tasks" },
                                    onLibraryTab = { selected ->
                                        page = when (selected) {
                                            3 -> "playlists"
                                            4 -> "favorites"
                                            else -> "songs"
                                        }
                                    },
                                    pad,
                                )
                                page == "tasks" -> TasksScreen(backend.tasks, onBack = { page = "profile" })
                                page == "upload" -> Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { TopBar("Upload"); UploadsPage(backend); androidx.compose.foundation.layout.Spacer(Modifier.height(120.dp)) }
                                page == "songs" -> Column { TopBar("Songs"); SongList(songs, currentId, actions, Modifier.fillMaxSize(), pad, empty = "No songs yet. Add a chat in Settings.") }
                                page == "playlists" -> Column {
                                    TopBar("Playlists")
                                    PlaylistsTab(lib.playlists, favorites, recents, pad, { detail = it }, { newPlaylist = true },
                                        { settings = true; settingsStack.clear(); settingsStack.add("spotify") })
                                }
                                page == "albums" -> Column { TopBar("Albums"); GroupGrid(lib.albums, pad, "No albums yet.") { detail = Detail.Album(it.name) } }
                                page == "artists" -> Column { TopBar("Artists"); GroupList(lib.artists, pad, "No artists yet.", circle = true) { detail = Detail.Artist(it.name) } }
                                page == "favorites" -> Column { TopBar("Favorites"); SongList(favorites, currentId, actions, Modifier.fillMaxSize(), pad, empty = "No favorites yet.") }
                                else -> Column { TopBar("Recently played"); SongList(recents, currentId, actions, Modifier.fillMaxSize(), pad, sortable = false, empty = "Nothing played yet.") }
                            }
                        }
                        if (panel) {
                            Spacer(Modifier.width(12.dp))
                            NowPlayingPanel(pb, backend, actions, { showPlayer = true }, { prefs.putNowPlayingPanel(false) }, Modifier.width(320.dp).fillMaxHeight().deskCard())
                        }
                    }
                    PlayerBar(pb, volume, prefs.nowPlayingPanel, backend, actions, { showPlayer = true }, { prefs.putNowPlayingPanel(!prefs.nowPlayingPanel) })
                }
                if (showPlayer) FullPlayer(pb, pbError, backend, actions, { showPlayer = false })
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
            }
        }

        pickFor?.let { ids ->
            PlaylistPicker(
                lib.playlists, onPick = { backend.addToPlaylist(it, ids); pickFor = null },
                onNew = { pickFor = null; newPlaylist = true; pendingAdd = ids }, onDismiss = { pickFor = null },
            )
        }
        if (newPlaylist) {
            NameDialog("New playlist", onDone = { name ->
                backend.createPlaylist(name, pendingAdd.orEmpty())
                pendingAdd = null
                newPlaylist = false
            }, onDismiss = { newPlaylist = false; pendingAdd = null })
        }
    }
}

@Composable
private fun PlaylistPicker(
    playlists: List<io.max.paperplay.ui.state.GroupUi>, onPick: (String) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = overlayRim(RoundedCornerShape(28.dp)), shape = RoundedCornerShape(28.dp), containerColor = overlayColor(),
        confirmButton = { TextButton(onNew) { Text("New playlist") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        title = { Text("Add to playlist") },
        text = {
            if (playlists.isEmpty()) Text("No playlists yet.")
            else LazyColumn {
                items(playlists, key = { it.id }) { p ->
                    Text(p.name, Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
    )
}

@Composable
private fun NameDialog(title: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = overlayRim(RoundedCornerShape(28.dp)), shape = RoundedCornerShape(28.dp), containerColor = overlayColor(),
        confirmButton = { TextButton({ onDone(name) }) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
    )
}

@Preview
@Composable
private fun PlaylistPickerPreview() {
    val samplePlaylists = listOf(io.max.paperplay.ui.state.GroupUi(id = "1", name = "Chill Vibes", subtitle = "10 songs"))
    PlaylistPicker(playlists = samplePlaylists, onPick = {}, onNew = {}, onDismiss = {})
}

@Preview
@Composable
private fun NameDialogPreview() {
    NameDialog(title = "New playlist", onDone = {}, onDismiss = {})
}

