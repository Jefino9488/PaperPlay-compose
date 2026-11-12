package io.max.paperplay.core.metadata

import io.max.paperplay.core.integrations.HttpFetcher
import io.max.paperplay.core.integrations.HttpRequest
import io.max.paperplay.core.integrations.HttpResponse
import io.max.paperplay.core.integrations.urlWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/** What is known about the song or album a cover is wanted for. */
class CoverQuery(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSec: Int = 0,
    val releaseId: String? = null,
    val coverUrl: String? = null,
) {
    /** Search words: title and first artist, without credit tags. */
    val term: String
        get() {
            val t = searchTitle(cleanTitle(title, artist))
            val a = if (artist == "Unknown artist") "" else firstArtist(artist)
            return "$t $a".trim()
        }
}

/**
 * One place a cover can be fetched from. [urls] are tried in order (largest first) and the first
 * that is an image wins. [pixels] is the size the first url is expected to have, before the real
 * size is read from the image itself.
 */
class CoverCandidate(
    val urls: List<String>,
    val source: String,
    val label: String,
    val pixels: Int,
    /** A MusicBrainz release whose album-level (release group) art is the last resort. */
    val groupOfRelease: String? = null,
    val match: ExternalMatch? = null,
)

/** A downloaded cover with its real size. */
class CoverImage(val bytes: ByteArray, val url: String, val source: String, val width: Int, val height: Int, val match: ExternalMatch?) {
    val pixels: Int get() = width * height
}

/**
 * Every place a cover can come from, queried at the same time, with the largest picked:
 * Cover Art Archive (by release, then by the album's release group), the link already stored on the
 * song, Deezer (song, then album search) and iTunes (song, then album search, other storefronts).
 * Each service is paced on its own so a bulk fill stays inside their limits. Tags embedded in files and
 * Telegram thumbnails are handled by their own classes. Last.fm, Spotify, TheAudioDB and Fanart need
 * API keys the Flutter app never had, so they are not queried.
 */
