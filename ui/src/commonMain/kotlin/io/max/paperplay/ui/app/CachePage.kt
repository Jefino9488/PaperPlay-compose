package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.ui.tooling.preview.Preview

internal data class CacheRow(val id: String, val title: String, val artist: String, val bytes: Long, val pinned: Boolean)

private fun sizeText(b: Long) = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

/** Lists the songs stored on this device, pinned ones first, with unpin and clear. */
@Composable
internal fun CachePage(backend: CoreBackend, notice: androidx.compose.runtime.MutableState<String?>) {
    var tick by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val rows by produceState(emptyList<CacheRow>(), tick) {
        value = withContext(Dispatchers.IO) {
            backend.services.library.cachedSongs()
                .map { CacheRow(it.unique_id, it.title, it.artist ?: "", it.cached_bytes, it.pinned) }
                .sortedWith(compareByDescending<CacheRow> { it.pinned }.thenByDescending { it.bytes })
        }
    }
    if (confirm) ConfirmDialog(
        "Clear the cache?", "Streamed songs stored on this device are deleted. Pinned songs stay. Stop playback first.", "Clear",
        { confirm = false; backend.clearCache { notice.value = it; tick++ } }, { confirm = false },
    )
    CacheContent(rows, onUnpin = { backend.setPinned(it, false); tick++ }, onClear = { confirm = true })
}

@Composable
internal fun CacheContent(rows: List<CacheRow>, onUnpin: (String) -> Unit, onClear: () -> Unit) {
    val pinned = rows.filter { it.pinned }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${rows.size} songs, ${sizeText(rows.sumOf { it.bytes })}. Pinned: ${pinned.size}, ${sizeText(pinned.sumOf { it.bytes })}.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClear, Modifier.fillMaxWidth(), enabled = rows.any { !it.pinned }) { Text("Clear unpinned songs") }
        if (rows.isEmpty()) Text("No songs are stored on this device.")
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(rows, key = { it.id }) { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(r.artist, sizeText(r.bytes), if (r.pinned) "Pinned" else "").filter { it.isNotEmpty() }.joinToString("  "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    if (r.pinned) TextButton({ onUnpin(r.id) }) { Text("Unpin") }
                }
            }
        }
    }
}

@Preview(name = "Cache", widthDp = 390, heightDp = 700, showBackground = true)
@Composable
private fun CachePreview() = io.max.paperplay.ui.PreviewFrame {
    CacheContent(
        listOf(CacheRow("1", "Song A", "Artist", 8_400_000, true), CacheRow("2", "Song B", "Artist", 5_100_000, false)), {}, {},
    )
}
