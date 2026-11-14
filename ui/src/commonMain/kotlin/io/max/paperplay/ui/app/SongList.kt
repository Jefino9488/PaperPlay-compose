package io.max.paperplay.ui.app

import io.max.paperplay.ui.theme.motionItem
import io.max.paperplay.ui.components.AppIcon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.SongRow
import io.max.paperplay.ui.logic.Selection
import io.max.paperplay.ui.logic.SongSort
import io.max.paperplay.ui.logic.filterSongs
import io.max.paperplay.ui.logic.sortSongs
import io.max.paperplay.ui.state.SongActions
import io.max.paperplay.ui.state.SongUi

/** The shell's list actions, for pages (such as Settings pages) that show songs. */
/** The colour at the top of the page behind the content, so pinned bars can match it instead of showing plain black. */
internal val LocalPageTop = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<androidx.compose.ui.graphics.Color>?> { null }

val LocalListActions = androidx.compose.runtime.staticCompositionLocalOf<ListActions?> { null }

/** Topics of the main chat and starred channels, as lists to show beside the playlists. Empty where there are none. */
internal val LocalTelegramLists = androidx.compose.runtime.staticCompositionLocalOf<() -> List<CoreBackend.TelegramList>> { { emptyList() } }

/** What a list of songs can do; the shell provides one. */
class ListActions(
    val play: (List<SongUi>, Int) -> Unit,
    val shuffle: (List<SongUi>) -> Unit,
    val setFavorite: (SongUi, Boolean) -> Unit,
    val addToPlaylist: (Set<String>) -> Unit,
    val notice: (String) -> Unit,
    val playNext: (SongUi) -> Unit = {},
    val telegramLink: (SongUi) -> String? = { null },
    val findMetadata: (SongUi) -> Unit = {},
    val editTags: (SongUi) -> Unit = {},
    val changeCover: (SongUi) -> Unit = {},
    val setPinned: (SongUi, Boolean) -> Unit = { _, _ -> },
    val deleteSong: (SongUi) -> Unit = {},
)

internal fun ListActions.rowActions(onRemove: ((SongUi) -> Unit)? = null) = object : SongActions {
    override val removeLabel: String? get() = if (onRemove != null) "Remove from playlist" else null
    override fun remove(song: SongUi) { onRemove?.invoke(song) }
    override fun play(songs: List<SongUi>, index: Int) = this@rowActions.play(songs, index)
    override fun playNext(song: SongUi) = this@rowActions.playNext(song)
    override fun setFavorite(song: SongUi, favorite: Boolean) = this@rowActions.setFavorite(song, favorite)
    override fun setPinned(song: SongUi, pinned: Boolean) { this@rowActions.setPinned(song, pinned); notice(if (pinned) "Keeping on this device" else "No longer kept on this device") }
    override fun addToPlaylist(song: SongUi) = this@rowActions.addToPlaylist(setOf(song.id))
    override fun deleteFromTelegram(song: SongUi) = this@rowActions.deleteSong(song)
    override fun telegramLink(song: SongUi): String? = this@rowActions.telegramLink(song)
    override fun notice(text: String) = this@rowActions.notice(text)
    override fun findMetadata(song: SongUi) = this@rowActions.findMetadata(song)
    override fun editTags(song: SongUi) = this@rowActions.editTags(song)
    override fun changeCover(song: SongUi) = this@rowActions.changeCover(song)
}

