package io.max.paperplay.core.app

import io.max.paperplay.core.backup.Fs
import io.max.paperplay.core.integrations.HttpFetcher
import io.max.paperplay.core.integrations.HttpRequest
import io.max.paperplay.core.integrations.HttpResponse
import io.max.paperplay.core.library.EnrichState
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.library.laterStamp
import io.max.paperplay.core.metadata.CoverOption
import io.max.paperplay.core.metadata.ExternalMatch
import io.max.paperplay.core.metadata.MbMatch
import io.max.paperplay.core.metadata.MetadataOption
import io.max.paperplay.core.metadata.busyDelay
import io.max.paperplay.core.metadata.cleanTitle
import io.max.paperplay.core.metadata.firstArtist
import io.max.paperplay.core.metadata.httpsUrl
import io.max.paperplay.core.metadata.musicBrainzQuery
import io.max.paperplay.core.metadata.pickExternal
import io.max.paperplay.core.metadata.pickMatch
import io.max.paperplay.core.metadata.releaseGroupOf
import io.max.paperplay.core.metadata.searchTitle
import io.max.paperplay.core.metadata.userAgent
import io.max.paperplay.core.metadata.extraStorefronts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/** A service answered "busy" (503 or 429); ask again later. */
private class ServiceBusy : Exception()

/**
 * Looks songs up online and writes what it finds back to the library: MusicBrainz first, then
 * Deezer and iTunes when MusicBrainz has no safe match. Also backs the manual picker (search,
 * apply a result, set a cover). Covers themselves are fetched by [CoverService].
 */
