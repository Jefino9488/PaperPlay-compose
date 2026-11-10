package io.max.paperplay.core.integrations

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import io.max.paperplay.core.db.Missing_tracks
import io.max.paperplay.core.db.Playlist_songs
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.MissingTrack
import io.max.paperplay.core.library.Playlist
import io.max.paperplay.core.library.PlaylistSong
import io.max.paperplay.core.library.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock

/** A track listed by a streaming service: only its details, never its audio. */
data class ImportedTrack(
    /** The service's id for the track, such as `spotify:track:...`. */
    val key: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    /** When it was added to the playlist, if the source says (milliseconds since epoch). */
    val addedAtMs: Long? = null,
)

class ImportError(message: String) : Exception(message)

data class FetchedPlaylist(val name: String, val tracks: List<ImportedTrack>)

private val spotifyPlaylist = Regex("""(?:open\.spotify\.com/(?:[\w-]+/)?playlist/|spotify:playlist:)([A-Za-z0-9]{22})""")

/** The playlist id in a Spotify link, or null if it isn't one. */
fun spotifyPlaylistId(link: String): String? = spotifyPlaylist.find(link)?.groupValues?.get(1)

private const val BROWSER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130 Safari/537.36"

/**
 * Reads a public playlist from Spotify's embed page, which needs no account. This isn't an
 * official API: Spotify can change the page or limit how many tracks it lists, so a failure
 * here must never touch what's already imported.
 */
suspend fun fetchSpotifyPlaylist(id: String, http: HttpFetcher): FetchedPlaylist {
    val res = http.send(HttpRequest("https://open.spotify.com/embed/playlist/$id", headers = mapOf("User-Agent" to BROWSER_AGENT)))
    if (res.status == 404) throw ImportError("Spotify can't find that playlist. It may be private.")
    if (res.status != 200) throw ImportError("Spotify answered with error ${res.status}.")
    return parseSpotifyEmbed(res.text)
}

private val nextData = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

