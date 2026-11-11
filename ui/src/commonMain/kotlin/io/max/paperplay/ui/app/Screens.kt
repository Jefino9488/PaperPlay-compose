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
import androidx.compose.material3.AssistChip
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.snapshotFlow

/** Where a tap on an album, artist or playlist goes. */
sealed interface Detail {
    val title: String

    data class Album(val name: String) : Detail { override val title get() = name }
    data class Artist(val name: String) : Detail { override val title get() = name }
    data class Playlist(val id: String, val name: String) : Detail { override val title get() = name }
    /** A fixed list: a mix, a generated tile or a genre. */
    data class Songs(override val title: String, val songs: List<SongUi>) : Detail
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
        Text("PaperPlay", style = MaterialTheme.typography.displaySmall)
        Text(
            "Connect your own Telegram bot. The API id and hash come from my.telegram.org, the token from @BotFather. " +
                "Everything stays on this device.",
            Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CredentialsForm(backend::saveCredentials, onSaved)
        OutlinedButton(onRestore, Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Restore everything from a backup") }
    }
}

// ---- Library -------------------------------------------------------------------------------------------

private val libraryTabs = listOf("Songs", "Albums", "Artists", "Playlists", "Favorites")

@Composable
fun LibraryScreen(
    lib: LibraryUi, favorites: List<SongUi>, currentId: String?, actions: ListActions,
    onOpen: (Detail) -> Unit, onNewPlaylist: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier,
    selectedTab: Int? = null,
) {
    var tab by remember(selectedTab) { mutableStateOf(selectedTab ?: 0) }
    Column(modifier) {
        TopBar("Your library")
        ChipRow(libraryTabs, tab, { tab = it })
        when (tab) {
            0 -> when (val s = lib.songs) {
                is Load.Loading -> Text("Loadingâ€¦", Modifier.padding(24.dp))
                is Load.Error -> Text(s.message, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                is Load.Ready -> SongList(s.value, currentId, actions, Modifier.fillMaxSize(), bottomPadding, empty = "No songs yet. Add a chat in Settings.")
            }
            1 -> GroupGrid(lib.albums, bottomPadding, "No albums yet.") { onOpen(Detail.Album(it.name)) }
            2 -> GroupList(lib.artists, bottomPadding, "No artists yet.", circle = true) { onOpen(Detail.Artist(it.name)) }
            3 -> GroupList(
                lib.playlists, bottomPadding, "No playlists yet.",
                header = { TextButton(onNewPlaylist, Modifier.padding(horizontal = 8.dp)) { Text("New playlist") } },
            ) { onOpen(Detail.Playlist(it.id, it.name)) }
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
            is Detail.Album -> backend.album(detail.name)
            is Detail.Artist -> backend.artist(detail.name)
            is Detail.Playlist -> backend.playlist(detail.id.toLongOrNull() ?: -1)
            is Detail.Songs -> flowOf(detail.songs)
        }
    }
    val songs by flow.collectAsState(emptyList())
    Column(modifier) {
        BackTopBar(detail.title, onBack) {
            if (detail is Detail.Playlist) TextButton({ backend.deletePlaylist(detail.id); onBack() }) { Text("Delete") }
        }
        SongList(songs, currentId, actions, Modifier.fillMaxSize(), bottomPadding, sortable = detail !is Detail.Playlist)
    }
}

// ---- Search --------------------------------------------------------------------------------------------

@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(
    backend: CoreBackend, songs: List<SongUi>, currentId: String?, actions: ListActions, onOpen: (Detail) -> Unit,
    bottomPadding: Dp, modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SongUi>>(emptyList()) }
    val recent = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        snapshotFlow { query }.debounce(150).collect { q ->
            if (q.isBlank()) results = emptyList()
            else backend.search(q).collect {
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
        PillField(query, { query = it }, "Songs, artists or albums", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (query.isBlank()) {
            if (recent.isNotEmpty()) {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(recent, key = { it }) { term ->
                        AssistChip(onClick = { query = term }, label = { Text(term, maxLines = 1) })
                    }
                }
            }
            GenreGrid(songs, bottomPadding, onOpen, Modifier.fillMaxSize())
        }
        else SongList(
            results, currentId, actions, Modifier.fillMaxSize(), bottomPadding, sortable = false, searchable = false,
            empty = "No songs match \"${query.trim()}\".",
        )
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

