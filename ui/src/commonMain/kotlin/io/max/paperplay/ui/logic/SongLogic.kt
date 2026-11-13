package io.max.paperplay.ui.logic

import io.max.paperplay.ui.state.SongUi

private val NON_WORD = Regex("[^\\p{L}\\p{N}\\p{M}]+")

/** Lowercase words of a search query; punctuation separates them, combining marks of Indian scripts stay inside a word. */
fun searchWords(query: String): List<String> = query.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

/** 0 when some word of the query starts no word of the song; otherwise higher is a better match (title, then artist, album, genre or year). */
fun SongUi.searchScore(words: List<String>): Int {
    if (words.isEmpty()) return 1
    val t = searchWords(title); val a = searchWords(artist); val al = searchWords(album)
    val g = searchWords(genre ?: ""); val y = year?.toString()
    var total = 0
    for (w in words) {
        total += when {
            t.any { it.startsWith(w) } -> 40
            a.any { it.startsWith(w) } -> 30
            al.any { it.startsWith(w) } -> 20
            g.any { it.startsWith(w) } || y == w -> 10
            else -> return 0
        }
    }
    if (t.joinToString(" ") == words.joinToString(" ")) total += 100
    return total
}

/** True when every word of the query starts a word of the song's title, artist, album, genre or year. */
fun SongUi.matches(words: List<String>): Boolean = searchScore(words) > 0

/** The songs that match [query], best match first; all of them, in their order, for a blank query. */
fun filterSongs(songs: List<SongUi>, query: String): List<SongUi> {
    val words = searchWords(query)
    if (words.isEmpty()) return songs
    return songs.mapNotNull { s -> s.searchScore(words).takeIf { it > 0 }?.let { s to it } }.sortedByDescending { it.second }.map { it.first }
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
