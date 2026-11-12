package io.max.paperplay.core.integrations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.TimeSource

private fun enc(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt().toChar()
        if (c.isLetterOrDigit() && b >= 0 || c in "-._~") append(c) else append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
    }
}

/** What is known about the song being looked up. */
class LyricsQuery(val title: String, val artist: String, val album: String, val durationSec: Int) {
    /** Title without "(feat. x)", "[Live]", " - Remastered 2011" and the like. */
    val cleanTitle: String = title
        .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), " ")
        .replace(Regex("""\s+-\s+(remaster(ed)?|live|mono|stereo|single|radio|version|edit|\d{4}).*""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\s+"""), " ").trim().ifEmpty { title.trim() }

    /** First credited artist. */
    val mainArtist: String = artist.split(Regex("""\s*(,|&|;| feat\.? | ft\.? | x | and | with )\s*""", RegexOption.IGNORE_CASE))
        .firstOrNull()?.trim().orEmpty().ifEmpty { artist.trim() }

    val keyword: String get() = "$cleanTitle $mainArtist".trim()
    val cacheKey: String get() = "${norm(cleanTitle)}|${norm(mainArtist)}|${durationSec / 5}"
}

private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

private fun titleOk(wanted: String, got: String): Boolean {
    val a = norm(wanted)
    val b = norm(got)
    return a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))
}

private fun artistOk(wanted: String, got: String): Boolean {
    val a = norm(wanted)
    val b = norm(got)
    return a.isEmpty() || b.isEmpty() || a.contains(b) || b.contains(a)
}

private val timedLine = Regex("""\[\d{1,3}:\d{2}""")

/** True when the text has `[mm:ss` time tags. */
fun isSyncedLyrics(text: String) = timedLine.containsMatchIn(text)

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.arr() = this as? JsonArray
private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull

private fun unescapeHtml(s: String): String =
    Regex("""&#(\d+);""").replace(s) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value }
        .replace("&apos;", "'").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

@OptIn(ExperimentalEncodingApi::class)
private fun b64(s: String): String? = runCatching { Base64.Default.decode(s.trim()).decodeToString() }.getOrNull()

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) PaperPlay/0.1"

private suspend fun HttpFetcher.getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement? {
    val r = send(HttpRequest(url, headers = mapOf("user-agent" to UA) + headers))
    return if (r.status == 200) runCatching { Json.parseToJsonElement(r.text) }.getOrNull() else null
}

/** Spaces out requests to one service: starts are at least [minIntervalMs] apart, however many callers wait. */
class RateGate(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private val base = TimeSource.Monotonic.markNow()
    private var next = 0L

    suspend fun wait() {
        val pause = mutex.withLock {
            val now = base.elapsedNow().inWholeMilliseconds
            val at = maxOf(now, next)
            next = at + minIntervalMs
            at - now
        }
        if (pause > 0) delay(pause)
    }
}

/** One lyrics service. [fetch] returns lyrics (timed or plain) or null; it throws on a network failure. */
class LyricsSource(
    val name: String,
    minIntervalMs: Long,
    val timeoutMs: Long,
    val fetch: suspend (HttpFetcher, LyricsQuery) -> String?,
) {
    val gate = RateGate(minIntervalMs)
}

/** Nothing answered: every source failed on the network. The caller may retry later. */
class LyricsUnavailable : Exception("No lyrics source answered")

// ---- sources (all keyless) ----

