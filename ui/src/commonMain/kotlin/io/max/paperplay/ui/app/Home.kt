package io.max.paperplay.ui.app

import io.max.paperplay.ui.components.AppIcon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.state.CollectionUi
import io.max.paperplay.ui.state.GroupUi
import io.max.paperplay.ui.state.HomeUi
import io.max.paperplay.ui.state.LibraryUi
import io.max.paperplay.ui.state.Load
import io.max.paperplay.ui.state.ShelfKindUi
import io.max.paperplay.ui.state.SongUi
import io.max.paperplay.ui.theme.hsl

private val cardSize = 150.dp

@Composable
fun HomeScreen(
    lib: LibraryUi, home: HomeUi, favorites: List<SongUi>, currentId: String?,
    conn: ConnectionUi, scan: State<ScanUi?>, actions: ListActions, onOpen: (Detail) -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier,
    name: String = "",
) {
    val songs = (lib.songs as? Load.Ready)?.value.orEmpty()
    // Quick tiles: favorites, up to three playlists, then generated collections to make six.
    val tileRows = remember(favorites, lib.playlists, home.tiles, actions) {
        buildList<Pair<GroupUi, () -> Unit>> {
            if (favorites.isNotEmpty()) add(GroupUi("fav", "Favorites", coverUris = favorites.map { it.coverUri }) to { actions.play(favorites, 0) })
            lib.playlists.take(3).forEach { g -> add(g to { onOpen(Detail.Playlist(g.id, g.name)) }) }
            home.tiles.take(6 - size).forEach { c -> add(GroupUi(c.id, c.name, coverUris = listOf(c.coverUri)) to { onOpen(Detail.Songs(c.name, c.songs)) }) }
        }.chunked(2)
    }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = bottomPadding)) {
        item { HomeHeader(name) }
        if (tileRows.isNotEmpty()) item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tileRows.forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (g, click) ->
                            Row(
                                Modifier.weight(1f).height(56.dp).partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                                    .clickable(onClick = click),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CoverGrid(g.coverUris, g.id, 56.dp, 0.dp)
                                Text(g.name, Modifier.padding(horizontal = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (home.mixes.isNotEmpty()) item {
            Column {
                Section("Your daily mixes")
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(home.mixes, key = { it.id }) { m -> MixCard(m) { onOpen(Detail.Mix(m.name, m.id, m.songs)) } }
                }
            }
        } else if (songs.isNotEmpty()) item {
            Text("Daily mixes appear as Paper Play learns your music.", Modifier.padding(16.dp, 20.dp, 16.dp, 0.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (lib.playlists.isNotEmpty()) item {
            Column {
                Section("Your playlists")
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(lib.playlists, key = { it.id }) { g ->
                        Column(Modifier.width(cardSize + 16.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(Detail.Playlist(g.id, g.name)) }.padding(8.dp)) {
                            CoverGrid(g.coverUris, g.id, cardSize, 10.dp)
                            Text(g.name, Modifier.padding(top = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
        items(home.shelves, key = { it.title }) { shelf ->
            when (shelf.kind) {
                ShelfKindUi.Songs -> SongShelf(shelf.title, shelf.songs, currentId, actions)
                else -> Column {
                    Section(shelf.title)
                    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                        items(shelf.groups, key = { it.id }) { g ->
                            MediaCard(g.coverUri, g.id, g.name, g.subtitle, circle = shelf.kind == ShelfKindUi.Artists) {
                                onOpen(if (shelf.kind == ShelfKindUi.Albums) Detail.Album(g.name) else Detail.Artist(g.name))
                            }
                        }
                    }
                }
            }
        }
    }
}

private val homeMessages = listOf(
    "Ready when you are.", "What are we playing today?", "Your music, your way.", "Good tunes are waiting.",
    "Press play on something great.", "Let the music pick the mood.", "Find your next favorite song.", "Turn it up.",
)

/** Greeting with the user's name and one message that stays fixed while the screen is open. */
@Composable
private fun HomeHeader(name: String) {
    val h = io.max.paperplay.ui.currentHour()
    val greeting = if (h < 12) "Good morning" else if (h < 18) "Good afternoon" else "Good evening"
    val message = remember { homeMessages.random() }
    val who = name.trim()
    Column(Modifier.barSurface().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 16.dp)) {
        Text(if (who.isEmpty()) greeting else "$greeting, $who", style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(message, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Connection and scan status as a small toast over the page: swipe it sideways to dismiss; it takes the page colour. */
@Composable
fun StatusToast(conn: ConnectionUi, scanState: State<ScanUi?>, songCount: Int, tint: Color, modifier: Modifier = Modifier) {
    val scan = scanState.value
    val text = when {
        conn.error != null -> conn.error
        !conn.ready && conn.state.isNotEmpty() -> "Connecting to Telegram…"
        scan != null -> scan.text
        songCount == 0 && conn.ready -> "No songs yet. Add the chat that holds your music in Settings."
        else -> null
    }
    var dismissed by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableStateOf(0f) }
    var last by remember { mutableStateOf("") }
    if (text != null) last = text
    if (text == null) dismissed = null
    // A message that stays (not scan progress, which keeps changing) goes away after 20 seconds.
    androidx.compose.runtime.LaunchedEffect(if (scan != null && conn.error == null) null else text) {
        if (text != null && scan == null) { kotlinx.coroutines.delay(20_000); dismissed = text }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = text != null && text != dismissed,
        modifier = modifier,
        enter = androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut(),
    ) {
        val scheme = MaterialTheme.colorScheme
        val bg = androidx.compose.ui.graphics.lerp(tint, scheme.onSurface, 0.16f)
        val fg = if (bg.luminance() < 0.5f) Color.White else Color.Black
        Column(
            Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp)
                .offset { androidx.compose.ui.unit.IntOffset(offset.toInt(), 0) }
                .graphicsLayer { alpha = (1f - kotlin.math.abs(offset) / 600f).coerceIn(0f, 1f) }
                .shadow(6.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(bg)
                .pointerInput(text) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (kotlin.math.abs(offset) > 80f) dismissed = text; offset = 0f },
                        onDragCancel = { offset = 0f },
                    ) { _, dx -> offset += dx }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(text ?: last, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (scan != null && conn.error == null) {
                val f = scan.fraction
                val m = Modifier.fillMaxWidth().padding(top = 8.dp)
                if (f != null) LinearProgressIndicator({ f.toFloat() }, m, color = fg, trackColor = fg.copy(alpha = 0.25f))
                else LinearProgressIndicator(m, color = fg, trackColor = fg.copy(alpha = 0.25f))
            }
        }
    }
}

@Composable
fun SongShelf(title: String, songs: List<SongUi>, currentId: String?, actions: ListActions) {
    Column {
        Section(title)
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
            items(songs.size) { i ->
                val s = songs[i]
                MediaCard(s.coverUri, s.id, s.title, s.artist, titleColor = if (s.id == currentId) MaterialTheme.colorScheme.primary else null) { actions.play(songs, i) }
            }
        }
    }
}

/** A cover with a title and subtitle under it; round for artists. */
@Composable
fun MediaCard(coverUri: String?, key: String, title: String, subtitle: String?, circle: Boolean = false, titleColor: Color? = null, onClick: () -> Unit) {
    val align = if (circle) Alignment.CenterHorizontally else Alignment.Start
    Column(Modifier.width(cardSize + 16.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp), horizontalAlignment = align) {
        CoverImage(coverUri, key, size = cardSize, radius = if (circle) cardSize / 2 else 10.dp)
        Text(
            title, Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
            color = titleColor ?: MaterialTheme.colorScheme.onSurface, textAlign = if (circle) TextAlign.Center else TextAlign.Start,
        )
        if (!subtitle.isNullOrEmpty()) Text(
            subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (circle) TextAlign.Center else TextAlign.Start,
        )
    }
}

private val moodColors = mapOf(
    "happy" to Color(0xFFB8860B), "sad" to Color(0xFF34568B), "drive" to Color(0xFFB5482A), "rain" to Color(0xFF3F6B7D),
    "chill" to Color(0xFF2F7D6B), "focus" to Color(0xFF4F5B93), "party" to Color(0xFFA8326E), "night" to Color(0xFF3B2F6B),
    "love" to Color(0xFFB03A5B), "workout" to Color(0xFFA33A2A),
)

private val moodIcons = mapOf(
    "happy" to Icons.Rounded.WbSunny, "sad" to Icons.Rounded.WaterDrop, "drive" to Icons.Rounded.DirectionsCar, "rain" to Icons.Rounded.Umbrella,
    "chill" to Icons.Rounded.Spa, "focus" to Icons.Rounded.CenterFocusStrong, "party" to Icons.Rounded.Celebration, "night" to Icons.Rounded.NightsStay,
    "love" to Icons.Rounded.Favorite, "workout" to Icons.Rounded.FitnessCenter,
)

/** A daily mix: its covers washed in the mood's colour, with its name. */
@Composable
private fun MixCard(mix: CollectionUi, onClick: () -> Unit) {
    val color = moodColors[mix.id] ?: MaterialTheme.colorScheme.primary
    Column(Modifier.width(cardSize + 16.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Box(Modifier.size(cardSize).clip(RoundedCornerShape(10.dp))) {
            CoverGrid(mix.coverUris, mix.id, cardSize, 0.dp)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.95f)))))
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                moodIcons[mix.id]?.let { AppIcon(it, null, Modifier.size(22.dp), tint = Color.White) }
                Spacer(Modifier.weight(1f))
                Text(mix.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
        Text(mix.subtitle, Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Browse by genre: a tile per genre, largest first, with a cover tilted into the corner. */
@Composable
fun GenreGrid(songs: List<SongUi>, bottomPadding: Dp, onOpen: (Detail) -> Unit, modifier: Modifier = Modifier) {
    val genres = androidx.compose.runtime.remember(songs) { songs.filter { !it.genre.isNullOrBlank() }.groupBy { it.genre!!.trim() }.entries.sortedByDescending { it.value.size } }
    if (genres.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Genres appear here as song info is found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    BoxWithConstraints(modifier) {
        val cols = (maxWidth / 200.dp).toInt().coerceIn(2, 6)
        LazyVerticalGrid(
            GridCells.Fixed(cols), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, bottomPadding + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(genres, key = { it.key }) { (genre, list) ->
                // Stable per genre, dark enough for white text in either theme.
                val hue = (genre.hashCode() % 360).let { if (it < 0) -it else it }.toFloat()
                Box(
                    Modifier.aspectRatio(1.9f).clip(RoundedCornerShape(12.dp)).background(hsl(hue, 0.45f, 0.32f))
                        .clickable { onOpen(Detail.Songs(genre, list)) },
                ) {
                    val art = list.firstOrNull { it.coverUri != null }
                    if (art != null) CoverImage(
                        art.coverUri, art.id, Modifier.align(Alignment.BottomEnd).offset(12.dp, 8.dp).rotate(24f).shadow(8.dp, RoundedCornerShape(6.dp)),
                        size = 64.dp, radius = 6.dp,
                    )
                    Text(
                        genre, Modifier.padding(14.dp, 14.dp, 60.dp, 14.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium, color = Color.White,
                    )
                }
            }
        }
    }
}

private val previewHomeSongs = listOf(
    SongUi("1", "Supermassive Black Hole", "Muse", "Black Holes and Revelations", genre = "Rock", durationSec = 209),
    SongUi("2", "Starlight", "Muse", "Black Holes and Revelations", genre = "Rock", durationSec = 240),
)

private val previewActions = ListActions(play = { _, _ -> }, shuffle = {}, setFavorite = { _, _ -> }, addToPlaylist = {}, notice = {})

@Preview
@Composable
private fun MediaCardPreview() {
    MediaCard(coverUri = null, key = "preview_art", title = "Muse", subtitle = "Artist", circle = true, onClick = {})
}

@Preview
@Composable
private fun SongShelfPreview() {
    SongShelf(title = "Recently Added", songs = previewHomeSongs, currentId = "1", actions = previewActions)
}

@Preview
@Composable
private fun GenreGridPreview() {
    GenreGrid(songs = previewHomeSongs, bottomPadding = 0.dp, onOpen = {})
}

@Preview
@Composable
private fun HomeScreenPreview() {
    HomeScreen(
        lib = LibraryUi(songs = Load.Ready(previewHomeSongs)),
        home = HomeUi(),
        favorites = previewHomeSongs,
        currentId = null, scan = remember { mutableStateOf(null) },
        conn = ConnectionUi(ready = true),
        actions = previewActions,
        onOpen = {},
        bottomPadding = 0.dp,
    )
}


@Preview(name = "Header", showBackground = true)
@Composable
private fun HomeHeaderPreview() = io.max.paperplay.ui.PreviewFrame { HomeHeader("Max") }

@Preview(name = "Status toast", showBackground = true)
@Composable
private fun StatusToastPreview() = io.max.paperplay.ui.PreviewFrame {
    StatusToast(
        conn = ConnectionUi(ready = true), scanState = remember { mutableStateOf<ScanUi?>(ScanUi("Reading channel 3 of 5", 0.4)) },
        songCount = 120, tint = MaterialTheme.colorScheme.primary,
    )
}

@Preview(name = "Mix card", showBackground = true)
@Composable
private fun MixCardPreview() = io.max.paperplay.ui.PreviewFrame {
    MixCard(CollectionUi(id = "mix1", name = "Daily Mix 1", subtitle = "Muse, Queen and more"), onClick = {})
}
