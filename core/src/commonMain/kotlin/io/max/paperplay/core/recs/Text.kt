package io.max.paperplay.core.recs

/** A local wall-clock instant. Mixes depend on the local day and hour, which common code cannot read itself. */
data class Moment(val epochMs: Long, val year: Int, val month: Int, val day: Int, val hour: Int) {
    /** `yyyy-mm-dd`, the key of a listening day. */
    val dayKey: String get() = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

private val artistSplit = Regex("""\s*(?:,|&|\bfeat\.?\s|\bft\.?\s)\s*""", RegexOption.IGNORE_CASE)
private val searchJunk = Regex(
    """\s*[\[(](?:feat\.?|ft\.?|with |from |explicit|clean|official|lyric|audio|video|remaster)[^\])]*[\])]""",
    RegexOption.IGNORE_CASE,
)
private val notLetterOrDigit = Regex("""[^\p{L}\p{N}]+""")

/** First name in `A, B & C`, since multi-artist strings rarely match exactly. */
fun firstArtist(artist: String): String = artist.split(artistSplit).first().trim()

/** Title for searching: drops `(feat. X)`, `[Explicit]` and similar. Keeps `(Remix)`. */
fun searchTitle(title: String): String = title.replace(searchJunk, "").trim().ifEmpty { title }

fun comparableText(s: String): String = s.lowercase().replace(notLetterOrDigit, "")
