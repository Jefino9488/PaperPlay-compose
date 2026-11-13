package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import io.max.paperplay.ui.RestoreHooks
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import io.max.paperplay.ui.components.SongRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import io.max.paperplay.ui.components.AppIcon
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.state.GroupUi
import io.max.paperplay.ui.state.LibraryUi
import io.max.paperplay.ui.state.Load
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.state.SongUi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.snapshotFlow

/** Where a tap on an album, artist or playlist goes. */
sealed interface Detail {
    val title: String

    data class Album(val name: String, val artist: String? = null) : Detail { override val title get() = name }
    data class Artist(val name: String) : Detail { override val title get() = name }
    data class Playlist(val id: String, val name: String) : Detail { override val title get() = name }
    /** A fixed list: a mix, a generated tile or a genre. */
    data class Songs(override val title: String, val songs: List<SongUi>) : Detail
    /** A daily mix; songs can be marked as not fitting [moodId]. */
    data class Mix(override val title: String, val moodId: String, val songs: List<SongUi>) : Detail
}

// ---- Setup ---------------------------------------------------------------------------------------------

/** The bot's credentials. Shown before the first login, and from Settings to change them. */
@Composable
fun CredentialsForm(onSave: (apiId: String, apiHash: String, token: String) -> String?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var id by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(id, { id = it }, Modifier.fillMaxWidth(), label = { Text("API id") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(hash, { hash = it }, Modifier.fillMaxWidth(), label = { Text("API hash") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Bot token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Button({
            error = onSave(id, hash, token)
            if (error == null) onDone()
        }, Modifier.fillMaxWidth()) { Text("Save and connect") }
    }
}

@Composable
fun SetupScreen(
    backend: CoreBackend,
    restoreHooks: RestoreHooks? = null,
    onSaved: () -> Unit,
    onRestore: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp,
            ) {
                Column(
                    Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Set up a bot", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Three quick steps, then connect.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    val conn by backend.connection.collectAsState()
                    io.max.paperplay.ui.BotSetupForm(backend::saveCredentials, conn.ready, conn.error)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onRestore, Modifier.fillMaxWidth()) { Text("Restore everything from a backup") }
                }
            }
        }
    }
}

// ---- Library -------------------------------------------------------------------------------------------

private val libraryTabs = listOf("Songs", "Albums", "Artists", "Playlists", "Favorites")

