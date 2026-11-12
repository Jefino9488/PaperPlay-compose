package io.max.paperplay.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.state.Load

/** Top bar button for a playlist: opens a searchable list of library songs to add. */
@Composable
internal fun AddSongsButton(backend: CoreBackend, playlistId: String, existing: Set<String>, notice: (String) -> Unit, external: Boolean = false, onClosed: () -> Unit = {}) {
    var open by remember { mutableStateOf(external) }
    if (!external) IconButton({ open = true }) { AppIcon(Icons.Rounded.Add, "Add songs") }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val all by backend.library.collectAsState()
    val flow = remember(query) { if (query.isBlank()) kotlinx.coroutines.flow.flowOf(null) else backend.search(query) }
    val found by flow.collectAsState(null)
    val base = found ?: (all.songs as? Load.Ready)?.value.orEmpty()
    val list = remember(base, existing) { base.filter { it.id !in existing }.take(200) }
    if (open) AlertDialog(
        onDismissRequest = { open = false; onClosed() },
        title = { Text("Add songs") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search") })
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.id }) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = if (s.id in picked) picked - s.id else picked + s.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(s.id in picked, { picked = if (it) picked + s.id else picked - s.id })
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                backend.addToPlaylist(playlistId, picked)
                notice("Added ${picked.size} songs")
                open = false; onClosed()
            }, enabled = picked.isNotEmpty()) { Text("Add ${picked.size}") }
        },
        dismissButton = { TextButton({ open = false; onClosed() }) { Text("Cancel") } },
    )
}
