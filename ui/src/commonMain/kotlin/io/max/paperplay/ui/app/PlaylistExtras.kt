package io.max.paperplay.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Link
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.account.ChatPost
import io.max.paperplay.core.integrations.ExportList
import io.max.paperplay.core.integrations.ExportRow
import io.max.paperplay.core.integrations.ImportStore
import io.max.paperplay.core.integrations.acceptMatch
import io.max.paperplay.core.integrations.exportCsv
import io.max.paperplay.core.integrations.importPlaylist
import io.max.paperplay.core.integrations.reconcileImports
import io.max.paperplay.core.integrations.spotifyLinkOf
import io.max.paperplay.core.library.MissingTrack
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.state.GroupUi
import io.max.paperplay.ui.state.SongUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** How a playlist page orders its songs. */
internal enum class PlaylistSort(val label: String) {
    Order("Playlist order"), Newest("Date added, newest"), Oldest("Date added, oldest"), Title("Title"), Artist("Artist");

    fun apply(songs: List<SongUi>): List<SongUi> = when (this) {
        Order -> songs
        Newest -> songs.sortedByDescending { it.addedAt }
        Oldest -> songs.sortedBy { it.addedAt }
        Title -> songs.sortedBy { it.title.lowercase() }
        Artist -> songs.sortedBy { it.artist.lowercase() }
    }
}

internal fun CoreBackend.importStore() = ImportStore(services.library.repo, services.driver)

/** The Spotify link a playlist was imported from, or null for a playlist made here. */
internal fun CoreBackend.playlistSource(playlistId: String): String? {
    val id = playlistId.toLongOrNull() ?: return null
    return importStore().livePlaylists().firstOrNull { it.id == id }?.source_url?.takeIf { it.isNotBlank() }
}

/** Reads the playlist's Spotify link again and adds what is new. Returns a message for the user. */
internal suspend fun CoreBackend.refreshPlaylist(playlistId: String): String {
    val link = playlistSource(playlistId) ?: return "This playlist was not imported from a link."
    return try {
        val r = withContext(Dispatchers.Default) { importPlaylist(importStore(), link, http) }
        "${r.name}: ${r.matched} in your library, ${r.missing} not yet."
    } catch (e: Exception) {
        e.message ?: "The refresh failed."
    }
}

/** The "N not in your library yet" row on a playlist page. */
@Composable
internal fun MissingRow(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(Icons.Rounded.MusicOff, null, Modifier.padding(end = 16.dp))
        Text("$count not in your library yet", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        AppIcon(Icons.Rounded.ChevronRight, null)
    }
}

/** Bottom sheet of the tracks a playlist still waits for, each with the best song the chat index has. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MissingSheet(backend: CoreBackend, store: ImportStore, missing: List<MissingTrack>, listName: String, onDismiss: () -> Unit, onChanged: () -> Unit) {
    var exportText by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${missing.size} not in your library", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton({
                val rows = missing.map { ExportRow(it.title, it.artist, it.duration_sec.toInt(), spotifyLinkOf(it.track_key)) }
                exportText = exportCsv(ExportList("missing", listName, 0L, rows))
            }) { AppIcon(Icons.Rounded.IosShare, "Export the missing list") }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(missing, key = { it.track_key }) { t -> MissingTrackRow(backend, store, t, onChanged) }
        }
    }
    exportText?.let { text ->
        AlertDialog(
            onDismissRequest = { exportText = null },
            title = { Text("Missing songs") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Select and copy. Columns: title, artist, seconds, Spotify link, Apple Music link.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton({ exportText = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun MissingTrackRow(backend: CoreBackend, store: ImportStore, t: MissingTrack, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var post by remember(t.track_key) { mutableStateOf<ChatPost?>(null) }
    var state by remember(t.track_key) { mutableStateOf<String?>(null) }
    LaunchedEffect(t.track_key) {
        post = withContext(Dispatchers.Default) { runCatching { backend.chatIndex.matchesFor(t.title, t.artist, t.duration_sec.toInt()).firstOrNull() }.getOrNull() }
    }
    val p = post
    val wanted = t.duration_sec.toInt()
    val sure = p != null && p.durationSec > 0 && wanted > 0 && abs(p.durationSec - wanted) <= 8
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(if (p == null) Icons.Rounded.MusicOff else Icons.AutoMirrored.Rounded.HelpOutline, null, Modifier.padding(end = 16.dp).size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p != null) {
                val what = "${p.title} · ${p.artist}"
                Text(
                    state ?: if (sure) "In your chats: $what" else "Maybe: $what (${p.durationSec}s, wanted ${wanted}s)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (p != null && state == null) TextButton({
            state = "Adding"
            scope.launch {
                val ok = withContext(Dispatchers.Default) {
                    runCatching {
                        backend.chatIndex.addToLibrary(p)
                        val song = store.songs().firstOrNull { it.chat_id == p.chatId && it.message_id == p.messageId }
                        if (song != null) acceptMatch(store, t, song) else reconcileImports(store)
                    }.isSuccess
                }
                state = if (ok) "Added" else null
                if (ok) onChanged()
            }
        }) { Text("Add") }
    }
}

// ---- Library, Playlists tab ----------------------------------------------------------------------------

@Composable
private fun PlaylistActionRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = appShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(44.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(contentAlignment = Alignment.Center) { AppIcon(icon, null, Modifier.size(22.dp)) }
            }
            Text(title, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Favorites, Recently played, New playlist, Import from Spotify link, then the user's playlists. */
@Composable
fun PlaylistsTab(
    playlists: List<GroupUi>, favorites: List<SongUi>, recents: List<SongUi>, bottomPadding: Dp,
    onOpen: (Detail) -> Unit, onNewPlaylist: () -> Unit, onImportSpotify: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PlaylistActionRow(Icons.Rounded.Favorite, "Favorites") { onOpen(Detail.Songs("Favorites", favorites)) } }
        item { PlaylistActionRow(Icons.Rounded.History, "Recently played") { onOpen(Detail.Songs("Recently played", recents)) } }
        item { PlaylistActionRow(Icons.Rounded.Add, "New playlist", onNewPlaylist) }
        item { PlaylistActionRow(Icons.Rounded.Link, "Import from Spotify link", onImportSpotify) }
        item { Text("Your playlists", Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium) }
        if (playlists.isEmpty()) item { Text("No playlists yet.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(playlists, key = { it.id }) { g ->
            Surface(onClick = { onOpen(Detail.Playlist(g.id, g.name)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = appShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    io.max.paperplay.ui.components.CoverImage(g.coverUris.firstOrNull(), g.id, size = 52.dp, radius = 10.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(g.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun PlaylistsTabPreview() {
    val samplePlaylists = listOf(GroupUi(id = "1", name = "Chill Mix", subtitle = "12 songs"))
    PlaylistsTab(
        playlists = samplePlaylists,
        favorites = emptyList(),
        recents = emptyList(),
        bottomPadding = 0.dp,
        onOpen = {},
        onNewPlaylist = {},
        onImportSpotify = {},
    )
}
