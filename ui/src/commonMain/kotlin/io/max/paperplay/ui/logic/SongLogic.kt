package io.max.paperplay.ui.logic

import io.max.paperplay.ui.state.SongUi

/** Words of a search query, lowercased; blank query gives none. */
fun searchWords(query: String): List<String> = query.lowercase().split(Regex("\s+")).filter { it.isNotEmpty() }

/** True when title, artist, album or genre together hold every word, in any order. */
fun SongUi.matches(words: List<String>): Boolean {
    if (words.isEmpty()) return true
    val text = "$title $artist $album ${genre ?: ""}".lowercase()
    return words.all { text.contains(it) }
}

fun filterSongs(songs: List<SongUi>, query: String): List<SongUi> {
    val words = searchWords(query)
    return if (words.isEmpty()) songs else songs.filter { it.matches(words) }
}

/** Ways to sort a song list. Each puts the most useful end first. */
enum class SongSort(val label: String) {
    Added("Recently added"),
    Title("Title"),
    Artist("Artist"),
    Album("Album"),
    Quality("Quality"),
    Size("Size"),
    Duration("Length"),
    Year("Year"),
    Plays("Most played"),
}

private fun text(a: String, b: String) = a.lowercase().compareTo(b.lowercase())

fun sortSongs(songs: List<SongUi>, by: SongSort, reverse: Boolean): List<SongUi> {
    val cmp: Comparator<SongUi> = when (by) {
        SongSort.Added -> Comparator { a, b -> b.addedAt.compareTo(a.addedAt) }
        SongSort.Title -> Comparator { a, b -> text(a.title, b.title) }
        SongSort.Artist -> Comparator { a, b -> text(a.artist, b.artist).takeIf { it != 0 } ?: text(a.album, b.album) }
        SongSort.Album -> Comparator { a, b -> text(a.album, b.album).takeIf { it != 0 } ?: text(a.title, b.title) }
        SongSort.Quality -> Comparator { a, b -> b.qualityRank.compareTo(a.qualityRank) }
        SongSort.Size -> Comparator { a, b -> b.sizeBytes.compareTo(a.sizeBytes) }
        SongSort.Duration -> Comparator { a, b -> b.durationSec.compareTo(a.durationSec) }
        SongSort.Year -> Comparator { a, b -> (b.year ?: 0).compareTo(a.year ?: 0) }
        SongSort.Plays -> Comparator { a, b -> b.plays.compareTo(a.plays) }
    }
    val sorted = songs.sortedWith(cmp)
    return if (reverse) sorted.reversed() else sorted
}

/** "3:05" or "1:02:03". */
fun formatDuration(totalSec: Long): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}
