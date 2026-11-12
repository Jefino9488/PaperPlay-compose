package io.max.paperplay.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.library.laterStamp
import io.max.paperplay.core.metadata.CoverOption
import io.max.paperplay.core.metadata.MetadataOption
import io.max.paperplay.ui.LocalAppEnv
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.state.SongUi
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Which song each dialog is open for; null means closed. */
class TagDialogState {
    var match by mutableStateOf<SongUi?>(null)
    var tags by mutableStateOf<SongUi?>(null)
    var cover by mutableStateOf<SongUi?>(null)
}

/** Hosts the Find metadata, Edit tags and Change cover dialogs. */
@Composable
fun TagDialogHost(backend: CoreBackend, state: TagDialogState, notice: (String) -> Unit) {
    state.match?.let { s ->
        val row = backend.songRow(s.id)
        if (row != null) MatchDialog(backend, row, notice) { state.match = null } else state.match = null
    }
    state.tags?.let { s ->
        val row = backend.songRow(s.id)
        if (row != null) TagsDialog(backend, row, notice) { state.tags = null } else state.tags = null
    }
    state.cover?.let { s ->
        val row = backend.songRow(s.id)
        if (row != null) CoverDialog(backend, row, notice) { state.cover = null } else state.cover = null
    }
}

private fun optionLine(o: MetadataOption): String {
    val length = o.lengthSec?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }
    return listOfNotNull(o.album.takeIf { it.isNotBlank() }, o.year?.toString(), length, o.source).joinToString("  -  ")
}

@Composable
private fun MatchDialog(backend: CoreBackend, song: Song, notice: (String) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var term by remember { mutableStateOf(listOfNotNull(song.title, song.artist.takeIf { it != "Unknown artist" }).joinToString(" ")) }
    var results by remember { mutableStateOf<List<MetadataOption>?>(null) }
    var busy by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    var searchKey by remember { mutableStateOf(0) }

    LaunchedEffect(searchKey) {
        busy = true
        results = runCatching { backend.services.enricher.searchOnline(term, song.duration_sec.toInt()) }.getOrDefault(emptyList())
        busy = false
    }

    AlertDialog(
        onDismissRequest = { if (!applying) onClose() },
        title = { Text("Find metadata") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(term, { term = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search") })
                TextButton({ searchKey++ }, enabled = term.isNotBlank() && !busy && !applying) { Text("Search") }
                val list = results.orEmpty()
                when {
                    busy -> CircularProgressIndicator(Modifier.size(24.dp))
                    list.isEmpty() -> Text("No results. Try different words.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(list) { o ->
                            Column(
                                Modifier.fillMaxWidth().clickable(enabled = !applying) {
                                    applying = true
                                    scope.launch {
                                        val ok = runCatching { backend.services.enricher.applyOption(song, o) }.getOrDefault(false)
                                        notice(if (ok) "Song info updated" else "Saved text; no cover found")
                                        onClose()
                                    }
                                }.padding(vertical = 8.dp),
                            ) {
                                Text(o.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    o.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    optionLine(o), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClose, enabled = !applying) { Text("Cancel") } },
    )
}

@Composable
private fun TagsDialog(backend: CoreBackend, song: Song, notice: (String) -> Unit, onClose: () -> Unit) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var album by remember { mutableStateOf(song.album) }
    var genre by remember { mutableStateOf(song.genre.orEmpty()) }
    var year by remember { mutableStateOf(song.year?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Title") })
                OutlinedTextField(artist, { artist = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Artist") })
                OutlinedTextField(album, { album = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Album") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(genre, { genre = it }, Modifier.weight(1f), singleLine = true, label = { Text("Genre") })
                    OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), singleLine = true, label = { Text("Year") })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    val repo = backend.services.library
                    val row = repo.songByUniqueId(song.unique_id)
                    if (row != null) {
                        repo.replaceSong(
                            row.copy(
                                title = title.trim(), artist = artist.trim().ifBlank { "Unknown artist" }, album = album.trim(),
                                genre = genre.trim().ifBlank { null }, year = year.toLongOrNull(),
                                user_edited = true, tags_at = laterStamp(Clock.System.now().toEpochMilliseconds(), row.tags_at),
                            ),
                        )
                        notice("Tags saved")
                    }
                    onClose()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

@Composable
private fun CoverDialog(backend: CoreBackend, song: Song, notice: (String) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pickImage = LocalAppEnv.current?.pickImage
    var term by remember {
        mutableStateOf(listOfNotNull(song.album.ifBlank { song.title }, song.artist.takeIf { it != "Unknown artist" }).joinToString(" "))
    }
    var options by remember { mutableStateOf<List<CoverOption>?>(null) }
    var busy by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var searchKey by remember { mutableStateOf(0) }

    LaunchedEffect(searchKey) {
        busy = true
        options = runCatching { backend.services.enricher.coverOptions(term) }.getOrDefault(emptyList())
        busy = false
    }

    fun finish(ok: Boolean) {
        notice(if (ok) "Cover changed" else "Could not set that cover")
        onClose()
    }

    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        title = { Text("Change cover") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pickImage != null) {
                    TextButton({
                        pickImage { bytes ->
                            if (bytes != null) {
                                backend.services.enricher.setCoverBytes(listOf(song), bytes)
                                finish(true)
                            }
                        }
                    }, enabled = !working) { Text("Choose from file") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true, label = { Text("Image link") })
                    TextButton({
                        working = true
                        scope.launch { finish(backend.services.enricher.setCoverFromUrl(listOf(song), url.trim())) }
                    }, enabled = url.isNotBlank() && !working) { Text("Use") }
                }
                OutlinedTextField(term, { term = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search online") })
                TextButton({ searchKey++ }, enabled = term.isNotBlank() && !busy && !working) { Text("Search") }
                val list = options.orEmpty()
                when {
                    busy -> CircularProgressIndicator(Modifier.size(24.dp))
                    list.isEmpty() -> Text("No covers found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(list) { o ->
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !working) {
                                    working = true
                                    scope.launch { finish(backend.services.enricher.setCoverFromUrl(listOf(song), o.url)) }
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CoverImage(o.url, o.url, size = 48.dp)
                                Text(o.label.ifBlank { "Cover" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClose, enabled = !working) { Text("Cancel") } },
    )
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OptionLinePhonePreview() {
    val sampleOption = MetadataOption(source = "Spotify", title = "Starlight", artist = "Muse", album = "Black Holes and Revelations", year = 2006, lengthSec = 240)
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) {
            Text(optionLine(sampleOption))
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun OptionLineDesktopPreview() {
    val sampleOption = MetadataOption(source = "Spotify", title = "Starlight", artist = "Muse", album = "Black Holes and Revelations", year = 2006, lengthSec = 240)
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.padding(32.dp)) {
            Text(optionLine(sampleOption))
        }
    }
}