/** LRCLIB: exact match when album and length are known, then a search accepting a length within 5 seconds. */
val LrclibSource = LyricsSource("LRCLIB", 150, 10_000) { http, q ->
    fun lyricsOf(o: JsonObject): String? {
        fun text(k: String) = o[k].str()?.takeIf { it.isNotBlank() }
        return text("syncedLyrics") ?: text("plainLyrics")
    }
    if (q.album.isNotBlank() && q.durationSec > 0) {
        val u = "https://lrclib.net/api/get?track_name=${enc(q.title)}&artist_name=${enc(q.artist)}&album_name=${enc(q.album)}&duration=${q.durationSec}"
        http.getJson(u).obj()?.let { lyricsOf(it) }?.let { return@LyricsSource it }
    }
    val rows = http.getJson("https://lrclib.net/api/search?track_name=${enc(q.title)}&artist_name=${enc(q.artist)}").arr()
        ?: http.getJson("https://lrclib.net/api/search?q=${enc(q.keyword)}").arr()
    var plain: String? = null
    for (e in rows.orEmpty()) {
        val o = e.obj() ?: continue
        val d = o["duration"].num() ?: 0.0
        if (q.durationSec > 0 && d > 0 && kotlin.math.abs(d - q.durationSec) > 5) continue
        val t = lyricsOf(o) ?: continue
        if (isSyncedLyrics(t)) return@LyricsSource t
        if (plain == null) plain = t
    }
    plain
}

/** NetEase Cloud Music: search, then the song's lyric (timed). */
val NeteaseSource = LyricsSource("NetEase", 400, 10_000) { http, q ->
    val h = mapOf("referer" to "https://music.163.com/")
    val songs = http.getJson("https://music.163.com/api/search/get?s=${enc(q.keyword)}&type=1&limit=6&offset=0", h)
        .obj()?.get("result").obj()?.get("songs").arr() ?: return@LyricsSource null
    for (e in songs) {
        val o = e.obj() ?: continue
        if (!titleOk(q.cleanTitle, o["name"].str().orEmpty())) continue
        val d = (o["duration"].num() ?: 0.0) / 1000
        if (q.durationSec > 0 && d > 0 && kotlin.math.abs(d - q.durationSec) > 6) continue
        val artists = o["artists"].arr().orEmpty().joinToString(" ") { it.obj()?.get("name").str().orEmpty() }
        if (!artistOk(q.mainArtist, artists)) continue
        val id = o["id"].num()?.toLong() ?: continue
        val lrc = http.getJson("https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1", h)
            .obj()?.get("lrc").obj()?.get("lyric").str()?.takeIf { it.isNotBlank() }
        if (lrc != null) return@LyricsSource lrc
    }
    null
}

/** Kugou: song search for a hash, lyric candidates for it, then the base64 download (timed). */
val KugouSource = LyricsSource("Kugou", 500, 12_000) { http, q ->
    val songs = http.getJson("https://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=${enc(q.keyword)}&page=1&pagesize=6")
        .obj()?.get("data").obj()?.get("info").arr() ?: return@LyricsSource null
    for (e in songs) {
        val o = e.obj() ?: continue
        if (!titleOk(q.cleanTitle, o["songname"].str().orEmpty())) continue
        val d = o["duration"].num() ?: 0.0
        if (q.durationSec > 0 && d > 0 && kotlin.math.abs(d - q.durationSec) > 6) continue
        if (!artistOk(q.mainArtist, o["singername"].str().orEmpty())) continue
        val hash = o["hash"].str() ?: continue
        val ms = if (d > 0) (d * 1000).toLong() else q.durationSec * 1000L
        val cands = http.getJson("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(q.keyword)}&duration=$ms&hash=$hash")
            .obj()?.get("candidates").arr() ?: continue
        val c = cands.firstOrNull().obj() ?: continue
        val id = c["id"].str() ?: continue
        val key = c["accesskey"].str() ?: continue
        val content = http.getJson("https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$key&fmt=lrc&charset=utf8")
            .obj()?.get("content").str() ?: continue
        b64(content)?.removePrefix("﻿")?.takeIf { it.isNotBlank() }?.let { return@LyricsSource it }
    }
    null
}

