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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.state.GroupUi
import io.max.paperplay.ui.state.LibraryUi
import io.max.paperplay.ui.state.Load
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.state.SongUi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.snapshotFlow

/** Where a tap on an album, artist or playlist goes. */
sealed interface Detail {
    val title: String

    data class Album(val name: String) : Detail { override val title get() = name }
    data class Artist(val name: String) : Detail { override val title get() = name }
    data class Playlist(val id: String, val name: String) : Detail { override val title get() = name }
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
fun SetupScreen(backend: CoreBackend, onSaved: () -> Unit, onRestore: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Paper Play", style = MaterialTheme.typography.displaySmall)
        Text(
            "Connect your own Telegram bot. The API id and hash come from my.telegram.org, the token from @BotFather. " +
                "Everything stays on this device.",
            Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CredentialsForm(backend::saveCredentials, onSaved)
        OutlinedButton(onRestore, Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Restore everything from a backup") }
    }
}

// ---- Home ----------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(
    lib: LibraryUi, favorites: List<SongUi>, recents: List<SongUi>, playback: PlaybackUiState,
    conn: ConnectionUi, actions: ListActions, onOpen: (Detail) -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier,
) {
    val songs = (lib.songs as? Load.Ready)?.value.orEmpty()
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = bottomPadding)) {
        item {
            val h = io.max.paperplay.ui.currentHour()
            TopBar(if (h < 12) "Good morning" else if (h < 18) "Good afternoon" else "Good evening")
        }
        item { StatusLine(conn, songs.size) }
        val tiles = buildList<Pair<GroupUi, () -> Unit>> {
            if (favorites.isNotEmpty()) add(GroupUi("fav", "Favorites", coverUris = favorites.map { it.coverUri }) to { actions.play(favorites, 0) })
            lib.playlists.take(6 - size).forEach { g -> add(g to { onOpen(Detail.Playlist(g.id, g.name)) }) }
        }
        if (tiles.isNotEmpty()) item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (g, click) ->
                            Row(
                                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = click),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CoverGrid(g.coverUris, g.id, 56.dp, 0.dp)
                                Text(g.name, Modifier.padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (recents.isNotEmpty()) item { Shelf("Recently played", recents, playback.current?.id, actions) }
        if (favorites.isNotEmpty()) item { Shelf("Favorites", favorites, playback.current?.id, actions) }
        val added = songs.sortedByDescending { it.addedAt }.take(20)
        if (added.isNotEmpty()) item { Shelf("Recently added", added, playback.current?.id, actions) }
        if (lib.playlists.isNotEmpty()) item {
            Column {
                Section("Your playlists")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(lib.playlists, key = { it.id }) { g ->
                        Column(Modifier.width(150.dp).clickable { onOpen(Detail.Playlist(g.id, g.name)) }) {
                            CoverGrid(g.coverUris, g.id, 150.dp, 16.dp)
                            Text(g.name, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(conn: ConnectionUi, songCount: Int) {
    val text = when {
        conn.error != null -> conn.error
        !conn.ready && conn.state.isNotEmpty() -> "Connecting to Telegram…"
        conn.scanText != null -> conn.scanText
        songCount == 0 && conn.ready -> "No songs yet. Add the chat that holds your music in Settings."
        else -> null
    } ?: return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text, color = if (conn.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (conn.scanText != null && conn.error == null) {
            val f = conn.scanFraction
            if (f != null) LinearProgressIndicator({ f.toFloat() }, Modifier.fillMaxWidth().padding(top = 6.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

@Composable
private fun Shelf(title: String, songs: List<SongUi>, currentId: String?, actions: ListActions) {
    Column {
        Section(title)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(songs.size) { i ->
                val s = songs[i]
                Column(Modifier.width(120.dp).clickable { actions.play(songs, i) }) {
                    CoverImage(s.coverUri, s.id, size = 120.dp, radius = 12.dp)
                    Text(
                        s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                        color = if (s.id == currentId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
) {
    var tab by remember { mutableStateOf(0) }
    Column(modifier) {
        TopBar("Your library")
        ChipRow(libraryTabs, tab, { tab = it })
        when (tab) {
            0 -> when (val s = lib.songs) {
                is Load.Loading -> Text("Loading…", Modifier.padding(24.dp))
                is Load.Error -> Text(s.message, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                is Load.Ready -> SongList(s.value, currentId, actions, Modifier.fillMaxSize(), bottomPadding, empty = "No songs yet. Add a chat in Settings.")
            }
            1 -> GroupList(lib.albums, bottomPadding, "No albums yet.") { onOpen(Detail.Album(it.name)) }
            2 -> GroupList(lib.artists, bottomPadding, "No artists yet.") { onOpen(Detail.Artist(it.name)) }
            3 -> GroupList(
                lib.playlists, bottomPadding, "No playlists yet.",
                header = { TextButton(onNewPlaylist, Modifier.padding(horizontal = 8.dp)) { Text("New playlist") } },
            ) { onOpen(Detail.Playlist(it.id, it.name)) }
            else -> SongList(favorites, currentId, actions, Modifier.fillMaxSize(), bottomPadding, empty = "No favorites yet.")
        }
    }
}

@Composable
private fun GroupList(groups: List<GroupUi>, bottomPadding: Dp, empty: String, header: (@Composable () -> Unit)? = null, onClick: (GroupUi) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        if (header != null) item { header() }
        if (groups.isEmpty()) item { Text(empty, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(groups, key = { it.id }) { g ->
            ClickableRow({ onClick(g) }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                CoverImage(g.coverUris.firstOrNull(), g.id, size = 56.dp, radius = 10.dp)
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
            is Detail.Album -> backend.album(detail.name)
            is Detail.Artist -> backend.artist(detail.name)
            is Detail.Playlist -> backend.playlist(detail.id.toLongOrNull() ?: -1)
        }
    }
    val songs by flow.collectAsState(emptyList())
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("Back") }
            Text(detail.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
            if (detail is Detail.Playlist) TextButton({ backend.deletePlaylist(detail.id); onBack() }) { Text("Delete") }
        }
        SongList(songs, currentId, actions, Modifier.fillMaxSize(), bottomPadding, sortable = detail !is Detail.Playlist)
    }
}

// ---- Search --------------------------------------------------------------------------------------------

@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(backend: CoreBackend, currentId: String?, actions: ListActions, bottomPadding: Dp, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SongUi>>(emptyList()) }
    LaunchedEffect(Unit) {
        snapshotFlow { query }.debounce(150).collect { q ->
            if (q.isBlank()) results = emptyList()
            else backend.search(q).collect { results = it }
        }
    }
    Column(modifier) {
        TopBar("Search")
        PillField(query, { query = it }, "Songs, artists or albums", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        SongList(
            results, currentId, actions, Modifier.fillMaxSize(), bottomPadding, sortable = false, searchable = false,
            empty = if (query.isBlank()) "Type to search your library." else "No songs match \"${query.trim()}\".",
        )
    }
}

// ---- Settings ------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(conn: ConnectionUi, songCount: Int, backend: CoreBackend, bottomPadding: Dp, modifier: Modifier = Modifier) {
    var chatId by remember { mutableStateOf("") }
    var chatTitle by remember { mutableStateOf("") }
    var chatError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = bottomPadding)) {
        TopBar("Settings")
        Section("Telegram")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    conn.ready -> "Connected as your bot."
                    conn.state.isEmpty() -> "Not connected."
                    else -> "Connecting… (${conn.state.removePrefix("authorizationState")})"
                },
            )
            if (conn.error != null) Text(conn.error, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(backend::connect) { Text("Reconnect") }
                OutlinedButton({ editing = !editing }) { Text(if (editing) "Cancel" else "Change credentials") }
            }
            if (editing) CredentialsForm(backend::saveCredentials, { editing = false }, Modifier.padding(top = 8.dp))
        }
        Section("Library")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$songCount songs")
            conn.chats.forEach { Text("${it.title}  (${it.id})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (conn.scanText != null) Text(conn.scanText)
            OutlinedTextField(chatId, { chatId = it }, Modifier.fillMaxWidth(), label = { Text("Chat id, like -1001234567890") }, singleLine = true)
            OutlinedTextField(chatTitle, { chatTitle = it }, Modifier.fillMaxWidth(), label = { Text("Name (optional)") }, singleLine = true)
            if (chatError != null) Text(chatError!!, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    chatError = backend.addChat(chatId, chatTitle)
                    if (chatError == null) { chatId = ""; chatTitle = "" }
                }, enabled = conn.ready) { Text("Add chat") }
                OutlinedButton(backend::rescan, enabled = conn.ready && conn.chats.isNotEmpty()) { Text("Scan again") }
            }
            if (!conn.ready) Text("Connect first to add a chat.", style = MaterialTheme.typography.bodySmall)
        }
        Section("About")
        Text("Paper Play for Compose, early build.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