/**
 * A song list with search, sort, play and shuffle, and multi-select.
 * [sortable] off keeps the given order (a playlist or album has its own).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SongList(
    songs: List<SongUi>,
    currentId: String?,
    actions: ListActions,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    sortable: Boolean = true,
    searchable: Boolean = true,
    header: (@Composable () -> Unit)? = null,
    onRemove: ((SongUi) -> Unit)? = null,
    empty: String = "No songs here yet.",
) {
    var query by remember { mutableStateOf("") }
    // The sort is remembered between lists and launches.
    val uiPrefs = LocalUiPrefs.current
    var sort by remember { mutableStateOf(uiPrefs?.songSort ?: SongSort.Added) }
    var reverse by remember { mutableStateOf(uiPrefs?.songSortReverse ?: false) }
    var selection by remember { mutableStateOf(Selection()) }
    var sortSheet by remember { mutableStateOf(false) }

    val shown = remember(songs, query, sort, reverse, sortable) {
        val found = filterSongs(songs, query)
        if (sortable) sortSongs(found, sort, reverse) else found
    }
    val rowActions = remember(actions, onRemove) { actions.rowActions(onRemove) }
    val picked = remember(selection, songs) { if (selection.ids.isEmpty()) selection else selection.retain(songs.mapTo(HashSet()) { it.id }) }

    val songCovers = remember(shown) {
        val map = mutableMapOf<String, String>()
        shown.forEach { song ->
            val uri = song.coverUri
            if (!uri.isNullOrBlank()) {
                if (song.album.isNotBlank()) map.putIfAbsent("album:${song.album}", uri)
                if (song.artist.isNotBlank()) map.putIfAbsent("artist:${song.artist}", uri)
            }
        }
        map
    }

    Column(modifier) {
        if (picked.active) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = appShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "${picked.size} picked",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = {
                        actions.play(shown.filter { it.id in picked }, 0)
                        selection = Selection()
                    }) { Text("Play") }
                    TextButton(onClick = {
                        actions.addToPlaylist(picked.ids)
                        selection = Selection()
                    }) { Text("Playlist") }
                    TextButton(onClick = {
                        val songsPicked = shown.filter { it.id in picked }
                        val makeFavorite = songsPicked.any { !it.favorite }
                        songsPicked.forEach { actions.setFavorite(it, makeFavorite) }
                        selection = Selection()
                    }) { Text("Favorite") }
                    IconButton(onClick = { selection = Selection() }) {
                        AppIcon(Icons.Rounded.Close, "Clear selection")
                    }
                }
            }
        } else if (searchable) {
            PillField(
                value = query,
                onChange = { query = it },
                hint = "Search songs, artists, albums, years",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val barIndex = if (header != null) 1 else 0
        // Pinned means rows scroll under the bar. At its natural place it stays see-through, so the page colour shows.
        val pinned by androidx.compose.runtime.remember(barIndex) {
            androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > barIndex || (listState.firstVisibleItemIndex == barIndex && listState.firstVisibleItemScrollOffset > 0) }
        }
        val pageTop = LocalPageTop.current
        val base = MaterialTheme.colorScheme.background
        // On desktop the list sits on a card, not the page gradient, so the pinned bar takes the card colour.
        val cardFill = MaterialTheme.colorScheme.surfaceContainerLow
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            // Horizontal insets live on the items, so the pinned bar can span the full width and hide rows fully.
            contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
        ) {
            // A Column, not a Box: the header can hold several parts (cover, then the missing-songs row) that stack down.
            if (header != null) item { Column(Modifier.padding(horizontal = 16.dp)) { header() } }
            if (shown.isNotEmpty()) stickyHeader(key = "song-list-bar") {
                val mins = shown.sumOf { it.durationSec } / 60
                val summary = "${shown.size} ${if (shown.size == 1) "song" else "songs"} · " +
                    if (mins >= 60) "${mins / 60} hr ${mins % 60} min" else "$mins min"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind { if (pinned) drawRect(if (io.max.paperplay.ui.isPhone) pageTop?.value ?: base else cardFill) }
                        .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledIconButton(
                        onClick = { actions.play(shown, 0) },
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                    ) { AppIcon(Icons.Rounded.PlayArrow, contentDescription = "Play all") }
                    FilledTonalIconButton(
                        onClick = { actions.shuffle(shown) },
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                    ) { AppIcon(Icons.Rounded.Shuffle, contentDescription = "Shuffle all", modifier = Modifier.size(20.dp)) }
                    Text(
                        text = summary,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (sortable) {
                        // Icon only; the order itself shows in the sheet that opens, and is remembered.
                        IconButton(onClick = { sortSheet = true }) {
                            AppIcon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort: ${sort.label}")
                        }
                    }
                }
            }
            if (shown.isEmpty()) item {
                Surface(
                    shape = appShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = if (query.isNotBlank()) "No songs match \"${query.trim()}\"." else empty,
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(shown, key = { _, s -> s.id }) { i, song ->
                val songCoverUri = song.coverUri
                    ?: (if (song.album.isNotBlank()) songCovers["album:${song.album}"] else null)
                    ?: (if (song.artist.isNotBlank()) songCovers["artist:${song.artist}"] else null)
                SongRow(
                    song = song,
                    onClick = { if (picked.active) selection = picked.toggle(song.id) else actions.play(shown, i) },
                    onLongClick = { selection = picked.toggle(song.id) },
                    current = song.id == currentId,
                    selected = song.id in picked,
                    coverUriOverride = songCoverUri,
                    actions = if (picked.active) null else rowActions,
                    modifier = motionItem().padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
            }
        }
        if (sortSheet) {
            ModalBottomSheet(onDismissRequest = { sortSheet = false }) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Sort songs", style = MaterialTheme.typography.titleLarge)
                    SongSort.entries.forEach { option ->
                        TextButton(
                            onClick = {
                                if (option == sort) reverse = !reverse else { sort = option; reverse = false }
                                uiPrefs?.setSongSort(sort, reverse)
                                sortSheet = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "${option.label}${if (option == sort) if (reverse) " (descending)" else " (ascending)" else ""}",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun ClickableRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

private val previewActions: ListActions
    get() = ListActions(
        play = { _, _ -> },
        shuffle = {},
        setFavorite = { _, _ -> },
        addToPlaylist = {},
        notice = {},
    )

private val previewSongList = listOf(
    SongUi(id = "1", title = "Song One", artist = "Artist A", album = "Album 1", durationSec = 200, sizeBytes = 5_000_000, qualityLabel = "320 kbps", favorite = true, pinned = false, offline = true),
    SongUi(id = "2", title = "Song Two", artist = "Artist B", album = "Album 2", durationSec = 180, sizeBytes = 4_500_000, qualityLabel = "FLAC", favorite = false, pinned = true, offline = true),
)

@Preview(name = "Song list", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun SongListPreview() = io.max.paperplay.ui.PreviewFrame {
    SongList(songs = previewSongList + io.max.paperplay.ui.previewSongs, currentId = "1", actions = previewActions)
}

@Preview
@Composable
private fun ScreenTitlePreview() {
    ScreenTitle(text = "Library Title")
}

@Preview
@Composable
private fun SectionPreview() {
    Section(title = "Featured Songs")
}

@Preview
@Composable
private fun ClickableRowPreview() {
    ClickableRow(onClick = {}) {
        Text("Clickable Row Content", modifier = Modifier.padding(16.dp))
    }
}


@Preview(name = "Gap", showBackground = true)
@Composable
private fun GapPreview() = io.max.paperplay.ui.PreviewFrame { Gap(24) }