/** QQ Music: search for a song mid, then its lyric (timed; plain or base64 depending on the reply). */
val QqSource = LyricsSource("QQ Music", 500, 12_000) { http, q ->
    val h = mapOf("referer" to "https://y.qq.com/")
    val songs = http.getJson("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${enc(q.keyword)}&format=json&p=1&n=6&t=0", h)
        .obj()?.get("data").obj()?.get("song").obj()?.get("list").arr() ?: return@LyricsSource null
    for (e in songs) {
        val o = e.obj() ?: continue
        if (!titleOk(q.cleanTitle, o["songname"].str().orEmpty())) continue
        val d = o["interval"].num() ?: 0.0
        if (q.durationSec > 0 && d > 0 && kotlin.math.abs(d - q.durationSec) > 6) continue
        val singers = o["singer"].arr().orEmpty().joinToString(" ") { it.obj()?.get("name").str().orEmpty() }
        if (!artistOk(q.mainArtist, singers)) continue
        val mid = o["songmid"].str() ?: continue
        val raw = http.getJson("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json&nobase64=1&g_tk=5381", h)
            .obj()?.get("lyric").str()?.takeIf { it.isNotBlank() } ?: continue
        val text = if (raw.contains('[')) raw else b64(raw) ?: continue
        unescapeHtml(text).takeIf { it.isNotBlank() }?.let { return@LyricsSource it }
    }
    null
}

/** lyrics.ovh: plain text only, so it ranks last. */
val OvhSource = LyricsSource("lyrics.ovh", 800, 8_000) { http, q ->
    if (q.mainArtist.isBlank()) return@LyricsSource null
    http.getJson("https://api.lyrics.ovh/v1/${enc(q.mainArtist)}/${enc(q.cleanTitle)}")
        .obj()?.get("lyrics").str()?.trim()?.takeIf { it.length > 40 }
}

// Genius and Musixmatch are not ported: both need a personal API token (Genius also only gives page URLs), so
// they wait until the app has somewhere to keep such a key. The Flutter app itself only ever used LRCLIB.

/** Fallback order: timed sources first, plain-only last. */
val defaultLyricsSources: List<LyricsSource> = listOf(LrclibSource, NeteaseSource, KugouSource, QqSource, OvhSource)

// ---- cache ----

private object LyricsCache {
    private val lock = Mutex()
    private val map = LinkedHashMap<String, String>() // "" means looked up, nothing found
    suspend fun get(k: String): String? = lock.withLock { map[k] }
    suspend fun put(k: String, v: String?) = lock.withLock {
        map[k] = v.orEmpty()
        if (map.size > 2000) map.remove(map.keys.first())
    }
}

/**
 * Looks a song's lyrics up on every source at once (each behind its own rate gate and timeout) and returns the
 * best by source order: the first timed result, else the first plain one. Results and misses are cached for the
 * session. Null when no source has it. Throws [LyricsUnavailable] only when every source failed on the network.
 * Only the title, artist, album and length are sent.
 */
suspend fun fetchLyrics(
    http: HttpFetcher,
    title: String,
    artist: String,
    album: String,
    durationSec: Int,
    sources: List<LyricsSource> = defaultLyricsSources,
): String? {
    val q = LyricsQuery(title, artist, album, durationSec)
    LyricsCache.get(q.cacheKey)?.let { return it.ifEmpty { null } }
    var failures = 0
    var answered = 0
    var best: String? = null
    coroutineScope {
        val jobs: List<Deferred<Result<String?>>> = sources.map { s ->
            async {
                try {
                    s.gate.wait()
                    Result.success(withTimeoutOrNull(s.timeoutMs) { s.fetch(http, q) })
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                    Result.failure(e)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }
        var plain: String? = null
        for (j in jobs) {
            val r = j.await()
            if (r.isFailure) { failures++; continue }
            answered++
            val t = r.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            if (isSyncedLyrics(t)) { best = t; break }
            if (plain == null) plain = t
        }
        if (best == null) best = plain
        jobs.forEach { it.cancel() }
    }
    if (best == null && answered == 0 && failures > 0) throw LyricsUnavailable()
    LyricsCache.put(q.cacheKey, best)
    return best
}