class MetadataEnricher(
    private val dataDir: String,
    private val repo: LibraryRepository,
    private val http: HttpFetcher,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val mbIntervalMs: Long = 1100L,
    private val itunesIntervalMs: Long = 3500L,
    private val retryAfterMs: Long = 60_000L,
    private val maxBusyRetries: Int = 6,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var worker: Job? = null
    private var retryJob: Job? = null
    @Volatile private var stopped = false
    @Volatile private var nextMbAt = 0L
    @Volatile private var nextItunesAt = 0L
    private val groups = HashMap<String, String?>()

    private val _running = MutableStateFlow(false)

    /** True while the song-info run is going. */
    val running: StateFlow<Boolean> = _running

    private val _failure = MutableStateFlow<String?>(null)

    /** Why the last run stopped early (offline, service busy); null otherwise. It retries by itself. */
    val failure: StateFlow<String?> = _failure

    /** Songs not yet looked up. */
    fun waiting(): Int = repo.repo.db.songsQueries.pendingToEnrich(0).executeAsList().size

    /** Ends the run after the song being looked up. [kick] starts it again. */
    fun stop() {
        stopped = true
        retryJob?.cancel()
    }

    /** Looks up pending songs one by one. Safe to call repeatedly; stops on a network failure and tries again later. */
    fun kick() {
        if (worker?.isActive == true) return
        stopped = false
        retryJob?.cancel()
        worker = scope.launch {
            _running.value = true
            try {
                runPending()
            } finally {
                _running.value = false
            }
        }
    }

    private suspend fun runPending() {
        var busy = 0
        while (!stopped) {
            val song = repo.nextToEnrich() ?: run { _failure.value = null; return }
            try {
                enrich(song)
                busy = 0
                _failure.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServiceBusy) {
                if (busy >= maxBusyRetries) return giveUp("A lookup service is busy")
                delay(busyDelay(busy++))
            } catch (e: Exception) {
                return giveUp("No connection")
            }
        }
    }

    private fun giveUp(why: String) {
        _failure.value = why
        if (stopped) return
        retryJob = scope.launch {
            delay(retryAfterMs)
            kick()
        }
    }

    /** Looks one song up and writes the result. Throws when nothing could be asked, so it is asked again. */
    suspend fun enrich(song: Song) {
        val fresh = repo.songByUniqueId(song.unique_id) ?: return
        if (fresh.enrich_state != EnrichState.PENDING) return
        if (fresh.user_edited) {
            repo.replaceSong(fresh.copy(enrich_state = EnrichState.DONE))
            return
        }
        val (match, external) = coroutineScope {
            val ext = async { runCatching { externalMatch(fresh) }.getOrNull() }
            val mb = search(fresh.title, fresh.artist)
            pickMatch(mb, fresh.duration_sec.toInt()) to ext.await()
        }
        val now = repo.songByUniqueId(song.unique_id) ?: return
        if (now.user_edited) return
        val stamp = laterStamp(clock(), now.tags_at)
        val out = when {
            match != null -> now.copy(
                title = match.title, artist = match.artist, album = match.album, release_id = match.releaseId,
                year = match.year?.toLong(), metadata_source = "MusicBrainz", enrich_state = EnrichState.DONE, tags_at = stamp,
            )
            external != null -> now.copy(
                title = external.title, artist = external.artist, album = external.album,
                cover_url = external.coverUrl ?: now.cover_url, genre = external.genre ?: now.genre,
                year = external.year?.toLong() ?: now.year, metadata_source = external.source,
                enrich_state = EnrichState.DONE, tags_at = stamp,
            )
            else -> now.copy(title = cleanTitle(now.title, now.artist), enrich_state = EnrichState.NO_MATCH)
        }
        repo.replaceSong(out)
    }

    /** Puts every unmatched song back in the queue and starts the run. Returns how many. */
    fun retryUnmatched(): Int {
        val list = repo.unmatchedSongs()
        list.forEach { repo.replaceSong(it.copy(enrich_state = EnrichState.PENDING)) }
        if (list.isNotEmpty()) kick()
        return list.size
    }

    /** Looks these songs up again, even if they matched before. Edited songs are left alone. */
    fun retrySongs(songs: List<Song>): Int {
        var n = 0
        for (s in songs) {
            val row = repo.songByUniqueId(s.unique_id) ?: continue
            if (row.user_edited) continue
            repo.replaceSong(row.copy(enrich_state = EnrichState.PENDING))
            n++
        }
        if (n > 0) kick()
        return n
    }

    // Searching

    /** Candidates from MusicBrainz for a title and artist, best first. */
    suspend fun search(title: String, artist: String): List<MbMatch> {
        fun quoted(s: String) = "\"" + s.replace(Regex("[\"\\\\]"), " ") + "\""
        val recording = quoted(searchTitle(cleanTitle(title, artist)))
        val query = if (artist == "Unknown artist") "recording:$recording"
        else "recording:$recording AND artist:${quoted(firstArtist(artist))}"
        val res = mbGet(httpsUrl("musicbrainz.org", "/ws/2/recording", mapOf("query" to query, "fmt" to "json", "limit" to "8")))
        if (res.status == 503 || res.status == 429) throw ServiceBusy()
        if (res.status != 200) return emptyList()
        return objects(parse(res), "recordings").map { MbMatch.fromJson(it) }
    }

    /**
     * Searches every service for whatever was typed. A service that fails adds nothing. Results
     * whose length matches [durationSec] come first when it is known.
     */
    suspend fun searchOnline(term: String, durationSec: Int = 0): List<MetadataOption> {
        val words = musicBrainzQuery(term)
        if (words.isEmpty()) return emptyList()
        val all = coroutineScope {
            val mb = async {
                runCatching {
                    val res = mbGet(httpsUrl("musicbrainz.org", "/ws/2/recording", mapOf("query" to words, "fmt" to "json", "limit" to "10")))
                    if (res.status != 200) emptyList()
                    else objects(parse(res), "recordings").map { MetadataOption.fromMusicBrainz(MbMatch.fromJson(it)) }
                }.getOrDefault(emptyList())
            }
            val dz = async { runCatching { deezer(term).map { MetadataOption.fromExternal(it) } }.getOrDefault(emptyList()) }
            val tn = async { runCatching { itunes(term).map { MetadataOption.fromExternal(it) } }.getOrDefault(emptyList()) }
            mb.await() + dz.await() + tn.await()
        }
        if (durationSec <= 0) return all
        val (near, far) = all.partition { o -> (o.lengthSec ?: Int.MAX_VALUE).let { l -> kotlin.math.abs(l - durationSec) <= 5 } }
        return near + far
    }

    /** Covers from Deezer and iTunes for a search. A failed service gives none. */
    suspend fun coverOptions(term: String): List<CoverOption> {
        val all = coroutineScope {
            val a = async { runCatching { deezer(term) }.getOrDefault(emptyList()) }
            val b = async { runCatching { itunes(term) }.getOrDefault(emptyList()) }
            a.await() + b.await()
        }
        val seen = HashSet<String>()
        return all.filter { !it.coverUrl.isNullOrBlank() && seen.add(it.coverUrl) }
            .map { CoverOption(it.coverUrl!!, listOf(it.album, it.artist).filter { s -> s.isNotEmpty() }.joinToString(" - ")) }
    }

    // Applying a pick

    /**
     * Writes a picked result to [song] as the owner's own edit, then sets its cover from the
     * release or the result's cover link. False when no cover could be fetched (the text is still saved).
     */
    suspend fun applyOption(song: Song, m: MetadataOption): Boolean {
        val row = repo.songByUniqueId(song.unique_id) ?: return false
        repo.replaceSong(
            row.copy(
                title = m.title, artist = m.artist, album = m.album, release_id = m.releaseId, cover_url = m.coverUrl,
                year = m.year?.toLong(), genre = m.genre ?: row.genre, metadata_source = m.source,
                user_edited = true, enrich_state = EnrichState.DONE, tags_at = laterStamp(clock(), row.tags_at),
            ),
        )
        val bytes = runCatching { archiveCover(m.releaseId) ?: m.coverUrl?.let { image(it) } }.getOrNull() ?: return false
        setCoverBytes(listOf(song), bytes)
        return true
    }

    /** Sets a cover from [url] on [songs]. False if it could not be downloaded. */
    suspend fun setCoverFromUrl(songs: List<Song>, url: String): Boolean {
        val bytes = runCatching { image(url) }.getOrNull() ?: return false
        setCoverBytes(songs, bytes)
        return true
    }

    /** Sets a cover from image bytes on [songs]. Each song gets its own file and is marked as chosen. */
    fun setCoverBytes(songs: List<Song>, bytes: ByteArray) {
        val at = clock()
        val dir = "${dataDir.trimEnd('/')}/covers"
        Fs.mkdirs(dir)
        for (s in songs) {
            val row = repo.songByUniqueId(s.unique_id) ?: continue
            val safe = row.unique_id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val path = "$dir/${safe}_$at.jpg"
            Fs.write(path, bytes)
            repo.replaceSong(row.copy(cover_path = path, cover_hi_res = true, cover_at = laterStamp(at, row.cover_at)))
        }
    }

    // Services

    private suspend fun externalMatch(s: Song): ExternalMatch? {
        val term = "${searchTitle(cleanTitle(s.title, s.artist))} ${firstArtist(s.artist)}"
        val dur = s.duration_sec.toInt()
        val (dz, it) = coroutineScope {
            val a = async { runCatching { deezer(term) }.getOrDefault(emptyList()) }
            val b = async { runCatching { itunes(term) }.getOrDefault(emptyList()) }
            a.await() to b.await()
        }
        pickExternal(dz, s.title, s.artist, dur)?.let { return it }
        pickExternal(it, s.title, s.artist, dur)?.let { return it }
        for (country in extraStorefronts(s.title, s.artist)) {
            val more = runCatching { itunes(term, country) }.getOrDefault(emptyList())
            pickExternal(more, s.title, s.artist, dur)?.let { return it }
        }
        return null
    }

    private suspend fun deezer(term: String): List<ExternalMatch> {
        val res = get(httpsUrl("api.deezer.com", "/search", mapOf("q" to term, "limit" to "10")))
        if (res.status != 200) return emptyList()
        return objects(parse(res), "data").map { ExternalMatch.fromDeezer(it) }
    }

    private suspend fun itunes(term: String, country: String? = null): List<ExternalMatch> {
        val wait = reserve(itunesIntervalMs, true)
        if (wait > 0) delay(wait)
        val res = get(httpsUrl("itunes.apple.com", "/search", mapOf("term" to term, "entity" to "song", "limit" to "10", "country" to country)))
        if (res.status != 200) return emptyList()
        return objects(parse(res), "results").map { ExternalMatch.fromItunes(it) }
    }

    /** MusicBrainz allows one request a second, so calls are spaced. */
    private suspend fun mbGet(url: String): HttpResponse {
        val wait = reserve(mbIntervalMs, false)
        if (wait > 0) delay(wait)
        return get(url)
    }

    private fun reserve(interval: Long, itunes: Boolean): Long {
        val now = clock()
        val at = maxOf(now, if (itunes) nextItunesAt else nextMbAt)
        if (itunes) nextItunesAt = at + interval else nextMbAt = at + interval
        return at - now
    }

    private suspend fun get(url: String): HttpResponse =
        http.send(HttpRequest(url, headers = mapOf("User-Agent" to userAgent())))

    /** An image's bytes, or null when the link has none. A busy answer is tried once more. */
    private suspend fun image(url: String): ByteArray? {
        var res = get(url)
        if (res.status == 429 || res.status >= 500) { delay(2000); res = get(url) }
        return res.body.takeIf { res.status == 200 && it.size > 100 }
    }

    /** The release's front cover, else that of another release of the same album. */
    private suspend fun archiveCover(releaseId: String?): ByteArray? {
        if (releaseId == null) return null
        image("https://coverartarchive.org/release/$releaseId/front-500")?.let { return it }
        val group = releaseGroup(releaseId) ?: return null
        return image("https://coverartarchive.org/release-group/$group/front-500")
    }

    private suspend fun releaseGroup(releaseId: String): String? {
        if (groups.containsKey(releaseId)) return groups[releaseId]
        val res = mbGet(httpsUrl("musicbrainz.org", "/ws/2/release/$releaseId", mapOf("inc" to "release-groups", "fmt" to "json")))
        if (res.status != 200) return null
        val root = parse(res) ?: return null
        return releaseGroupOf(root).also { groups[releaseId] = it }
    }

    private fun parse(res: HttpResponse): JsonObject? =
        runCatching { json.parseToJsonElement(res.text) as? JsonObject }.getOrNull()

    private fun objects(root: JsonObject?, key: String): List<JsonObject> =
        (root?.get(key) as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
}
