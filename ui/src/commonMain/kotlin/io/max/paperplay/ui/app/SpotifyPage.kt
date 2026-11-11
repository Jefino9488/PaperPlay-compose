package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.integrations.ImportStore
import io.max.paperplay.core.integrations.acceptMatch
import io.max.paperplay.core.integrations.importPlaylist
import io.max.paperplay.core.integrations.reviewCandidates
import io.max.paperplay.core.library.MissingTrack
import io.max.paperplay.core.library.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SPOTIFY = "https://open.spotify.com"

/** Settings > Spotify import. Reads a public playlist link; no Spotify login. */
@Composable
internal fun SpotifyPage(backend: CoreBackend) {
    val store = remember { ImportStore(backend.services.library.repo, backend.services.driver) }
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var reviewing by remember { mutableStateOf<Long?>(null) }

    fun run(url: String) {
        busy = true
        message = null
        scope.launch {
            message = try {
                val r = withContext(Dispatchers.Default) { importPlaylist(store, url, backend.http) }
                val more = if (r.limited) " Spotify's public page lists only the first 100 tracks." else ""
                "${r.name}: ${r.matched} in your library, ${r.missing} not yet.$more"
            } catch (e: Exception) {
                e.message ?: "The import failed."
            }
            busy = false
            version++
        }
    }

    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Paste a public Spotify playlist link. Songs you already have join a new playlist. The rest wait there and join when they show up in your library.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Playlist link") }, singleLine = true)
        Button({ run(link.trim()) }, enabled = link.isNotBlank() && !busy) { Text(if (busy) "Importing" else "Import") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurface) }
    }

    @Suppress("UNUSED_EXPRESSION") version
    val imported = remember(version) { store.livePlaylists().filter { "${it.source_url}".startsWith(SPOTIFY) } }
    if (imported.isNotEmpty()) SectionLabel("Imported playlists")
    for (p in imported) {
        val waiting = remember(version) { store.missing(p.id) }
        SettingsRow(p.name, icon = Icons.Rounded.QueueMusic, subtitle = if (waiting.isEmpty()) "Everything is in your library" else "${waiting.size} not in your library yet",
            onClick = { reviewing = if (reviewing == p.id) null else p.id }) {
            OutlinedButton({ run("${p.source_url}") }, enabled = !busy) { Text("Refresh") }
        }
        if (reviewing == p.id) ReviewList(store, waiting) { version++ }
    }
}

@Composable
private fun ReviewList(store: ImportStore, waiting: List<MissingTrack>, onChange: () -> Unit) {
    val candidates = remember(waiting) { reviewCandidates(store, waiting) }
    val withMatches = waiting.filter { candidates[it.track_key].orEmpty().isNotEmpty() }
    if (withMatches.isEmpty()) {
        Text("No close matches in your library to review.", Modifier.padding(horizontal = 36.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    for (t in withMatches) {
        Column(Modifier.padding(horizontal = 36.dp, vertical = 4.dp)) {
            Text("${t.title} - ${t.artist}", style = MaterialTheme.typography.titleSmall)
            for (s in candidates[t.track_key].orEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${s.title} - ${s.artist}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ acceptMatch(store, t, s); onChange() }) { Text("This one") }
                }
            }
        }
    }
}
