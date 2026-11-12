package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.integrations.ExportList
import io.max.paperplay.core.integrations.ExportRow
import io.max.paperplay.core.integrations.exportCsv
import io.max.paperplay.core.library.EnrichState
import io.max.paperplay.core.library.Song
import io.max.paperplay.ui.components.AppIcon

/** Something a song still lacks. [todo] ones are work not done yet; the rest were looked for and not found. */
internal enum class Missing(val label: String, val todo: Boolean = false) {
    Waiting("Waiting for song info", true),
    ScanPending("Lyrics and genre not looked up yet", true),
    NoCover("No cover yet", true),
    NoMatch("No match found"),
    SmallCover("Small cover only"),
    NoAlbum("No album"),
    NoYear("No year"),
    NoGenre("No genre"),
    UnknownArtist("Unknown artist"),
}

/** What [s] still lacks. With [lookupsOn] off the lyrics and genre scan never runs, so it is not counted. */
internal fun missingOf(s: Song, lookupsOn: Boolean): Set<Missing> {
    val looked = s.enrich_state != EnrichState.PENDING
    val scanned = !lookupsOn || s.mood_scanned_at > 0
    return buildSet {
        if (!looked) add(Missing.Waiting)
        if (lookupsOn && !scanned) add(Missing.ScanPending)
        if (s.cover_path == null) add(Missing.NoCover) else if (!s.cover_hi_res && s.cover_at == 0L) add(Missing.SmallCover)
        if (s.enrich_state == EnrichState.NO_MATCH) add(Missing.NoMatch)
        if (looked && s.album.isEmpty()) add(Missing.NoAlbum)
        if (looked && s.year == null) add(Missing.NoYear)
        if (looked && scanned && s.genre == null) add(Missing.NoGenre)
        if (s.artist == "Unknown artist") add(Missing.UnknownArtist)
    }
}

/** Every song that still lacks something, what, a way to look them up again, and a CSV of the list. */
@Composable
internal fun MissingInfoPage(backend: CoreBackend) {
    val songs by backend.services.library.allSongs().collectAsState(emptyList())
    val taste by backend.taste.collectAsState()
    val running by backend.services.enricher.running.collectAsState()
    val clipboard = LocalClipboardManager.current
    var only by remember { mutableStateOf<Missing?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    val rows = songs.map { it to missingOf(it, taste.lookups) }.filter { it.second.isNotEmpty() }
    val counts = Missing.entries.associateWith { m -> rows.count { m in it.second } }
    val shown = rows.filter { only == null || only in it.second }
        .sortedWith(compareByDescending<Pair<Song, Set<Missing>>> { it.second.size }.thenBy { it.first.title })

    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (rows.isEmpty()) "All ${songs.size} songs are complete" else "${songs.size - rows.size} of ${songs.size} songs are complete",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (running) "Looking up song info now" else "No song info lookup running",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (m in Missing.entries) if (counts.getValue(m) > 0) {
                    FilterChip(selected = only == m, onClick = { only = if (only == m) null else m }, label = { Text("${m.label} (${counts.getValue(m)})") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        val n = backend.services.enricher.retrySongs(shown.map { it.first })
                        note = if (n == 0) "Nothing to look up." else "Looking up $n songs again, info and covers."
                    }) {
                        AppIcon(Icons.Rounded.Refresh, null, Modifier.padding(end = 8.dp))
                        Text("Look up again")
                    }
                    OutlinedButton(enabled = shown.isNotEmpty(), onClick = {
                        val list = ExportList("missing", "Songs missing info", 0, shown.map { (s, _) -> ExportRow(s.title, s.artist, s.duration_sec.toInt()) })
                        clipboard.setText(AnnotatedString(exportCsv(list)))
                        note = "Copied ${shown.size} songs as CSV."
                    }) { Text("Copy list as CSV") }
                }
                Text(
                    note ?: "Redoes only the songs shown by the chosen filter. Songs you edited keep their info.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(shown, key = { it.first.unique_id }) { (s, missing) ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(s.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(
                    missing.joinToString(" · ") { it.label }, style = MaterialTheme.typography.labelSmall,
                    color = if (missing.any { it.todo }) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
