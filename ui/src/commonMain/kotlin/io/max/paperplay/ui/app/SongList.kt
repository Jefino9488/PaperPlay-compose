package io.max.paperplay.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.SongRow
import io.max.paperplay.ui.logic.Selection
import io.max.paperplay.ui.logic.SongSort
import io.max.paperplay.ui.logic.filterSongs
import io.max.paperplay.ui.logic.sortSongs
import io.max.paperplay.ui.state.SongActions
import io.max.paperplay.ui.state.SongUi

/** What a list of songs can do; the shell provides one. */
class ListActions(
    val play: (List<SongUi>, Int) -> Unit,
    val shuffle: (List<SongUi>) -> Unit,
    val setFavorite: (SongUi, Boolean) -> Unit,
    val addToPlaylist: (Set<String>) -> Unit,
    val notice: (String) -> Unit,
)

private fun ListActions.rowActions() = object : SongActions {
    override fun play(songs: List<SongUi>, index: Int) = this@rowActions.play(songs, index)
    override fun playNext(song: SongUi) = notice("Play next is not built yet.")
    override fun setFavorite(song: SongUi, favorite: Boolean) = this@rowActions.setFavorite(song, favorite)
    override fun setPinned(song: SongUi, pinned: Boolean) = notice("Pinning for offline is not built yet.")
    override fun addToPlaylist(song: SongUi) = this@rowActions.addToPlaylist(setOf(song.id))
}

/**
 * A song list with search, sort, play and shuffle, and multi-select. [sortable] off keeps the given order
 * (a playlist or album has its own).
 */
@Composable
fun SongList(
    songs: List<SongUi>,
    currentId: String?,
    actions: ListActions,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    sortable: Boolean = true,
    searchable: Boolean = true,
    header: (@Composable () -> Unit)? = null,
    empty: String = "No songs here yet.",
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SongSort.Added) }
    var reverse by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(Selection()) }
    var sortMenu by remember { mutableStateOf(false) }

    val shown = remember(songs, query, sort, reverse, sortable) {
        val found = filterSongs(songs, query)
        if (sortable) sortSongs(found, sort, reverse) else found
    }
    val rowActions = remember(actions) { actions.rowActions() }
    val picked = selection.retain(songs.mapTo(HashSet()) { it.id })

    Column(modifier) {
        if (picked.active) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${picked.size} picked", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton({ actions.play(shown.filter { it.id in picked }, 0); selection = Selection() }) { Text("Play") }
                TextButton({ actions.addToPlaylist(picked.ids); selection = Selection() }) { Text("Playlist") }
                TextButton({
                    val songsPicked = shown.filter { it.id in picked }
                    val makeFavorite = songsPicked.any { !it.favorite }
                    songsPicked.forEach { actions.setFavorite(it, makeFavorite) }
                    selection = Selection()
                }) { Text("Favorite") }
                TextButton({ selection = Selection() }) { Text("Clear") }
            }
        } else if (searchable) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Search songs, artists, albums") },
                    shape = RoundedCornerShape(28.dp),
                )
                if (sortable) Box {
                    TextButton({ sortMenu = true }) { Text("Sort") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        SongSort.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label + if (s == sort) (if (reverse) "  ↑" else "  ↓") else "") },
                                onClick = {
                                    if (s == sort) reverse = !reverse else { sort = s; reverse = false }
                                    sortMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = bottomPadding)) {
            if (header != null) item { header() }
            if (shown.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ actions.play(shown, 0) }) { Text("Play") }
                    OutlinedButton({ actions.shuffle(shown) }) { Text("Shuffle") }
                    Text("${shown.size} songs", Modifier.padding(start = 4.dp).align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (shown.isEmpty()) item {
                Text(
                    if (query.isNotBlank()) "No songs match \"${query.trim()}\"." else empty,
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(shown, key = { _, s -> s.id }) { i, song ->
                SongRow(
                    song,
                    onClick = { if (picked.active) selection = picked.toggle(song.id) else actions.play(shown, i) },
                    onLongClick = { selection = picked.toggle(song.id) },
                    current = song.id == currentId,
                    selected = song.id in picked,
                    actions = if (picked.active) null else rowActions,
                )
            }
        }
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
fun ClickableRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))
