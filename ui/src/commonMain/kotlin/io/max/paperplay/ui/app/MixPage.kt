package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.state.SongUi

/**
 * A daily mix as its own page. Songs stay in mix order; removing one calls [onReject] (does not fit), which
 * should record the vote (MoodVotes.vote with -1) so the next mix learns from it. Rejected songs hide at once.
 */
@Composable
fun MixPage(
    title: String,
    songs: List<SongUi>,
    currentId: String?,
    actions: ListActions,
    onReject: (SongUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    var rejected by remember(songs) { mutableStateOf(emptySet<String>()) }
    val shown = remember(songs, rejected) { songs.filter { it.id !in rejected } }
    SongList(
        songs = shown,
        currentId = currentId,
        actions = actions,
        modifier = modifier.fillMaxSize(),
        sortable = false,
        header = {
            Column(Modifier.padding(16.dp, 8.dp)) {
                ScreenTitle(title)
                Text(
                    "${shown.size} songs. Remove a song that does not fit this mix and it will be left out next time.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        onRemove = { s -> rejected = rejected + s.id; onReject(s) },
        empty = "Nothing left in this mix.",
    )
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun MixPagePhonePreview() {
    val dummyActions = ListActions(play = { _, _ -> }, shuffle = {}, setFavorite = { _, _ -> }, addToPlaylist = {}, notice = {})
    val sampleSongs = listOf(SongUi(id = "1", title = "Starlight", artist = "Muse", durationSec = 240))
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.size(390.dp, 844.dp)) {
            MixPage(
                title = "Daily Mix 1",
                songs = sampleSongs,
                currentId = "1",
                actions = dummyActions,
                onReject = {},
            )
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun MixPageDesktopPreview() {
    val dummyActions = ListActions(play = { _, _ -> }, shuffle = {}, setFavorite = { _, _ -> }, addToPlaylist = {}, notice = {})
    val sampleSongs = listOf(SongUi(id = "1", title = "Starlight", artist = "Muse", durationSec = 240))
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.size(1024.dp, 768.dp)) {
            MixPage(
                title = "Daily Mix 1",
                songs = sampleSongs,
                currentId = "1",
                actions = dummyActions,
                onReject = {},
            )
        }
    }
}
