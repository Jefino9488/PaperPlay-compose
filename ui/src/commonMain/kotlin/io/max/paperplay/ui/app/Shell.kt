package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.max.paperplay.ui.GlassSurface
import io.max.paperplay.ui.PlatformBackHandler
import io.max.paperplay.ui.components.LocalCoverLoader
import io.max.paperplay.ui.state.Load
import kotlinx.coroutines.launch

private val tabs = listOf("Home", "Search", "Library", "Settings")

@Composable
fun MainShell(backend: CoreBackend) {
    val lib by backend.library.collectAsState()
    val favorites by backend.favorites.collectAsState()
    val recents by backend.recents.collectAsState()
    val pb by backend.playback.collectAsState()
    val pbError by backend.playbackError.collectAsState()
    val conn by backend.connection.collectAsState()

    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<Set<String>?>(null) }
    var newPlaylist by remember { mutableStateOf(false) }
    // Songs waiting for a playlist that is about to be created.
    var pendingAdd by remember { mutableStateOf<Set<String>?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val loader = remember { FileCoverLoader() }
    val backdrop = rememberLayerBackdrop()

    val actions = remember(backend) {
        ListActions(
            play = backend::play,
            shuffle = backend::shuffleAll,
            setFavorite = { s, f -> backend.setFavorite(s.id, f) },
            addToPlaylist = { pickFor = it },
            notice = { scope.launch { snack.showSnackbar(it) } },
        )
    }

    PlatformBackHandler(showPlayer) { showPlayer = false }
    PlatformBackHandler(!showPlayer && detail != null) { detail = null }
    PlatformBackHandler(!showPlayer && detail == null && tab != 0) { tab = 0 }

    val songs = (lib.songs as? Load.Ready)?.value.orEmpty()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barsHeight = (if (pb.current != null) 68.dp + 8.dp else 0.dp) + 64.dp + 24.dp + bottomInset
    val currentId = pb.current?.id

    CompositionLocalProvider(LocalCoverLoader provides loader) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).padding(top = top)) {
                val d = detail
                when {
                    d != null -> DetailScreen(d, backend, currentId, actions, { detail = null }, barsHeight)
                    tab == 0 -> HomeScreen(lib, favorites, recents, pb, conn, actions, barsHeight)
                    tab == 1 -> SearchScreen(backend, currentId, actions, barsHeight)
                    tab == 2 -> LibraryScreen(lib, favorites, currentId, actions, { detail = it }, { newPlaylist = true }, barsHeight)
                    else -> SettingsScreen(conn, songs.size, backend, barsHeight)
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp + bottomInset),
            ) {
                if (pb.current != null) {
                    MiniPlayer(pb, backdrop, { showPlayer = true }, backend::togglePlay, backend::next)
                    Box(Modifier.height(8.dp))
                }
                GlassSurface(backdrop, Modifier.fillMaxWidth().height(64.dp), cornerRadius = 32.dp) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        tabs.forEachIndexed { i, label ->
                            Box(
                                Modifier.weight(1f).fillMaxSize().clickable { tab = i; detail = null },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    color = if (tab == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
            if (showPlayer) FullPlayer(pb, pbError, backend, { showPlayer = false }, Modifier.padding(top = top))
            SnackbarHost(snack, Modifier.align(Alignment.TopCenter).padding(top = top))
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
        confirmButton = { TextButton({ onDone(name) }) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
    )
}
