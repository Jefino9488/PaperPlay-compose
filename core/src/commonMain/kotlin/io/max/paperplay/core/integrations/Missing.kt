package io.max.paperplay.core.integrations

import io.max.paperplay.core.library.EnrichState
import io.max.paperplay.core.library.Song

/**
 * Something a song still lacks. [todo] ones are work the app hasn't done yet; the rest were
 * looked for and nothing was found, so they only change if a source starts to help.
 */
enum class Missing(val label: String, val todo: Boolean = false) {
    Waiting("Waiting for song info", true),
    ScanPending("Lyrics and genre not looked up yet", true),
    NoCover("No cover yet", true),
    NoMatch("No match found"),
    SmallCover("Small cover only"),
    NoAlbum("No album"),
    NoYear("No year"),
    NoGenre("No genre"),
    UnknownArtist("Unknown artist"),
}

/** What [s] still lacks. With [lookupsOn] off the lyrics and genre scan never runs, so it isn't counted. */
fun missingOf(s: Song, lookupsOn: Boolean): Set<Missing> {
    val looked = s.enrich_state != EnrichState.PENDING
    val scanned = !lookupsOn || s.mood_scanned_at > 0
    return buildSet {
        if (!looked) add(Missing.Waiting)
        if (lookupsOn && !scanned) add(Missing.ScanPending)
        if (s.cover_path == null) add(Missing.NoCover) else if (!s.cover_hi_res && s.cover_at == 0L) add(Missing.SmallCover)
        if (s.enrich_state == EnrichState.NO_MATCH) add(Missing.NoMatch)
        if (looked && s.album.isEmpty()) add(Missing.NoAlbum)
        if (looked && s.year == null) add(Missing.NoYear)
        if (looked && scanned && s.genre == null) add(Missing.NoGenre)
        if (s.artist == "Unknown artist") add(Missing.UnknownArtist)
    }
}
