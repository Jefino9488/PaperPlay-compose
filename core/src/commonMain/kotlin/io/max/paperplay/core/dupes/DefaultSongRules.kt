package io.max.paperplay.core.dupes

import io.max.paperplay.core.library.Song
import io.max.paperplay.core.metadata.cleanTitle
import io.max.paperplay.core.metadata.comparableText
import io.max.paperplay.core.metadata.firstArtist
import io.max.paperplay.core.metadata.searchTitle

/** Format and quality rules for the duplicate finder, from what the scanner stored about each song. */
object DefaultSongRules : SongRules {
    private val lossless = setOf("FLAC", "ALAC", "WAV", "AIFF", "APE", "WV", "TTA", "WAVPACK")

    override fun format(s: Song): String {
        s.codec?.takeIf { it.isNotBlank() }?.let { return it.uppercase() }
        val ext = s.file_name.substringAfterLast('.', "").uppercase()
        if (ext.isNotEmpty() && ext.length <= 5) return if (ext == "M4A") "AAC" else ext
        return s.mime.substringAfterLast('/').uppercase().removePrefix("X-").ifEmpty { "AUDIO" }
    }

    override fun isLossless(format: String) = format.uppercase() in lossless

    /** Better first: lossless over lossy, then bit depth, sample rate and file size. */
    override fun compareQuality(a: Song, b: Song): Int {
        val tier = isLossless(format(b)).compareTo(isLossless(format(a)))
        if (tier != 0) return tier
        val depth = (b.bit_depth ?: 0).compareTo(a.bit_depth ?: 0)
        if (depth != 0) return depth
        val rate = (b.sample_rate ?: 0).compareTo(a.sample_rate ?: 0)
        if (rate != 0) return rate
        return b.size.compareTo(a.size)
    }

    override fun comparableTitle(s: Song) = comparableText(searchTitle(cleanTitle(s.title, s.artist)))
    override fun comparableArtist(s: Song) = comparableText(firstArtist(s.artist))
}