class CoverSources(
    private val http: HttpFetcher,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val deezerGapMs: Long = 150L,
    private val itunesGapMs: Long = 3500L,
    private val mbGapMs: Long = 1100L,
    private val caaGapMs: Long = 100L,
    private val retryDelaysMs: List<Long> = listOf(1000L, 3000L),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val deezerPace = Pace(deezerGapMs)
    private val itunesPace = Pace(itunesGapMs)
    private val mbPace = Pace(mbGapMs)
    private val caaPace = Pace(caaGapMs)
    private val groups = HashMap<String, String?>()
    private val groupMutex = Mutex()
    private var itunesBlockedUntil = 0L

    /** Whether iTunes has asked us to back off. */
    val itunesBlocked: Boolean get() = clock() < itunesBlockedUntil

    private inner class Pace(private val gapMs: Long) {
        private val mutex = Mutex()
        private var nextAt = 0L
        suspend fun slot() {
            val wait = mutex.withLock {
                val now = clock()
                val at = maxOf(now, nextAt)
                nextAt = at + gapMs
                at - now
            }
            if (wait > 0) delay(wait)
        }
    }

    // Candidates

    /**
     * Everything that can be asked at once, asked at once. A source that fails adds nothing.
     * [lookups] false limits this to the song's own stored sources (no search services).
     */
    suspend fun candidates(q: CoverQuery, lookups: Boolean = true, album: Boolean = true, includeStored: Boolean = true): List<CoverCandidate> = coroutineScope {
        val jobs = buildList {
            if (includeStored) add(async { safely { stored(q) } })
            if (lookups && q.term.isNotBlank()) {
                add(async { safely { deezer(q, album) } })
                add(async { safely { itunes(q, album) } })
            }
        }
        jobs.awaitAll().flatten().distinctBy { it.urls.first() }
    }

    private suspend fun <T> safely(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyList()
    }

    /** Cover Art Archive for the release (largest size first), and the link already stored. */
    private fun stored(q: CoverQuery): List<CoverCandidate> = buildList {
        val rid = q.releaseId?.takeIf { it.isNotBlank() }
        if (rid != null) {
            add(
                CoverCandidate(
                    urls = listOf(
                        "https://coverartarchive.org/release/$rid/front-1200",
                        "https://coverartarchive.org/release/$rid/front-500",
                    ),
                    source = "Cover Art Archive", label = "MusicBrainz release", pixels = 1200, groupOfRelease = rid,
                ),
            )
        }
        val url = q.coverUrl?.takeIf { it.isNotBlank() }
        if (url != null) add(CoverCandidate(urls = upscaled(url), source = hostOf(url), label = "Saved link", pixels = declaredPixels(url)))
    }

    private suspend fun deezer(q: CoverQuery, album: Boolean): List<CoverCandidate> {
        deezerPace.slot()
        val res = get(urlWith("https://api.deezer.com/search", mapOf("q" to q.term, "limit" to "10")))
        val out = ArrayList<CoverCandidate>()
        if (res.status == 200) {
            val items = parse(res)?.objs("data") ?: emptyList()
            val picked = pickExternal(items.map { ExternalMatch.fromDeezer(it) }, q.title, q.artist, q.durationSec)
            picked?.coverUrl?.takeIf { it.isNotBlank() }?.let { out += candidate("Deezer", it, picked) }
        }
        if (out.isEmpty() && album && q.album.isNotBlank()) {
            deezerPace.slot()
            val a = get(urlWith("https://api.deezer.com/search/album", mapOf("q" to "${firstArtistOf(q)} ${q.album}".trim(), "limit" to "8")))
            if (a.status == 200) {
                val wantAlbum = comparableText(q.album)
                val wantArtist = comparableText(firstArtistOf(q))
                for (r in parse(a)?.objs("data") ?: emptyList()) {
                    val title = r.str("title") ?: continue
                    val artist = r.obj("artist")?.str("name") ?: ""
                    val cover = r.str("cover_xl") ?: r.str("cover_big") ?: continue
                    if (comparableText(title) == wantAlbum && (wantArtist.isEmpty() || comparableText(artist).contains(wantArtist))) {
                        out += candidate("Deezer", cover, ExternalMatch("Deezer", "", artist, title, 0, cover))
                        break
                    }
                }
            }
        }
        return out
    }

    private suspend fun itunes(q: CoverQuery, album: Boolean): List<CoverCandidate> {
        val extra = extraStorefronts(q.title, q.artist)
        val asian = extra.firstOrNull() in setOf("JP", "TW")
        val fronts: List<String?> = if (asian) extra + listOf<String?>(null) else listOf<String?>(null) + extra
        for (country in fronts) {
            if (itunesBlocked) return emptyList()
            itunesPace.slot()
            val params = buildMap {
                put("term", q.term); put("media", "music"); put("entity", "song"); put("limit", "10")
                if (country != null) put("country", country)
            }
            val res = get(urlWith("https://itunes.apple.com/search", params))
            if (res.status == 403 || res.status == 429) { itunesBlockedUntil = clock() + 30_000L; return emptyList() }
            if (res.status != 200) continue
            val items = parse(res)?.objs("results") ?: continue
            val picked = pickExternal(items.map { ExternalMatch.fromItunes(it) }, q.title, q.artist, q.durationSec)
            picked?.coverUrl?.takeIf { it.isNotBlank() }?.let { return listOf(candidate("iTunes", it, picked)) }
        }
        if (album && q.album.isNotBlank() && !itunesBlocked) {
            itunesPace.slot()
            val res = get(urlWith("https://itunes.apple.com/search", mapOf("term" to "${firstArtistOf(q)} ${q.album}".trim(), "media" to "music", "entity" to "album", "limit" to "8")))
            if (res.status == 403 || res.status == 429) { itunesBlockedUntil = clock() + 30_000L; return emptyList() }
            if (res.status == 200) {
                val wantAlbum = comparableText(q.album)
                val wantArtist = comparableText(firstArtistOf(q))
                for (r in parse(res)?.objs("results") ?: emptyList()) {
                    val name = r.str("collectionName") ?: continue
                    val artist = r.str("artistName") ?: ""
                    val art = r.str("artworkUrl100") ?: continue
                    if (comparableText(name) == wantAlbum && (wantArtist.isEmpty() || comparableText(artist).contains(wantArtist))) {
                        return listOf(candidate("iTunes", art, ExternalMatch("iTunes", "", artist, name, 0, art, r.str("primaryGenreName"), yearOf(r.str("releaseDate")))))
                    }
                }
            }
        }
        return emptyList()
    }

    private fun firstArtistOf(q: CoverQuery) = if (q.artist == "Unknown artist") "" else firstArtist(q.artist)

    private fun candidate(source: String, url: String, match: ExternalMatch) =
        CoverCandidate(urls = upscaled(url), source = source, label = listOf(match.album, match.artist).filter { it.isNotEmpty() }.joinToString(" - "), pixels = declaredPixels(upscaled(url).first()), match = match)

    // Choosing

    /** The largest cover among [candidates], all fetched at once; null when none is an image. */
    suspend fun best(candidates: List<CoverCandidate>): CoverImage? {
        if (candidates.isEmpty()) return null
        val images = coroutineScope { candidates.map { c -> async { safely { listOfNotNull(fetch(c)) } } }.awaitAll() }.flatten()
        return images.maxByOrNull { it.pixels }
    }

    /**
     * Quick path for bulk fills: the song's own stored sources first and, when one gives at least
     * [goodEnoughPx], nothing else is asked. Otherwise every source is asked at once and the largest wins.
     */
    suspend fun find(q: CoverQuery, lookups: Boolean = true, goodEnoughPx: Int = 500 * 500): CoverImage? {
        val own = safely { stored(q) }
        val first = if (own.isEmpty()) null else best(own)
        if (first != null && first.pixels >= goodEnoughPx) return first
        val rest = candidates(q, lookups).filter { c -> own.none { it.urls.first() == c.urls.first() } }
        val other = best(rest)
        return listOfNotNull(first, other).maxByOrNull { it.pixels }
    }

    /** Several covers for a manual picker: one per service, best first, links only. */
    suspend fun options(term: String): List<CoverOption> {
        val found = coroutineScope {
            val a = async { safely { deezerOptions(term) } }
            val b = async { safely { itunesOptions(term) } }
            a.await() + b.await()
        }
        val seen = HashSet<String>()
        return found.filter { seen.add(it.url) }
    }

    private suspend fun deezerOptions(term: String): List<CoverOption> {
        deezerPace.slot()
        val res = get(urlWith("https://api.deezer.com/search", mapOf("q" to term, "limit" to "8")))
        if (res.status != 200) return emptyList()
        return (parse(res)?.objs("data") ?: emptyList()).map { ExternalMatch.fromDeezer(it) }.mapNotNull { m ->
            m.coverUrl?.takeIf { it.isNotBlank() }?.let { CoverOption(it, listOf(m.album, m.artist).filter { s -> s.isNotEmpty() }.joinToString(" - ")) }
        }
    }

    private suspend fun itunesOptions(term: String): List<CoverOption> {
        if (itunesBlocked) return emptyList()
        itunesPace.slot()
        val res = get(urlWith("https://itunes.apple.com/search", mapOf("term" to term, "media" to "music", "entity" to "song", "limit" to "8")))
        if (res.status == 403 || res.status == 429) { itunesBlockedUntil = clock() + 30_000L; return emptyList() }
        if (res.status != 200) return emptyList()
        return (parse(res)?.objs("results") ?: emptyList()).map { ExternalMatch.fromItunes(it) }.mapNotNull { m ->
            m.coverUrl?.takeIf { it.isNotBlank() }?.let { upscaled(it).first() }?.let { CoverOption(it, listOf(m.album, m.artist).filter { s -> s.isNotEmpty() }.joinToString(" - ")) }
        }
    }

    // Fetching

    /** The first of the candidate's links that gives an image; the album's art as a last resort. */
    suspend fun fetch(c: CoverCandidate): CoverImage? {
        for (u in c.urls) fetchImage(u)?.let { return toImage(it, u, c) }
        val rid = c.groupOfRelease ?: return null
        val group = releaseGroup(rid) ?: return null
        for (u in listOf("front-1200", "front-500")) {
            val url = "https://coverartarchive.org/release-group/$group/$u"
            fetchImage(url)?.let { return toImage(it, url, c) }
        }
        return null
    }

    private fun toImage(bytes: ByteArray, url: String, c: CoverCandidate): CoverImage {
        val (w, h) = imageSize(bytes) ?: (0 to 0)
        // An unreadable header still counts, at the size the source promised.
        val side = if (w > 0) 0 else kotlin.math.sqrt(c.pixels.toDouble()).toInt()
        return CoverImage(bytes, url, c.source, if (w > 0) w else side, if (h > 0) h else side, c.match)
    }

    /** One url's bytes if it is an image. A busy answer (429, 5xx) is asked again after short waits. */
    suspend fun fetchImage(url: String): ByteArray? {
        var attempt = 0
        while (true) {
            if (hostOf(url).endsWith("coverartarchive.org")) caaPace.slot()
            val res = try { get(url) } catch (e: CancellationException) { throw e } catch (_: Throwable) { return null }
            if (res.status == 200) return res.body.takeIf { looksLikeImage(it) }
            if ((res.status == 429 || res.status >= 500) && attempt < retryDelaysMs.size) { delay(retryDelaysMs[attempt++]); continue }
            return null
        }
    }

    /** The album a release belongs to; Cover Art Archive often has art for it when the release has none. */
    suspend fun releaseGroup(releaseId: String): String? {
        groupMutex.withLock { if (groups.containsKey(releaseId)) return groups[releaseId] }
        mbPace.slot()
        val res = get(urlWith("https://musicbrainz.org/ws/2/release/$releaseId", mapOf("inc" to "release-groups", "fmt" to "json")))
        if (res.status == 503 || res.status == 429) return null
        val group = if (res.status == 200) parse(res)?.let { releaseGroupOf(it) } else null
        groupMutex.withLock { groups[releaseId] = group }
        return group
    }

    private suspend fun get(url: String): HttpResponse = http.send(HttpRequest(url, headers = mapOf("User-Agent" to userAgent())))

    private fun parse(res: HttpResponse): JsonObject? = runCatching { json.parseToJsonElement(res.text) as? JsonObject }.getOrNull()

    companion object {
        /** Links for the same picture from the largest size down. iTunes and Deezer serve any size asked for. */
        fun upscaled(url: String): List<String> {
            val itunes = Regex("/(\\d+)x(\\d+)(bb|-\\d+)?\\.(jpg|png)$").find(url)
            if (itunes != null && hostOf(url).contains("mzstatic.com")) {
                val big = url.replaceRange(itunes.range, "/1400x1400bb.${itunes.groupValues[4]}")
                val mid = url.replaceRange(itunes.range, "/600x600bb.${itunes.groupValues[4]}")
                return listOf(big, mid, url).distinct()
            }
            if (hostOf(url).contains("dzcdn.net") || hostOf(url).contains("deezer")) {
                val big = url.replace(Regex("/(\\d+)x\\1-"), "/1000x1000-")
                return listOf(big, url).distinct()
            }
            if (url.contains("coverartarchive.org") && url.endsWith("front-500")) return listOf(url.removeSuffix("500") + "1200", url)
            return listOf(url)
        }

        /** The size a link asks for, as far as the link says. */
        fun declaredPixels(url: String): Int {
            val m = Regex("/(\\d{2,4})x(\\d{2,4})").find(url)
            if (m != null) return m.groupValues[1].toInt() * m.groupValues[2].toInt()
            val caa = Regex("front-(\\d+)").find(url)
            if (caa != null) return caa.groupValues[1].toInt().let { it * it }
            if (url.contains("cover_xl")) return 1000 * 1000
            return 500 * 500
        }

        /** The same album across songs: artist and album with case and punctuation ignored. Blank album gives null. */
        fun albumKey(album: String, artist: String): String? {
            val a = comparableText(album)
            if (a.isEmpty()) return null
            val ar = if (artist == "Unknown artist") "" else comparableText(firstArtist(artist))
            return "$ar|$a"
        }

        private fun looksLikeImage(b: ByteArray): Boolean {
            if (b.size < 100) return false
            val b0 = b[0].toInt() and 0xFF; val b1 = b[1].toInt() and 0xFF; val b2 = b[2].toInt() and 0xFF; val b3 = b[3].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xD8) return true
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true
            if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return true
            return b.size >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte()
        }

        private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
        private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
        private fun be32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
        private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
        private fun le24(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)

        /** Width and height read from a PNG, JPEG, GIF or WebP header; null when unreadable. */
        fun imageSize(b: ByteArray): Pair<Int, Int>? {
            if (b.size < 24) return null
            try {
                if (u8(b, 0) == 0x89 && u8(b, 1) == 0x50) return be32(b, 16) to be32(b, 20)
                if (u8(b, 0) == 0x47 && u8(b, 1) == 0x49) return le16(b, 6) to le16(b, 8)
                if (u8(b, 0) == 0xFF && u8(b, 1) == 0xD8) {
                    var i = 2
                    while (i + 9 < b.size) {
                        if (u8(b, i) != 0xFF) { i++; continue }
                        val m = u8(b, i + 1)
                        if (m == 0xFF) { i++; continue }
                        if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return be16(b, i + 7) to be16(b, i + 5)
                        if (m == 0xD8 || m in 0xD0..0xD7 || m == 0x01) { i += 2; continue }
                        i += 2 + be16(b, i + 2)
                    }
                    return null
                }
                if (u8(b, 0) == 0x52 && b.size >= 30) {
                    return when (b.decodeToString(12, 16)) {
                        "VP8X" -> (le24(b, 24) + 1) to (le24(b, 27) + 1)
                        "VP8L" -> {
                            val v = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
                            ((v and 0x3FFF) + 1) to (((v shr 14) and 0x3FFF) + 1)
                        }
                        "VP8 " -> (le16(b, 26) and 0x3FFF) to (le16(b, 28) and 0x3FFF)
                        else -> null
                    }
                }
            } catch (_: IndexOutOfBoundsException) {
            }
            return null
        }
    }
}