@Composable
fun LibraryScreen(
    lib: LibraryUi, favorites: List<SongUi>, currentId: String?, actions: ListActions,
    onOpen: (Detail) -> Unit, onNewPlaylist: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier,
    selectedTab: Int? = null, recents: List<SongUi> = emptyList(), onImportSpotify: () -> Unit = {},
) {
    var tab by remember(selectedTab) { mutableStateOf(selectedTab ?: 0) }
    Column(modifier) {
        TopBar("Your library")
        ChipRow(libraryTabs, tab, { tab = it })
        when (tab) {
            0 -> when (val s = lib.songs) {
                is Load.Loading -> Text("Loading…", Modifier.padding(24.dp))
                is Load.Error -> Text(s.message, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                is Load.Ready -> SongList(s.value, currentId, actions, Modifier.fillMaxSize(), bottomPadding, empty = "No songs yet. Add a chat in Settings.")
            }
            1 -> GroupGrid(lib.albums, bottomPadding, "No albums yet.") { onOpen(Detail.Album(it.name, it.owner)) }
            2 -> GroupList(lib.artists, bottomPadding, "No artists yet.", circle = true) { onOpen(Detail.Artist(it.name)) }
            3 -> PlaylistsTab(lib.playlists, favorites, recents, bottomPadding, onOpen, onNewPlaylist, onImportSpotify)
            else -> SongList(favorites, currentId, actions, Modifier.fillMaxSize(), bottomPadding, empty = "No favorites yet.")
        }
    }
}

/** Albums as a grid of large covers, two columns on a phone and as many as fit on a desktop. */
@Composable
fun GroupGrid(groups: List<GroupUi>, bottomPadding: Dp, empty: String, onClick: (GroupUi) -> Unit) {
    if (groups.isEmpty()) { Text(empty, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        androidx.compose.foundation.lazy.grid.GridCells.Adaptive(160.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, bottomPadding + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(groups.size, key = { groups[it].id }) { i ->
            val g = groups[i]
            Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick(g) }) {
                androidx.compose.foundation.layout.BoxWithConstraints { CoverImage(g.coverUris.firstOrNull(), g.id, size = maxWidth, radius = 12.dp) }
                Text(g.name, Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(g.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A list of albums, artists or playlists; [circle] makes round covers for artists. */
@Composable
fun GroupList(groups: List<GroupUi>, bottomPadding: Dp, empty: String, header: (@Composable () -> Unit)? = null, circle: Boolean = false, onClick: (GroupUi) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        if (header != null) item { header() }
        if (groups.isEmpty()) item { Text(empty, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(groups, key = { it.id }) { g ->
            ClickableRow({ onClick(g) }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                CoverImage(g.coverUris.firstOrNull(), g.id, size = 56.dp, radius = if (circle) 28.dp else 10.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(g.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun DetailScreen(
    detail: Detail, backend: CoreBackend, currentId: String?, actions: ListActions, onBack: () -> Unit,
    bottomPadding: Dp, modifier: Modifier = Modifier,
) {
    val flow = remember(detail) {
        when (detail) {
            is Detail.Album -> backend.album(detail.name, detail.artist)
            is Detail.Artist -> backend.artist(detail.name)
            is Detail.Playlist -> backend.playlist(detail.id.toLongOrNull() ?: -1)
            is Detail.Songs -> flowOf(detail.songs)
            is Detail.Mix -> flowOf(detail.songs)
        }
    }
    val rawSongs by flow.collectAsState(emptyList())
    var name by remember(detail) { mutableStateOf(detail.title) }
    var dialog by remember(detail) { mutableStateOf<String?>(null) }
    // Each playlist remembers its own order between launches.
    val sortPrefs = LocalUiPrefs.current
    val sortKey = "playlist_sort:" + ((detail as? Detail.Playlist)?.id ?: detail.title)
    var sort by remember(detail) { mutableStateOf(PlaylistSort.entries.firstOrNull { it.name == sortPrefs?.getString(sortKey) } ?: PlaylistSort.Order) }
    var sortMenu by remember(detail) { mutableStateOf(false) }
    var moreMenu by remember(detail) { mutableStateOf(false) }
    var reorderOpen by remember(detail) { mutableStateOf(false) }
    var missingSheet by remember(detail) { mutableStateOf(false) }
    var refreshing by remember(detail) { mutableStateOf(false) }
    var bump by remember(detail) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var rejected by remember(detail) { mutableStateOf(emptySet<String>()) }
    val songs = remember(rawSongs, sort, rejected) { sort.apply(rawSongs).filter { it.id !in rejected } }
    val store = remember(backend) { backend.importStore() }
    val source = remember(detail) { if (detail is Detail.Playlist) backend.playlistSource(detail.id) else null }
    val missing by androidx.compose.runtime.produceState(emptyList<io.max.paperplay.core.library.MissingTrack>(), detail, bump, refreshing) {
        if (detail !is Detail.Playlist) return@produceState
        backend.services.library.changes.collect { value = detail.id.toLongOrNull()?.let { store.missing(it) }.orEmpty() }
    }
    val mixSnack = remember { androidx.compose.material3.SnackbarHostState() }
    Box(modifier) {
    Column(Modifier.fillMaxSize()) {
        BackTopBar(name, onBack) {
            if (detail is Detail.Playlist) {
                IconButton({ actions.notice(if (backend.togglePinAll(rawSongs.map { it.id })) "Keeping this playlist on this device" else "Playlist no longer kept on this device") }) { AppIcon(Icons.Rounded.PushPin, "Pin for offline") }
                AddSongsButton(backend, detail.id, rawSongs.map { it.id }.toSet(), { actions.notice(it) })
                Box {
                    IconButton({ moreMenu = true }) { AppIcon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(moreMenu, { moreMenu = false; sortMenu = false }) {
                        if (sortMenu) {
                            PlaylistSort.entries.forEach { o ->
                                DropdownMenuItem(
                                    text = { Text(if (o == sort) "${o.label} (selected)" else o.label) },
                                    onClick = { sort = o; sortPrefs?.putString(sortKey, o.name); sortMenu = false; moreMenu = false },
                                )
                            }
                        } else {
                            DropdownMenuItem(text = { Text("Sort by") }, leadingIcon = { AppIcon(Icons.AutoMirrored.Rounded.Sort, null) }, onClick = { sortMenu = true })
                            if (source != null) DropdownMenuItem(
                                text = { Text("Refresh from Spotify link") },
                                leadingIcon = { AppIcon(Icons.Rounded.Refresh, null) },
                                onClick = {
                                    moreMenu = false
                                    if (!refreshing) {
                                        refreshing = true
                                        scope.launch { actions.notice(backend.refreshPlaylist(detail.id)); refreshing = false }
                                    }
                                },
                            )
                            if (rawSongs.size > 1) DropdownMenuItem(text = { Text("Reorder songs") }, leadingIcon = { AppIcon(Icons.Rounded.SwapVert, null) }, onClick = { moreMenu = false; reorderOpen = true })
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { AppIcon(Icons.Rounded.Edit, null) }, onClick = { moreMenu = false; dialog = "rename" })
                            DropdownMenuItem(text = { Text("Delete playlist") }, leadingIcon = { AppIcon(Icons.Rounded.Delete, null) }, onClick = { moreMenu = false; dialog = "delete" })
                        }
                    }
                }
            }
        }
        SongList(
            songs, currentId, actions, Modifier.fillMaxSize(), bottomPadding, sortable = detail !is Detail.Playlist,
            header = {
                DetailHero(detail, name, songs)
                if (missing.isNotEmpty()) MissingRow(missing.size) { missingSheet = true }
            },
            onRemove = when (detail) {
                is Detail.Playlist -> { s -> backend.removeFromPlaylist(detail.id, s.id) }
                is Detail.Mix -> { s ->
                    rejected = rejected + s.id
                    backend.rejectFromMix(s, detail.moodId)
                    scope.launch {
                        val r = mixSnack.showSnackbar("Removed from ${detail.title}.", "Undo")
                        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            rejected = rejected - s.id
                            backend.undoRejectFromMix(s, detail.moodId)
                        }
                    }
                }
                else -> null
            },
        )
    }
    androidx.compose.material3.SnackbarHost(mixSnack, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding))
    }
    if (reorderOpen && detail is Detail.Playlist) ReorderButton(backend, detail.id, rawSongs, external = true) { reorderOpen = false }
    if (missingSheet && missing.isNotEmpty()) MissingSheet(backend, store, missing, name, { missingSheet = false }) { bump++ }
    if (detail is Detail.Playlist) {
        when (dialog) {
            "rename" -> {
                var text by remember { mutableStateOf(name) }
                AlertDialog(
                    onDismissRequest = { dialog = null },
                    title = { Text("Rename playlist") },
                    text = { OutlinedTextField(text, { text = it }, singleLine = true) },
                    confirmButton = { TextButton({ if (text.isNotBlank()) { backend.renamePlaylist(detail.id, text); name = text.trim() }; dialog = null }) { Text("Save") } },
                    dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } },
                )
            }
            "delete" -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Delete this playlist?") },
                text = { Text("The songs stay in your library.") },
                confirmButton = { TextButton({ backend.deletePlaylist(detail.id); dialog = null; onBack() }) { Text("Delete") } },
                dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } },
            )
        }
    }
}

/** Big cover, name, and what the list is. The play and shuffle bar sits below it in the list. */
@Composable
private fun DetailHero(detail: Detail, title: String, songs: List<SongUi>) {
    val kind = when (detail) {
        is Detail.Album -> "Album"
        is Detail.Artist -> "Artist"
        is Detail.Playlist -> "Playlist"
        is Detail.Songs, is Detail.Mix -> "Collection"
    }
    val extra = when (detail) {
        is Detail.Album -> listOfNotNull(
            songs.map { it.artist }.filter { it.isNotBlank() }.distinct().let { if (it.size > 1) "Various artists" else it.firstOrNull() },
            songs.mapNotNull { it.year }.maxOrNull()?.toString(),
        ).joinToString(" · ")
        is Detail.Artist -> songs.map { it.album }.filter { it.isNotBlank() }.distinct().size.let { if (it == 0) "" else if (it == 1) "1 album" else "$it albums" }
        else -> ""
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CoverGrid(songs.map { it.coverUri }, "hero:$title", 200.dp, if (detail is Detail.Artist) 100.dp else 20.dp)
        Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOf(kind, extra).filter { it.isNotEmpty() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Search --------------------------------------------------------------------------------------------

@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(
    search: (String) -> kotlinx.coroutines.flow.Flow<List<SongUi>>, songs: List<SongUi>, currentId: String?, actions: ListActions, onOpen: (Detail) -> Unit,
    bottomPadding: Dp, modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SongUi>>(emptyList()) }
    val recent = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        // collectLatest: the result flow never completes (it follows the library), so a plain collect would block every later query.
        snapshotFlow { query }.debounce(150).collectLatest { q ->
            if (q.isBlank()) results = emptyList()
            else search(q).collect {
                results = it
                if (it.isNotEmpty()) {
                    recent.remove(q)
                    recent.add(0, q)
                    while (recent.size > 6) recent.removeAt(recent.lastIndex)
                }
            }
        }
    }
    Column(modifier) {
        TopBar("Search")
        PillField(query, { query = it }, "Songs, artists, albums or years", Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        if (query.isBlank()) {
            if (recent.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ recent.clear() }) { Text("Clear") }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(recent, key = { it }) { term ->
                        AssistChip(onClick = { query = term }, label = { Text(term, maxLines = 1) })
                    }
                }
            }
            GenreGrid(songs, bottomPadding, onOpen, Modifier.fillMaxSize())
        } else SearchResults(results, query, currentId, actions, onOpen, bottomPadding, Modifier.fillMaxSize())
    }
}

/** Results in three groups: songs, then albums and artists found among them. */
@Composable
private fun SearchResults(
    results: List<SongUi>, query: String, currentId: String?, actions: ListActions, onOpen: (Detail) -> Unit,
    bottomPadding: Dp, modifier: Modifier = Modifier,
) {
    val rowActions = remember(actions) { actions.rowActions() }
    val albums = remember(results) { results.filter { it.album.isNotBlank() }.distinctBy { it.album to it.artist }.take(6) }
    val artists = remember(results) { results.filter { it.artist.isNotBlank() }.distinctBy { it.artist }.take(6) }
    if (results.isEmpty()) {
        Text("No songs match \"${query.trim()}\".", modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
        item(key = "h-songs") { SearchHeader("Songs", results.size) }
        itemsIndexed(results, key = { _, s -> "s-${s.id}" }) { i, song ->
            SongRow(
                song = song, onClick = { actions.play(results, i) }, onLongClick = {},
                current = song.id == currentId, actions = rowActions,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            )
        }
        if (albums.isNotEmpty()) {
            item(key = "h-albums") { SearchHeader("Albums", albums.size) }
            items(albums, key = { "a-${it.album}-${it.artist}" }) { s ->
                SearchGroupRow(s.album, s.artist, s.coverUri, s.id, circle = false) { onOpen(Detail.Album(s.album, s.artist.ifBlank { null })) }
            }
        }
        if (artists.isNotEmpty()) {
            item(key = "h-artists") { SearchHeader("Artists", artists.size) }
            items(artists, key = { "r-${it.artist}" }) { s ->
                SearchGroupRow(s.artist, "", s.coverUri, "artist:${s.artist}", circle = true) { onOpen(Detail.Artist(s.artist)) }
            }
        }
    }
}

@Composable
private fun SearchHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchGroupRow(title: String, subtitle: String, cover: String?, key: String, circle: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(cover, key, size = 44.dp, radius = if (circle) 22.dp else 10.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val previewScreenActions = ListActions(play = { _, _ -> }, shuffle = {}, setFavorite = { _, _ -> }, addToPlaylist = {}, notice = {})

@Preview
@Composable
private fun CredentialsFormPreview() {
    CredentialsForm(onSave = { _, _, _ -> null }, onDone = {})
}

@Preview
@Composable
private fun GroupListPreview() {
    GroupList(
        groups = listOf(GroupUi(id = "1", name = "Rock Hits", subtitle = "24 songs")),
        bottomPadding = 0.dp,
        empty = "No groups yet.",
        onClick = {},
    )
}

@Preview
@Composable
private fun LibraryScreenPreview() {
    LibraryScreen(
        lib = LibraryUi(songs = Load.Ready(emptyList())),
        favorites = emptyList(),
        currentId = null,
        actions = previewScreenActions,
        onOpen = {},
        onNewPlaylist = {},
        bottomPadding = 0.dp,
    )
}


@Preview(name = "Group grid", showBackground = true)
@Composable
private fun GroupGridPreview() = io.max.paperplay.ui.PreviewFrame {
    GroupGrid(
        groups = listOf(GroupUi("1", "Black Holes and Revelations", "Muse"), GroupUi("2", "A Night at the Opera", "Queen")),
        bottomPadding = 0.dp, empty = "Nothing here", onClick = {},
    )
}

@Preview(name = "Group grid empty", showBackground = true)
@Composable
private fun GroupGridEmptyPreview() = io.max.paperplay.ui.PreviewFrame {
    GroupGrid(groups = emptyList(), bottomPadding = 0.dp, empty = "Nothing here", onClick = {})
}

@Preview(name = "Detail hero", showBackground = true)
@Composable
private fun DetailHeroPreview() = io.max.paperplay.ui.PreviewFrame {
    DetailHero(Detail.Album("Black Holes and Revelations"), "Black Holes and Revelations", io.max.paperplay.ui.previewSongs)
}

@Preview(name = "Search phone", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun SearchScreenPreview() = io.max.paperplay.ui.PreviewFrame {
    SearchScreen(
        search = { q -> flowOf(io.max.paperplay.ui.previewSongs.filter { it.title.contains(q, true) || it.artist.contains(q, true) }) },
        songs = io.max.paperplay.ui.previewSongs, currentId = "1", actions = io.max.paperplay.ui.previewActions(),
        onOpen = {}, bottomPadding = 0.dp,
    )
}
