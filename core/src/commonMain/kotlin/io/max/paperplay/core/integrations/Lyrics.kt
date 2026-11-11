package io.max.paperplay.core.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

private fun enc(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt().toChar()
        if (c.isLetterOrDigit() && b >= 0 || c in "-._~") append(c) else append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
    }
}

private fun lyricsOf(o: JsonObject): String? {
    fun text(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    return text("syncedLyrics") ?: text("plainLyrics")
}

/**
 * Looks a song's lyrics up on LRCLIB, which needs no key. Timed lyrics (`[mm:ss.xx] line`) win over plain text.
 * An exact match (needs album and length) is tried first, then a search by title and artist accepting a result
 * within 5 seconds of the song's length. Null when there is none. Only the title, artist, album and length are
 * sent. Throws on a network failure.
 */
suspend fun fetchLyrics(http: HttpFetcher, title: String, artist: String, album: String, durationSec: Int): String? {
    if (album.isNotBlank() && durationSec > 0) {
        val q = "track_name=${enc(title)}&artist_name=${enc(artist)}&album_name=${enc(album)}&duration=$durationSec"
        val r = http.send(HttpRequest("https://lrclib.net/api/get?$q"))
        if (r.status == 200) {
            val o = runCatching { Json.parseToJsonElement(r.text) as? JsonObject }.getOrNull()
            o?.let { lyricsOf(it) }?.let { return it }
        }
    }
    val r = http.send(HttpRequest("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}"))
    if (r.status != 200) return null
    val rows = runCatching { Json.parseToJsonElement(r.text) as? JsonArray }.getOrNull() ?: return null
    for (e in rows) {
        val o = e as? JsonObject ?: continue
        val d = (o["duration"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        if (durationSec > 0 && kotlin.math.abs(d - durationSec) > 5) continue
        lyricsOf(o)?.let { return it }
    }
    return null
}