/** Pulls the playlist out of the embed page's HTML. */
fun parseSpotifyEmbed(html: String): FetchedPlaylist {
    val changed = ImportError("Spotify's page has changed, so this link can't be read.")
    val data = nextData.find(html) ?: throw changed
    val json = try {
        Json.parseToJsonElement(data.groupValues[1])
    } catch (_: Exception) {
        throw changed
    }
    val list = findKey(json, "trackList") as? JsonArray
    if (list == null || list.isEmpty()) throw ImportError("That playlist is empty or private.")
    val tracks = list.mapNotNull { t ->
        val o = t as? JsonObject ?: return@mapNotNull null
        val uri = o.str("uri") ?: return@mapNotNull null
        val title = o.str("title") ?: return@mapNotNull null
        // The page separates artists with a non-breaking space.
        val artist = (o.str("subtitle") ?: "").replace(' ', ' ').trim()
        ImportedTrack(uri, title, artist, millisToSeconds(o["duration"]))
    }
    val name = (findKey(json, "name") as? JsonPrimitive)?.takeIf { it.isString }?.content
    return FetchedPlaylist(if (!name.isNullOrEmpty()) name else "Imported playlist", tracks)
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun millisToSeconds(ms: JsonElement?): Int {
    val p = ms as? JsonPrimitive ?: return 0
    if (p.isString) return 0
    return ((p.doubleOrNull ?: return 0) / 1000).roundToInt()
}

private fun findKey(node: JsonElement?, key: String): JsonElement? {
    when (node) {
        is JsonObject -> {
            if (key in node) return node[key]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            for (v in node.values) findKey(v, key)?.let { return it }
        }
        is JsonArray -> for (v in node) findKey(v, key)?.let { return it }
        else -> {}
    }
    return null
}

private val credit = Regex("""(?:[\[(]|\s[-–]\s|\s)(?:feat|ft|featuring)\b\.?\s*([^\])]*)[\])]?""", RegexOption.IGNORE_CASE)
private val remasterBrackets = Regex("""\s*[\[(][^\])]*remaster[^\])]*[\])]""", RegexOption.IGNORE_CASE)
private val remasterDash = Regex("""\s+[-–]\s+[^-–]*remaster.*$""", RegexOption.IGNORE_CASE)
private val versionTags = Regex("""\s*[\[(](?:mono|stereo|single version|album version|radio edit)[\])]""", RegexOption.IGNORE_CASE)

/** Title with featured-artist credits and remaster/version tags removed, so one song from different releases gets one key. */
fun looseTitleKey(title: String, artist: String): String {
    val t = cleanTitle(title, artist)
        .replace(remasterBrackets, "")
        .replace(remasterDash, "")
        .replace(versionTags, "")
        .replace(credit, "")
    return comparableText(searchTitle(t))
}

private fun artistsOf(artist: String): List<String> = splitArtists(artist).map(::comparableText).filter { it.isNotEmpty() }

/** Artists credited on a track: its artist field plus any "feat." names in the title. */
private fun credited(title: String, artist: String): List<String> =
    artistsOf(artist) + credit.findAll(title).flatMap { artistsOf(it.groupValues[1]) }

/** How sure a library song is the same recording as a wanted track. */
enum class MatchKind { Sure, Maybe }

/**
 * Same song if the loose title agrees and an artist is shared. With lengths known and within 8 s
 * that is [MatchKind.Sure]; a different or unknown length is only [MatchKind.Maybe], for the owner to confirm.
 */
fun matchKind(s: Song, title: String, artist: String, durationSec: Int): MatchKind? {
    val key = looseTitleKey(title, artist)
    if (key.isEmpty() || looseTitleKey(s.title, s.artist) != key) return null
    val mine = credited(s.title, s.artist)
    val theirs = credited(title, artist)
    if (theirs.none { a -> mine.any { b -> a in b || b in a } }) return null
    val known = s.duration_sec > 0 && durationSec > 0
    return if (known && abs(s.duration_sec - durationSec) <= 8) MatchKind.Sure else MatchKind.Maybe
}

fun matchesTrack(s: Song, title: String, artist: String, durationSec: Int): Boolean =
    matchKind(s, title, artist, durationSec) == MatchKind.Sure

/** Songs that might be [title] but can't be told for sure, closest length first. */
fun maybeMatches(songs: Iterable<Song>, title: String, artist: String, durationSec: Int): List<Song> =
    songs.filter { matchKind(it, title, artist, durationSec) == MatchKind.Maybe }
        .sortedBy { abs(it.duration_sec - durationSec) }

private fun Song.lossless() = isLossless(mime, file_name)

/** The best copy of a track: lossless first, then the largest file. */
fun bestMatch(songs: Iterable<Song>, title: String, artist: String, durationSec: Int): Song? {
    var best: Song? = null
    for (s in songs) {
        if (!matchesTrack(s, title, artist, durationSec)) continue
        val b = best
        if (b == null || (if (s.lossless() != b.lossless()) s.lossless() else s.size > b.size)) best = s
    }
    return best
}

/**
 * The playlist and missing-track tables for import and matching. Rows the generated queries
 * don't cover go through [driver] directly, then listeners are told.
 */
class ImportStore(val repo: LibraryRepo, private val driver: SqlDriver, val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val lists get() = repo.db.playlistsQueries

    fun <T> transaction(block: () -> T): T = repo.db.transactionWithResult { block() }

    fun livePlaylists(): List<Playlist> = lists.livePlaylists().executeAsList()
    fun songs(): List<Song> = repo.db.songsQueries.allSongs().executeAsList()
    fun missing(playlistId: Long): List<MissingTrack> = lists.missingTracks(playlistId).executeAsList()
    fun createPlaylist(name: String, sourceUrl: String): Long = repo.createPlaylist(name, sourceUrl)

    fun entries(playlistId: Long): List<PlaylistSong> = driver.executeQuery(
        null,
        "SELECT playlist_id, chat_id, message_id, added_at, source_added_at, removed_at FROM playlist_songs WHERE playlist_id = ?",
        { c ->
            val rows = mutableListOf<PlaylistSong>()
            while (c.next().value) {
                rows += Playlist_songs(c.getLong(0)!!, c.getLong(1)!!, c.getLong(2)!!, c.getLong(3)!!, c.getLong(4), c.getLong(5))
            }
            QueryResult.Value(rows)
        },
        1,
    ) { bindLong(0, playlistId) }.value

    fun insertMissing(playlistId: Long, t: ImportedTrack, position: Int) = lists.insertMissingTrack(
        Missing_tracks(playlistId, t.key, t.title, t.artist, t.durationSec.toLong(), position.toLong(), t.addedAtMs),
    )

    fun setMissingSourceAdded(playlistId: Long, key: String, ms: Long) =
        exec("UPDATE missing_tracks SET source_added_at = ? WHERE playlist_id = ? AND track_key = ?", "missing_tracks") {
            bindLong(0, ms); bindLong(1, playlistId); bindString(2, key)
        }

    fun deleteMissing(playlistId: Long, key: String) =
        exec("DELETE FROM missing_tracks WHERE playlist_id = ? AND track_key = ?", "missing_tracks") {
            bindLong(0, playlistId); bindString(1, key)
        }

    /** A new entry; an existing one (even a removed one) is left alone. */
    fun addEntry(playlistId: Long, chat: Long, message: Long, addedAt: Long, sourceAddedAt: Long?) = exec(
        "INSERT OR IGNORE INTO playlist_songs (playlist_id, chat_id, message_id, added_at, source_added_at, removed_at) VALUES (?, ?, ?, ?, ?, NULL)",
        "playlist_songs",
    ) { bindLong(0, playlistId); bindLong(1, chat); bindLong(2, message); bindLong(3, addedAt); bindLong(4, sourceAddedAt) }

    /** The entry is in the playlist (again) from [addedAt]; its source date changes only if [sourceAddedAt] is given. */
    fun upsertEntry(playlistId: Long, chat: Long, message: Long, addedAt: Long, sourceAddedAt: Long?, keepSource: Boolean) = exec(
        "INSERT INTO playlist_songs (playlist_id, chat_id, message_id, added_at, source_added_at, removed_at) VALUES (?, ?, ?, ?, ?, NULL) " +
            "ON CONFLICT (playlist_id, chat_id, message_id) DO UPDATE SET added_at = excluded.added_at, removed_at = NULL" +
            (if (keepSource) "" else ", source_added_at = excluded.source_added_at"),
        "playlist_songs",
    ) { bindLong(0, playlistId); bindLong(1, chat); bindLong(2, message); bindLong(3, addedAt); bindLong(4, sourceAddedAt) }

    fun setEntrySourceAdded(playlistId: Long, chat: Long, message: Long, ms: Long?) = exec(
        "UPDATE playlist_songs SET source_added_at = ? WHERE playlist_id = ? AND chat_id = ? AND message_id = ?",
        "playlist_songs",
    ) { bindLong(0, ms); bindLong(1, playlistId); bindLong(2, chat); bindLong(3, message) }

    fun markEntryRemoved(playlistId: Long, chat: Long, message: Long, at: Long) = exec(
        "UPDATE playlist_songs SET removed_at = ? WHERE playlist_id = ? AND chat_id = ? AND message_id = ?",
        "playlist_songs",
    ) { bindLong(0, at); bindLong(1, playlistId); bindLong(2, chat); bindLong(3, message) }

    /** Listeners of the generated queries re-run after raw writes. */
    fun announceChange() = driver.notifyListeners("playlist_songs", "missing_tracks", "playlists")

    private fun exec(sql: String, table: String, binders: app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit) {
        driver.execute(null, sql, countParams(sql), binders).value
        driver.notifyListeners(table)
    }

    private fun countParams(sql: String) = sql.count { it == '?' }
}

/** Review list: waiting tracks of [tracks] with the songs that might be them. */
fun reviewCandidates(store: ImportStore, tracks: Iterable<MissingTrack>): Map<String, List<Song>> {
    val byTitle = store.songs().groupBy { looseTitleKey(it.title, it.artist) }
    return tracks.associate { t ->
        t.track_key to maybeMatches(byTitle[looseTitleKey(t.title, t.artist)].orEmpty(), t.title, t.artist, t.duration_sec.toInt())
    }
}

/** The owner confirmed [song] is [track]: it joins the playlist and stops waiting. */
fun acceptMatch(store: ImportStore, track: MissingTrack, song: Song) {
    store.transaction {
        store.upsertEntry(
            track.playlist_id, song.chat_id, song.message_id, store.clock() + track.position, track.source_added_at, keepSource = false,
        )
        store.deleteMissing(track.playlist_id, track.track_key)
    }
    store.announceChange()
}

class ImportResult(
    val playlistId: Long,
    val name: String,
    val matched: Int,
    val missing: Int,
    /** It came from the public page and reached that page's limit, so there may be more. */
    val limited: Boolean = false,
)

/** How many tracks Spotify's public embed page lists, however long the playlist is. */
private const val EMBED_LIMIT = 100

/**
 * Creates or refreshes the playlist for [link]. Tracks already in the library join it; the rest
 * wait in its missing list until a matching song appears. A refresh only adds: tracks removed at
 * the source stay.
 *
 * [official] reads the whole list through the owner's Spotify login, when it can.
 */
suspend fun importPlaylist(
    store: ImportStore,
    link: String,
    http: HttpFetcher,
    official: (suspend (String) -> FetchedPlaylist?)? = null,
): ImportResult {
    val id = spotifyPlaylistId(link) ?: throw ImportError("That isn't a Spotify playlist link.")
    val whole = official?.invoke(id)
    val source = whole ?: fetchSpotifyPlaylist(id, http)
    val sourceUrl = "https://open.spotify.com/playlist/$id"

    val existing = store.livePlaylists().firstOrNull { it.source_url == sourceUrl }
    val playlistId = existing?.id ?: store.createPlaylist(source.name, sourceUrl)

    val known = store.missing(playlistId).map { it.track_key }.toMutableSet()
    store.transaction {
        source.tracks.forEachIndexed { i, t ->
            // add() is false for a repeat: playlists can list one track twice.
            if (!known.add(t.key)) {
                if (t.addedAtMs != null) store.setMissingSourceAdded(playlistId, t.key, t.addedAtMs)
            } else {
                store.insertMissing(playlistId, t, i)
            }
        }
    }
    reconcileImports(store)
    stampSourceDates(store, playlistId, source.tracks)
    val missing = store.missing(playlistId)
    val distinct = source.tracks.map { it.key }.toSet().size
    return ImportResult(
        playlistId,
        source.name,
        distinct - missing.size,
        missing.size,
        limited = whole == null && source.tracks.size >= EMBED_LIMIT,
    )
}

/**
 * Records the source's added dates on songs already in the playlist. Run on every refresh, so
 * playlists imported before dates were kept get them too.
 */
fun stampSourceDates(store: ImportStore, playlistId: Long, tracks: List<ImportedTrack>) {
    val dated = tracks.filter { it.addedAtMs != null }
    if (dated.isEmpty()) return
    val entries = store.entries(playlistId).filter { it.removed_at == null }
    val songs = store.songs().associateBy { it.chat_id to it.message_id }
    val byTitle = HashMap<String, MutableList<PlaylistSong>>()
    for (e in entries) {
        val s = songs[e.chat_id to e.message_id] ?: continue
        byTitle.getOrPut(looseTitleKey(s.title, s.artist)) { mutableListOf() } += e
    }
    var changed = false
    store.transaction {
        for (t in dated) {
            for (e in byTitle[looseTitleKey(t.title, t.artist)].orEmpty()) {
                if (e.source_added_at == t.addedAtMs || !matchesTrack(songs.getValue(e.chat_id to e.message_id), t.title, t.artist, t.durationSec)) continue
                store.setEntrySourceAdded(playlistId, e.chat_id, e.message_id, t.addedAtMs)
                changed = true
            }
        }
    }
    if (changed) store.announceChange()
}

/**
 * Matches waiting tracks against the library, and moves imported playlists to a lossless copy
 * when one has appeared. Run when songs are added or synced.
 *
 * Only the playlist entry moves. The old file stays in Telegram: deleting it automatically
 * could lose the wrong song on a bad match.
 */
fun reconcileImports(store: ImportStore) {
    val imported = store.livePlaylists().filter { it.source_url != null }
    if (imported.isEmpty()) return
    val library = store.songs()
    val byTitle = library.groupBy { looseTitleKey(it.title, it.artist) }
    fun sameTitle(title: String, artist: String) = byTitle[looseTitleKey(title, artist)].orEmpty()

    var changed = false
    val now = store.clock()
    store.transaction {
        for (p in imported) {
            val entries = store.entries(p.id)
            val keys = entries.map { it.chat_id to it.message_id }.toMutableSet()

            for (t in store.missing(p.id)) {
                val song = bestMatch(sameTitle(t.title, t.artist), t.title, t.artist, t.duration_sec.toInt()) ?: continue
                // A song someone removed from this playlist stays removed.
                if (keys.add(song.chat_id to song.message_id)) {
                    store.addEntry(p.id, song.chat_id, song.message_id, now + t.position, t.source_added_at)
                }
                store.deleteMissing(p.id, t.track_key)
                changed = true
            }

            for (e in entries.filter { it.removed_at == null }) {
                val song = library.firstOrNull { it.chat_id == e.chat_id && it.message_id == e.message_id }
                if (song == null || song.lossless()) continue
                val better = bestMatch(sameTitle(song.title, song.artist), song.title, song.artist, song.duration_sec.toInt())
                if (better == null || !better.lossless()) continue
                store.markEntryRemoved(p.id, e.chat_id, e.message_id, now)
                store.upsertEntry(p.id, better.chat_id, better.message_id, e.added_at, null, keepSource = true)
                changed = true
            }
        }
    }
    if (changed) store.announceChange()
}
