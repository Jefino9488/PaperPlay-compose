package io.max.paperplay.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.integrations.ExportList
import io.max.paperplay.core.integrations.deleteExportList
import io.max.paperplay.core.integrations.exportCsv
import io.max.paperplay.core.integrations.exportLinks
import io.max.paperplay.core.integrations.loadExportLists
import io.max.paperplay.ui.components.AppIcon

/** Saved export lists (songs not in the library, with Spotify and Apple Music links): open one, copy its links or CSV, delete it. */
@Composable
internal fun ExportListsPage(backend: CoreBackend) {
    val repo = backend.services.library.repo
    var bump by remember { mutableStateOf(0) }
    val lists = remember(bump) { loadExportLists(repo).sortedByDescending { it.createdAt } }
    var open by remember { mutableStateOf<String?>(null) }
    val chosen = lists.firstOrNull { it.id == open }
    if (chosen == null) {
        if (lists.isEmpty()) {
            Text(
                "No saved lists. A list of songs that are not in your library is saved when you export it from a playlist.",
                Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(lists, key = { it.id }) { l ->
                Row(Modifier.fillMaxWidth().clickable { open = l.id }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, style = MaterialTheme.typography.titleMedium)
                        Text("${l.rows.size} songs, ${l.withSpotify} with Spotify links, ${l.withApple} with Apple Music links", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AppIcon(Icons.Rounded.ChevronRight, null)
                }
            }
        }
    } else {
        ExportListDetail(chosen, onBack = { open = null }, onDelete = { deleteExportList(repo, chosen.id); open = null; bump++ })
    }
}

@Composable
private fun ExportListDetail(list: ExportList, onBack: () -> Unit, onDelete: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var note by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(list.name, style = MaterialTheme.typography.titleLarge)
                Text("${list.rows.size} songs", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ clipboard.setText(AnnotatedString(exportLinks(list, apple = false))); note = "Spotify links copied" }, Modifier.fillMaxWidth(), enabled = list.withSpotify > 0) { Text("Copy Spotify links") }
                OutlinedButton({ clipboard.setText(AnnotatedString(exportLinks(list, spotify = false))); note = "Apple Music links copied" }, Modifier.fillMaxWidth(), enabled = list.withApple > 0) { Text("Copy Apple Music links") }
                OutlinedButton({ clipboard.setText(AnnotatedString(exportCsv(list))); note = "CSV copied" }, Modifier.fillMaxWidth()) { Text("Copy as CSV") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onBack) { Text("All lists") }
                    TextButton(onDelete) { Text("Delete list") }
                }
                note?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        items(list.rows) { r ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(r.title, style = MaterialTheme.typography.bodyLarge)
                Text(r.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
