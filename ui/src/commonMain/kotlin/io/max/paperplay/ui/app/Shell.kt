package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import io.max.paperplay.ui.theme.schemeFromSeed
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewSidebar
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
import androidx.compose.animation.AnimatedContent
import io.max.paperplay.ui.theme.animatedWidth
import io.max.paperplay.ui.theme.panelEnter
import io.max.paperplay.ui.theme.panelExit
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import io.max.paperplay.ui.mouseBackButton
import io.max.paperplay.ui.state.Load
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private data class NavSnapshot(
    val tab: Int, val libraryTab: Int?, val profilePage: String?, val detail: Detail?,
    val settings: Boolean, val settingsStack: List<String>, val settingsDeep: Boolean, val page: String,
)

@Composable
fun MainShell(backend: CoreBackend, prefs: UiPrefs, onRestore: () -> Unit, open: MutableStateFlow<String?>? = null) {
    val lib by backend.library.collectAsState()
    val favorites by backend.favorites.collectAsState()
    val recents by backend.recents.collectAsState()
    val home by backend.home.collectAsState()
    val pb by backend.playbackMeta.collectAsState()
    // Position and scan text change every tick: keep them as State and read them only in draw lambdas and small leaf composables.
    val pos = backend.position.collectAsState()
    val scan = backend.scan.collectAsState()
    val scanning by backend.scanning.collectAsState()
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
    // True when a page was opened straight from Profile or a library link: Back then returns there, not to the Settings list.
    var settingsDeep by remember { mutableStateOf(false) }
    var showPlayer by remember { mutableStateOf(false) }
    // How far the full player has slid up; it follows a drag and the back swipe.
    val sheet = remember { PlayerSheetState() }
    // Where the phone came from, so Back returns there instead of jumping to Home or Settings.
    val history = remember { mutableStateListOf<NavSnapshot>() }
    fun snapshot() = NavSnapshot(tab, libraryTab, profilePage, detail, settings, settingsStack.toList(), settingsDeep, page)
    fun navigate(change: () -> Unit) {
        val before = snapshot()
        change()
        if (snapshot() != before) {
            history.add(before)
            if (history.size > 40) history.removeAt(0)
        }
    }
    fun goBack() {
        val to = history.removeLastOrNull()
        if (to != null) {
            tab = to.tab; libraryTab = to.libraryTab; profilePage = to.profilePage; detail = to.detail
            settings = to.settings; settingsDeep = to.settingsDeep; page = to.page
            settingsStack.clear(); settingsStack.addAll(to.settingsStack)
        } else when {
            settings -> { settings = false; settingsStack.clear() }
            detail != null -> detail = null
            profilePage != null -> profilePage = null
            else -> { tab = 0; page = "home" }
        }
    }
    fun handleBack() {
        when {
            showPlayer -> showPlayer = false
            settings && settingsStack.size > (if (settingsDeep || !isPhone) 1 else 0) -> settingsStack.removeAt(settingsStack.lastIndex)
            else -> goBack()
        }
    }
    var pickFor by remember { mutableStateOf<Set<String>?>(null) }
    var newPlaylist by remember { mutableStateOf(false) }
    // Songs waiting for a playlist that is about to be created.
    var pendingAdd by remember { mutableStateOf<Set<String>?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val loader = SharedCovers.loader
    // Glass needs blur layers; with every part on Material they are skipped.
    val navGlass = prefs.look(LookGroup.Nav) == Look.Glass
    val anyGlass = LookGroup.entries.any { prefs.look(it) == Look.Glass }
    val backdrop = rememberLayerBackdrop()
    val pageBackdrop = rememberLayerBackdrop()

    val tagDialogs = remember { TagDialogState() }
    val actions = remember(backend) {
        ListActions(
            play = backend::play,
            shuffle = backend::shuffleAll,
            setFavorite = { s, f -> backend.setFavorite(s.id, f) },
            addToPlaylist = { pickFor = it },
            notice = { scope.launch { snack.showSnackbar(it) } },
            playNext = { s -> backend.playNext(s); scope.launch { snack.showSnackbar("Plays next") } },
            setPinned = { s, p -> backend.setPinned(s.id, p) },
            deleteSong = { s -> backend.deleteSong(s.id) { m -> scope.launch { snack.showSnackbar(m) } } },
            telegramLink = backend::telegramLink,
            findMetadata = { s -> tagDialogs.match = s },
            editTags = { s -> tagDialogs.tags = s },
            changeCover = { s -> tagDialogs.cover = s },
        )
    }

    // A notification tap: the player, or Profile (where tasks are listed) for the background notification.
    val openRequest = open?.collectAsState()?.value
    // A short notice while this device dials a paired device, and when one links. The sync object appears once the
    // Connection page or the saved "on" setting creates it, so wait for it.
    LaunchedEffect(Unit) {
        var lan = backend.lanSync
        while (lan == null) { kotlinx.coroutines.delay(2_000); lan = backend.lanSync }
        val sync = lan
        launch {
            // The sync loop dials an offline paired device every 20 seconds; each device is announced once per run.
            val announced = mutableSetOf<String>()
            sync.connecting.collect { now ->
                now.firstOrNull { announced.add(it) }?.let { name -> snack.currentSnackbarData?.dismiss(); launch { snack.showSnackbar("Connecting to $name", duration = androidx.compose.material3.SnackbarDuration.Short) } }
            }
        }
        var known = sync.connected.value.map { it.id }.toSet()
        sync.connected.collect { devices ->
            val now = devices.map { it.id }.toSet()
            devices.firstOrNull { it.id !in known }?.let { d -> snack.currentSnackbarData?.dismiss(); launch { snack.showSnackbar("Connected to ${d.name}", duration = androidx.compose.material3.SnackbarDuration.Short) } }
            known = now
        }
    }
    LaunchedEffect(openRequest) {
        when (openRequest) {
            "player" -> showPlayer = true
            "upload" -> { page = "upload"; detail = null; settings = false; showPlayer = false }
            "tasks" -> { navigate { tab = 3; profilePage = "tasks"; detail = null; settings = false }; page = "tasks"; showPlayer = false }
        }
        if (openRequest != null) open?.value = null
    }
    // One handler: several enabled handlers would let the last one win.
    PlatformBackHandler(
        showPlayer || settings || detail != null || profilePage != null || tab != 0 || history.isNotEmpty(),
        // The player shrinks a little as the swipe goes; a cancelled swipe puts it back.
        onProgress = { p -> if (showPlayer) scope.launch { sheet.progress.snapTo(1f - 0.3f * p) } },
        onBack = ::handleBack,
    )

    val songs = (lib.songs as? Load.Ready)?.value.orEmpty()
    val currentId = pb.current?.id
    val mainChatTitle = remember(conn.chats, settings) { conn.chats.firstOrNull { it.id == backend.mainChatId() }?.title }

    // Cover gradient: the current song's colours wash down from the top of every page.
    val base = MaterialTheme.colorScheme.background
    val dark = base.luminance() < 0.5f
    val tint = (if (prefs.coverGradient) coverScheme(pb.current?.coverUri, dark) else null)?.primaryContainer ?: MaterialTheme.colorScheme.primaryContainer
    val g0 = androidx.compose.animation.animateColorAsState(backgroundTopColor(prefs, tint, base), androidx.compose.animation.core.tween(700))
    // The page itself is drawn by AppBackground; g0 only tints the window and top areas.
    // Colour of the page where a pinned list bar sits, a fifth of the way down: the cover wash eases from g0 to g1 over the top 45%.
    val pinnedBar = androidx.compose.runtime.remember(tint, base, prefs.background) {
        androidx.compose.runtime.derivedStateOf {
            if (prefs.background == BgMode.Cover) {
                val g1 = tint.copy(alpha = 0.32f).compositeOver(base)
                androidx.compose.ui.graphics.lerp(g0.value, g1, 0.45f)
            } else base
        }
    }

    val lanSync = rememberLanSync(backend)
    CompositionLocalProvider(LocalLanSync provides lanSync, LocalCoverLoader provides loader, LocalOnMissingCover provides backend::ensureCover, LocalListActions provides actions, LocalTelegramLists provides backend::telegramLists, LocalPageTop provides pinnedBar, io.max.paperplay.ui.LocalGlassBackdrop provides (if (anyGlass) pageBackdrop else null)) {
        TagDialogHost(backend, tagDialogs) { m -> scope.launch { snack.showSnackbar(m) } }
        Box(Modifier.fillMaxSize()) {
            // The page background alone is the layer glass cards and bars blur (content on it would recurse).
            Box(if (anyGlass) Modifier.fillMaxSize().layerBackdrop(pageBackdrop) else Modifier.fillMaxSize()) {
                AppBackground(prefs, pb.current?.coverUri, base)
            }
            if (isPhone) {
                val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val navHeight = if (prefs.navLayout == NavLayout.Labels || prefs.navLayout == NavLayout.Dock) 76.dp else 64.dp
                val barsHeight = (if (pb.current != null) 70.dp + 8.dp else 0.dp) + navHeight + (if (prefs.floatingNav) 24.dp else 0.dp) + bottomInset + 40.dp // extra scroll room so the last item clears the bar
                Box((if (navGlass) Modifier.fillMaxSize().layerBackdrop(backdrop) else Modifier.fillMaxSize()).padding(top = top)) {
                    // Each page is drawn from its own key, so the page that is leaving keeps showing what it showed.
                    val reduceMotion = io.max.paperplay.ui.theme.LocalReduceMotion.current
                    // A tab remembers where its list was scrolled to while you are on another page.
                    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                    AnimatedContent(
                        targetState = PageKey(settings, detail, tab, tab == 3 && profilePage == "tasks"),
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = { pageTransition(navMove(initialState.level, targetState.level), reduceMotion) },
                        label = "page",
                    ) { k ->
                    val phonePage: @Composable () -> Unit = {
                    val d = k.detail
                    when {
                        k.settings -> SettingsHost(settingsStack, prefs, conn, songs.size, backend, onRestore, ::goBack, barsHeight, deep = settingsDeep)
                        d != null -> DetailScreen(d, backend, currentId, actions, ::goBack, barsHeight)
                        k.tab == 0 -> HomeScreen(lib, home, favorites, currentId, conn, scan, actions, { d2 -> navigate { detail = d2 } }, barsHeight, name = prefs.profileName)
                        k.tab == 1 -> SearchScreen(backend::search, songs, currentId, actions, { d2 -> navigate { detail = d2 } }, barsHeight)
                        k.tab == 2 -> LibraryScreen(lib, favorites, currentId, actions, { d2 -> navigate { detail = d2 } }, { newPlaylist = true }, barsHeight, selectedTab = libraryTab,
                            recents = recents, onImportSpotify = { navigate { settings = true; settingsDeep = true; settingsStack.clear(); settingsStack.add("spotify") } })
                        k.tasks -> TasksScreen(backend.tasks, Modifier.fillMaxSize().padding(bottom = barsHeight), onBack = ::goBack)
                        else -> ProfileScreen(
                            songs.size, favorites.size, lib.playlists.size, conn, prefs,
                            mainChatTitle,
                            onSettings = { id ->
                                navigate {
                                    settings = true
                                    settingsDeep = id != null
                                    settingsStack.clear()
                                    id?.let(settingsStack::add)
                                }
                            },
                            onTasks = { navigate { profilePage = "tasks" } },
                            onLibraryTab = { selected ->
                                navigate {
                                    profilePage = null
                                    libraryTab = selected
                                    tab = 2
                                }
                            },
                            barsHeight,
                            scan = scan,
                        )
                    }
                    }
                    if (k.settings || k.detail != null || k.tasks) phonePage() else tabStates.SaveableStateProvider("tab${k.tab}") { phonePage() }
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(if (prefs.floatingNav) Modifier.padding(start = 16.dp, end = 16.dp, bottom = bottomInset.coerceAtLeast(12.dp)) else Modifier),
                ) {
                    if (pb.current != null) {
                        MiniPlayer(pb, { showPlayer = true }, backend::togglePlay, backend::next, backend::previous, { pos.value.progress(pb.durationMs) }, Modifier.graphicsLayer { alpha = 1f - sheet.progress.value }.then(if (prefs.floatingNav) Modifier else Modifier.padding(horizontal = 8.dp)),
                            onDevices = { id -> navigate { settings = true; settingsDeep = true; settingsStack.clear(); settingsStack.add(id) } })
                        Box(Modifier.height(8.dp))
                    }
                    NavBar(
                        selected = if (settings) 3 else tab,
                        backdrop = backdrop,
                        prefs = prefs,
                        inset = bottomInset,
                        onSelect = { sel -> navigate { tab = sel; detail = null; settings = false; settingsStack.clear(); profilePage = null } },
                        taskRunning = scanning,
                    )
                }
                PlayerSheetHost(showPlayer, sheet) {
                    FullPlayer(
                        pb, pbError, backend, actions, { showPlayer = false },
                        onOpenSettings = { id -> navigate { settings = true; settingsDeep = true; settingsStack.clear(); settingsStack.add(id) } },
                    )
                }
                SnackbarHost(snack, Modifier.align(Alignment.TopCenter).padding(top = top))
            } else {
                // Desktop: sidebar, content card, now playing panel, player bar.
                LaunchedEffect(settings) { if (settings && settingsStack.isEmpty()) settingsStack.add("style") }
                val playlistId = (detail as? Detail.Playlist)?.id
                var showNow by remember { mutableStateOf(false) }
                LaunchedEffect(page, detail, settings) { showNow = false }
                val keys = rememberPlayerKeys(backend, volume)
                PublishMiniState(pb, backend, prefs.miniFormat)
                val panel = prefs.nowPlayingPanel && pb.current != null && !showNow
                var sidebarExpanded by remember { mutableStateOf(true) }
                WindowTint(g0.value)
                // The mouse back button goes back like the phone gesture.
                // While the full now playing view is open the whole window takes the song's colours, so the sidebar and
                // player bar sit on the same wash as the card instead of on the plain page background.
                val nowSong = if (showNow) pb.current else null
                val shell: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize().mouseBackButton(::handleBack).playerKeys(keys)) {
                    Row(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp)) {
                        Sidebar(
                            page, playlistId, lib.playlists,
                            expanded = sidebarExpanded,
                            onToggle = { sidebarExpanded = !sidebarExpanded },
                            onPage = { target ->
                                navigate {
                                    detail = null; settingsStack.clear()
                                    if (target == "settings") { settings = true; settingsDeep = false } else if (target in toolPages.map { t -> t.id }) { settings = true; settingsStack.add(target) } else { page = target; settings = false }
                                }
                            },
                            onPlaylist = { g -> navigate { detail = Detail.Playlist(g.id, g.name); settings = false } },
                            onNewPlaylist = { newPlaylist = true },
                            modifier = Modifier.animatedWidth(if (sidebarExpanded) prefs.sidebarWidth.dp else 72.dp).fillMaxHeight(),
                        )
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f).fillMaxHeight().deskCard()) {
                            val pad = 16.dp
                            val reduceMotion = io.max.paperplay.ui.theme.LocalReduceMotion.current
                            val pageStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                            AnimatedContent(
                                targetState = DeskKey(showNow && pb.current != null, settings, detail, page),
                                modifier = Modifier.fillMaxSize(),
                                transitionSpec = { pageTransition(navMove(initialState.level, targetState.level), reduceMotion) },
                                label = "page",
                            ) { k ->
                            val deskPage: @Composable () -> Unit = {
                            val d = k.detail
                            when {
                                k.now -> DesktopNowPlayingView(pb, backend, actions, { showNow = false }, Modifier.fillMaxSize())
                                k.settings -> DesktopSettings(settingsStack, prefs, conn, songs.size, backend, onRestore, ::goBack, Modifier.fillMaxSize())
                                d != null -> DetailScreen(d, backend, currentId, actions, ::goBack, pad)
                                k.page == "home" -> HomeScreen(lib, home, favorites, currentId, conn, scan, actions, { d2 -> navigate { detail = d2 } }, pad, name = prefs.profileName)
                                k.page == "search" -> SearchScreen(backend::search, songs, currentId, actions, { d2 -> navigate { detail = d2 } }, pad)
                                k.page == "profile" -> ProfileScreen(
                                    songs.size, favorites.size, lib.playlists.size, conn, prefs,
                                    mainChatTitle,
                                    onSettings = { id ->
                                        navigate {
                                            settings = true
                                            settingsDeep = id != null
                                            settingsStack.clear()
                                            id?.let(settingsStack::add)
                                        }
                                    },
                                    onTasks = { navigate { page = "tasks" } },
                                    onLibraryTab = { selected ->
                                        navigate {
                                            page = when (selected) {
                                                3 -> "playlists"
                                                4 -> "favorites"
                                                else -> "songs"
                                            }
                                        }
                                    },
                                    pad,
                                    scan = scan,
                                )
                                k.page == "tasks" -> TasksScreen(backend.tasks, onBack = ::goBack)
                                k.page == "upload" -> Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { TopBar("Upload"); UploadsPage(backend); androidx.compose.foundation.layout.Spacer(Modifier.height(120.dp)) }
                                k.page == "songs" -> Column { TopBar("Songs"); SongList(songs, currentId, actions, Modifier.fillMaxSize(), pad, empty = "No songs yet. Add a chat in Settings.") }
                                k.page == "playlists" -> Column {
                                    TopBar("Playlists")
                                    PlaylistsTab(lib.playlists, favorites, recents, pad, { d2 -> navigate { detail = d2 } }, { newPlaylist = true },
                                        { navigate { settings = true; settingsDeep = true; settingsStack.clear(); settingsStack.add("spotify") } })
                                }
                                k.page == "albums" -> Column { TopBar("Albums"); GroupGrid(lib.albums, pad, "No albums yet.") { g -> navigate { detail = Detail.Album(g.name, g.owner) } } }
                                k.page == "artists" -> Column { TopBar("Artists"); GroupList(lib.artists, pad, "No artists yet.", circle = true) { g -> navigate { detail = Detail.Artist(g.name) } } }
                                k.page == "favorites" -> Column { TopBar("Favorites"); SongList(favorites, currentId, actions, Modifier.fillMaxSize(), pad, empty = "No favorites yet.") }
                                else -> Column { TopBar("Recently played"); SongList(recents, currentId, actions, Modifier.fillMaxSize(), pad, sortable = false, empty = "Nothing played yet.") }
                            }
                            }
                            if (k.now || k.settings || k.detail != null) deskPage() else pageStates.SaveableStateProvider(k.page) { deskPage() }
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(panel, enter = panelEnter(), exit = panelExit()) {
                            Row {
                                Spacer(Modifier.width(12.dp))
                                NowPlayingPanel(pb, backend, actions, { showPlayer = true }, { prefs.putNowPlayingPanel(false) }, Modifier.width(320.dp).fillMaxHeight().deskCard())
                            }
                        }
                    }
                    PlayerBar(pb, volume, backend, actions, { showPlayer = true },
                        onNowPlaying = { showNow = !showNow }, extras = { DevicePickerButton(onOpen = { id -> navigate { settings = true; settingsDeep = true; settingsStack.clear(); settingsStack.add(id) } }); MiniPlayerToggle() })
                }
                }
                if (nowSong != null) CoverTinted(nowSong.coverUri) {
                    Box(Modifier.fillMaxSize()) {
                        // One tone per main colour of the cover, running diagonally; a cover with one colour fades to a darker shade of it.
                        val top = MaterialTheme.colorScheme.primaryContainer
                        val tones = CoverSeeds.palette(nowSong.coverUri).map { schemeFromSeed(it, true).primaryContainer }.ifEmpty { listOf(top) }
                        val stops = if (tones.size > 1) tones else listOf(top, androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f).compositeOver(top))
                        Box(Modifier.fillMaxSize().drawBehind {
                            drawRect(androidx.compose.ui.graphics.Brush.linearGradient(stops, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, size.height)))
                        })
                        shell()
                    }
                } else shell()
                PlayerSheetHost(showPlayer, sheet) { FullPlayer(pb, pbError, backend, actions, { showPlayer = false }) }
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
                ShortcutsOverlay(keys, Modifier.fillMaxSize())
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = if (isPhone) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp else 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NetplayIndicator(
                    backend, Modifier,
                ) { navigate { settings = true; settingsDeep = true; settingsStack.clear(); settingsStack.add("netplay") } }
                // Only a way to open the panel: hidden while the panel, the now playing view or the full player is up, back once the panel is closed.
                if (!isPhone && pb.current != null && !prefs.nowPlayingPanel && !showPlayer) {
                    val panelOn = prefs.nowPlayingPanel
                    val on = MaterialTheme.colorScheme.primary
                    val off = MaterialTheme.colorScheme.onSurfaceVariant
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.clickable { prefs.putNowPlayingPanel(!prefs.nowPlayingPanel) }
                    ) {
                        io.max.paperplay.ui.components.AppIcon(
                            Icons.AutoMirrored.Rounded.ViewSidebar,
                            if (panelOn) "Hide the now playing panel" else "Show the now playing panel",
                            Modifier.padding(10.dp).size(20.dp),
                            tint = if (panelOn) on else off
                        )
                    }
                }
            }
            StatusToast(
                conn, scan, if (lib.songs is Load.Ready) songs.size else 1, g0.value,
                Modifier.align(Alignment.TopCenter).padding(top = if (isPhone) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp else 16.dp),
            )
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

