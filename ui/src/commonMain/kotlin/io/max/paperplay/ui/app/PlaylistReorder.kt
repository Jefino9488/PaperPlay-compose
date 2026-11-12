package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.state.SongUi

/** Top bar button for a playlist: a list with move up and move down per song. Each move is saved at once. */
@Composable
internal fun ReorderButton(backend: CoreBackend, playlistId: String, songs: List<SongUi>, external: Boolean = false, onClosed: () -> Unit = {}) {
    var open by remember { mutableStateOf(external) }
    if (!external) IconButton({ open = true }, enabled = songs.size > 1) { AppIcon(Icons.Rounded.SwapVert, "Reorder songs") }
    if (!open) return
    // Local copy so the list moves at once; the database catches up behind it.
    var order by remember { mutableStateOf(songs) }
    fun move(from: Int, to: Int) {
        if (to !in order.indices) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        backend.setPlaylistOrder(playlistId, order.map { it.id })
    }
    AlertDialog(
        onDismissRequest = { open = false; onClosed() },
        title = { Text("Reorder songs") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(order, key = { _, s -> s.id }) { i, s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 4.dp)) {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({ move(i, i - 1) }, enabled = i > 0) { AppIcon(Icons.Rounded.ExpandLess, "Move up") }
                        IconButton({ move(i, i + 1) }, enabled = i < order.lastIndex) { AppIcon(Icons.Rounded.ExpandMore, "Move down") }
                    }
                }
            }
        },
        confirmButton = { TextButton({ open = false; onClosed() }) { Text("Done") } },
    )
}
