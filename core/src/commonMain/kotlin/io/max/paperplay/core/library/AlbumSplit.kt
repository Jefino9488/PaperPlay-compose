package io.max.paperplay.core.library

/**
 * Splits the songs that share one album name by artist, so a generic name such as "Greatest Hits"
 * does not merge unrelated artists. The owner is null when the album stays whole.
 *
 * Stays whole when: one artist, one artist has at least 60% of the songs (the rest are guests),
 * or four or more artists share it (a compilation). Otherwise one album per artist.
 */
fun <T> splitAlbum(items: List<T>, artistOf: (T) -> String): List<Pair<String?, List<T>>> {
    val byArtist = items.groupBy { artistOf(it).trim().lowercase() }
    if (byArtist.size <= 1) return listOf(null to items)
    val top = byArtist.values.maxOf { it.size }
    if (top * 5 >= items.size * 3 || byArtist.size >= 4) return listOf(null to items)
    return byArtist.values.map { list -> artistOf(list.first()).trim() to list }.sortedBy { it.first.lowercase() }
}
