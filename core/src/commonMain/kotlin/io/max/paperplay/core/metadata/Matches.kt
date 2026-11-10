package io.max.paperplay.core.metadata

import io.max.paperplay.core.library.Song
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.objs(key: String): List<JsonObject> = (this[key] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

fun yearOf(date: String?): Int? = if (date == null || date.length < 4) null else date.substring(0, 4).toIntOrNull()

class MbMatch(
    val score: Int,
    val title: String,
    val artist: String,
    val album: String,
    val releaseId: String?,
    val lengthSec: Int?,
    val year: Int? = null,
) {
    companion object {
        fun fromJson(r: JsonObject): MbMatch {
            val releases = r.objs("releases")
            val credits = r.objs("artist-credit")
            return MbMatch(
                score = r.int("score") ?: 0,
                title = r.str("title") ?: "",
                artist = credits.joinToString("") { (it.str("name") ?: "") + (it.str("joinphrase") ?: "") },
                album = releases.firstOrNull()?.str("title") ?: "",
                releaseId = releases.firstOrNull()?.str("id"),
                lengthSec = r.int("length")?.let { it / 1000 },
                year = yearOf(r.str("first-release-date")),
            )
        }
    }
}

/** A track from Deezer or iTunes, used when MusicBrainz has no match. */
class ExternalMatch(
    val source: String,
    val title: String,
    val artist: String,
    val album: String,
    val lengthSec: Int,
    val coverUrl: String? = null,
    val genre: String? = null,
    val year: Int? = null,
) {
    companion object {
        fun fromDeezer(r: JsonObject): ExternalMatch {
            val album = r.obj("album")
            return ExternalMatch(
                source = "Deezer",
                title = r.str("title") ?: "",
                artist = r.obj("artist")?.str("name") ?: "",
                album = album?.str("title") ?: "",
                lengthSec = r.int("duration") ?: 0,
                coverUrl = album?.str("cover_xl"),
            )
        }

        fun fromItunes(r: JsonObject) = ExternalMatch(
            source = "iTunes",
            title = r.str("trackName") ?: "",
            artist = r.str("artistName") ?: "",
            album = r.str("collectionName") ?: "",
            lengthSec = (r.int("trackTimeMillis") ?: 0) / 1000,
            coverUrl = r.str("artworkUrl100")?.replaceFirst("100x100bb", "600x600bb"),
            genre = r.str("primaryGenreName"),
            year = yearOf(r.str("releaseDate")),
        )
    }
}

/**
 * First result that's safely the same recording: the title and artist agree, and the length is
 * within 5 seconds. A file with no length is matched on title and a known artist alone.
 */
fun pickExternal(results: List<ExternalMatch>, title: String, artist: String, durationSec: Int): ExternalMatch? {
    val wantTitle = comparableText(searchTitle(cleanTitle(title, artist)))
    val wantArtist = if (artist == "Unknown artist") "" else comparableText(firstArtist(artist))
    if (wantTitle.isEmpty()) return null
    if (durationSec <= 0 && wantArtist.isEmpty()) return null
    for (r in results) {
        if (durationSec > 0 && kotlin.math.abs(r.lengthSec - durationSec) > 5) continue
        if (comparableText(searchTitle(r.title)) != wantTitle) continue
        if (wantArtist.isEmpty() || comparableText(r.artist).contains(wantArtist)) return r
    }
    return null
}

/** First match safe to apply without asking: score >= 90 and length within 5s. */
fun pickMatch(matches: List<MbMatch>, durationSec: Int): MbMatch? {
    if (durationSec <= 0) return null
    return matches.firstOrNull { m -> m.score >= 90 && m.lengthSec != null && kotlin.math.abs(m.lengthSec - durationSec) <= 5 }
}

/** The album a MusicBrainz release belongs to. */
fun releaseGroupOf(release: JsonObject): String? = release.obj("release-group")?.str("id")

/** A search result the owner can apply to a song, from any service. */
class MetadataOption(
    val source: String,
    val title: String,
    val artist: String,
    val album: String,
    val lengthSec: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val releaseId: String? = null,
    val coverUrl: String? = null,
) {
    companion object {
        fun fromMusicBrainz(m: MbMatch) = MetadataOption(
            "MusicBrainz", m.title, m.artist, m.album, m.lengthSec, m.year, releaseId = m.releaseId,
        )

        fun fromExternal(m: ExternalMatch) = MetadataOption(
            m.source, m.title, m.artist, m.album, m.lengthSec, m.year, m.genre, coverUrl = m.coverUrl,
        )
    }
}

/** A cover found online that the owner can choose. */
class CoverOption(val url: String, val label: String)

enum class CoverWork { None, First, Upgrade }

/**
 * What a song on screen still needs for its cover. A thumbnail with a full-size source is upgraded
 * right away. Covers someone chose or edited are left alone.
 */
fun coverWorkFor(s: Song): CoverWork {
    if (s.cover_path == null) return CoverWork.First
    val hasSource = s.release_id != null || s.cover_url != null
    if (hasSource && !s.cover_hi_res && s.cover_at == 0L && !s.user_edited) return CoverWork.Upgrade
    return CoverWork.None
}

/** What a song's own file says about it. */
class EmbeddedTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val genre: String? = null,
)

/**
 * What a file's own tags add to what's known. Only gaps are filled: an online match keeps what it
 * found, and a person's edits are never touched. Null when nothing's new.
 */
fun fillFromTags(s: Song, t: EmbeddedTags): Song? {
    if (s.user_edited) return null
    val album = (t.album ?: "").trim()
    val genre = (t.genre ?: "").trim()
    val artist = (t.artist ?: "").trim()
    val year = t.year?.takeIf { it in 1900..2100 }
    var fill = s
    if (s.album.isEmpty() && album.isNotEmpty()) fill = fill.copy(album = album)
    if (s.year == null && year != null) fill = fill.copy(year = year.toLong())
    if (s.genre == null && genre.isNotEmpty()) fill = fill.copy(genre = genre)
    if (s.artist == "Unknown artist" && artist.isNotEmpty()) fill = fill.copy(artist = artist)
    if (fill == s) return null
    return if (s.metadata_source == null) fill.copy(metadata_source = "File tags") else fill
}
