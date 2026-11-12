package io.max.paperplay.core.recs

import app.cash.sqldelight.db.SqlDriver
import io.max.paperplay.core.integrations.HttpFetcher
import io.max.paperplay.core.integrations.HttpRequest
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.MoodVote
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.metadata.cleanTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private fun enc(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt().toChar()
        if (b >= 0 && (c.isLetterOrDigit() || c in "-._~")) append(c)
        else append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
    }
}

/**
 * Looks up each song's lyrics and genre once, in the background, one at a time, so mixes have something to go on
 * (port of the Flutter mood scan). Lyrics come from LRCLIB, a missing genre from iTunes when title and artist agree.
 * Every song is stamped scanned, found or not, so it is tried once. A network failure retries after five minutes.
 */
class MoodScanner(
    private val driver: SqlDriver,
    private val repo: LibraryRepo,
    private val http: HttpFetcher,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    /** Whether background lookups are allowed (the lookups setting). */
    private val allowed: () -> Boolean = { true },
    /** Whether metadata lookups are still running; the scan waits for them. */
    private val enrichRunning: () -> Boolean = { false },
    private val gapMs: Long = 1500,
) {
    private var job: Job? = null
    private var retry: Job? = null

    val scanned: Int get() = repo.db.songsQueries.moodScannedCount().executeAsOne().toInt()

    fun start() {
        if (job?.isActive == true) return
        retry?.cancel()
        job = scope.launch {
            try {
                while (isActive) {
                    if (enrichRunning()) { delay(10_000); continue }
                    if (!allowed()) return@launch
                    val song = repo.db.songsQueries.nextForMoodScan().executeAsOneOrNull() ?: return@launch
                    scanOne(song)
                    delay(gapMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                retry = scope.launch { delay(5 * 60_000L); start() }
            }
        }
    }

    fun stop() { job?.cancel(); retry?.cancel() }

    /** Makes every song eligible again, then scans. */
    fun rescanAll() {
        driver.execute(null, "UPDATE songs SET mood_scanned_at = 0", 0)
        start()
    }

    internal suspend fun scanOne(s: Song) {
        var lyrics: String? = null
        var instrumental = false
        var genre: String? = null
        val artist = firstArtist(s.artist)
        if (s.lyrics == null) {
            val r = http.send(HttpRequest("https://lrclib.net/api/search?track_name=${enc(s.title)}&artist_name=${enc(artist)}"))
            if (r.status == 200) {
                val found = (runCatching { Json.parseToJsonElement(r.text) }.getOrNull() as? JsonArray)
                    ?.filterIsInstance<JsonObject>().orEmpty()
                fun text(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                lyrics = found.firstNotNullOfOrNull { text(it, "syncedLyrics") ?: text(it, "plainLyrics") }
                if (lyrics == null && found.any { (it["instrumental"] as? JsonPrimitive)?.booleanOrNull == true }) instrumental = true
            }
        }
        if (s.genre == null) {
            val title = searchTitle(cleanTitle(s.title, s.artist))
            val want = comparableText(title)
            val r = http.send(HttpRequest("https://itunes.apple.com/search?term=${enc("$title $artist")}&media=music&entity=song&limit=8"))
            if (r.status == 200) {
                val results = ((runCatching { Json.parseToJsonElement(r.text) }.getOrNull() as? JsonObject)?.get("results") as? JsonArray)
                    ?.filterIsInstance<JsonObject>().orEmpty()
                genre = results.firstNotNullOfOrNull { o ->
                    fun text(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val ok = comparableText(searchTitle(text("trackName"))) == want &&
                        comparableText(text("artistName")).contains(comparableText(artist))
                    if (ok) text("primaryGenreName").takeIf { it.isNotBlank() } else null
                }
            }
        }
        driver.execute(
            null,
            "UPDATE songs SET mood_scanned_at = ?, lyrics = COALESCE(?, lyrics), " +
                "instrumental = CASE WHEN ? = 1 THEN 1 ELSE instrumental END, genre = COALESCE(genre, ?) WHERE unique_id = ?",
            5,
        ) {
            bindLong(0, clock())
            bindString(1, lyrics)
            bindLong(2, if (instrumental) 1 else 0)
            bindString(3, genre)
            bindString(4, s.unique_id)
        }
    }
}

/** The fits / does not fit votes behind mix tuning. */
object MoodVotes {
    /** Live votes (undone ones, 0, dropped) in the form [DailyMixes.build] takes. */
    fun verdicts(repo: LibraryRepo, songs: List<Song>): List<MoodVerdict> {
        val byPost = songs.associateBy { it.chat_id to it.message_id }
        return repo.db.miscQueries.moodVotes().executeAsList().mapNotNull { v ->
            val s = byPost[v.chat_id to v.message_id] ?: return@mapNotNull null
            if (v.vote == 0L) null else MoodVerdict(s.unique_id, s.artist, v.mood, v.vote.toInt())
        }
    }

    /** Records "does not fit" (-1), "fits" (1) or an undo (0) of [song] for [moodId]. */
    fun vote(repo: LibraryRepo, song: Song, moodId: String, vote: Int, now: Long) {
        repo.db.miscQueries.setMoodVote(MoodVote(song.chat_id, song.message_id, moodId, vote.toLong(), now))
    }

    /** Posts the person has marked as not fitting a mix. */
    fun rejected(repo: LibraryRepo, moodId: String): Set<Pair<Long, Long>> =
        repo.db.miscQueries.moodVotes().executeAsList()
            .filter { it.mood == moodId && it.vote < 0 }.mapTo(HashSet()) { it.chat_id to it.message_id }
}
