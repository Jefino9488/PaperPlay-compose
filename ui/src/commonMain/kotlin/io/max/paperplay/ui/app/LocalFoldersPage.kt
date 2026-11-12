package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.app.FolderScan

/**
 * Music folders on this device. Only the folders added here are read, and only the songs directly inside them:
 * a subfolder (call recordings, say) stays untouched unless it is added on its own. With "Send new songs to Telegram"
 * on, new songs go to the main chat through the upload queue, so every device sees them.
 */
@Composable
internal fun LocalFoldersPage(backend: CoreBackend) {
    val folders = backend.services.localFolders
    val env = io.max.paperplay.ui.LocalAppEnv.current
    var bump by remember { mutableStateOf(0) }
    var typed by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val scans: List<FolderScan> = remember(bump) { folders.scan() }
    val entries = remember(bump) { folders.list() }

    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Pick the folders that hold your music. Only the songs directly in a folder are used. Its subfolders are left alone " +
                "unless you add them here too, so call recordings and other audio in other places are never touched.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val pick = env?.pickFolderPath
        if (pick != null) Button({ pick { path -> if (folders.add(path)) bump++ else note = "That folder is already in the list." } }, Modifier.fillMaxWidth()) { Text("Add a folder") }
        OutlinedTextField(
            typed, { typed = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Folder path") }, placeholder = { Text("/storage/emulated/0/Music") },
        )
        OutlinedButton({
            if (folders.add(typed)) { typed = ""; bump++ } else note = "Enter a path that is not in the list yet."
        }, Modifier.fillMaxWidth(), enabled = typed.isNotBlank()) { Text("Add this path") }
        note?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (entries.isEmpty()) Text("No folders yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        entries.forEach { f ->
            val scan = scans.firstOrNull { it.path == f.path }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(f.path, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            scan == null -> ""
                            scan.songs.isEmpty() -> "No songs directly in this folder, or it cannot be read"
                            else -> "${scan.songs.size} songs, ${scan.fresh} not sent yet"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Send new songs to Telegram")
                            Text("Off: nothing is read or sent from this folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(f.sync, { folders.setSync(f.path, it); bump++ })
                    }
                    TextButton({ folders.remove(f.path); bump++ }) { Text("Remove folder") }
                }
            }
        }
        if (entries.any { it.sync }) {
            Button({
                val n = folders.syncNow()
                note = if (n < 0) "Choose a main chat first." else if (n == 0) "Nothing new to send." else "$n songs queued. See Tasks for progress."
                bump++
            }, Modifier.fillMaxWidth()) { Text("Send new songs now") }
        }
        Text(
            "New songs are also sent each time the app connects. They appear in your library once Telegram has them; songs already in " +
                "the library (same file name and size) are skipped. Folders are not played from directly.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
