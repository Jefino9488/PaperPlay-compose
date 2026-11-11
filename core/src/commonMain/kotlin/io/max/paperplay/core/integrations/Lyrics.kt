package io.max.paperplay.core.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private fun enc(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt().toChar()
        if (c.isLetterOrDigit() && b >= 0 || c in "-._~") append(c) else append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
    }
}

/**
 * Looks a song's lyrics up on LRCLIB, which needs no key. Timed lyrics (`[mm:ss.xx] line`) win over plain text.
 * Null when there is none. Only the title, artist, album and length are sent. Throws on a network failure.
 */
suspend fun fetchLyrics(http: HttpFetcher, title: String, artist: String, album: String, durationSec: Int): String? {
    val q = buildString {
        append("track_name=").append(enc(title)).append("&artist_name=").append(enc(artist))
        if (album.isNotBlank()) append("&album_name=").append(enc(album))
        if (durationSec > 0) append("&duration=").append(durationSec)
    }
    val r = http.send(HttpRequest("https://lrclib.net/api/get?$q"))
    if (r.status != 200) return null
    val o = runCatching { Json.parseToJsonElement(r.text) as? JsonObject }.getOrNull() ?: return null
    fun text(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    return text("syncedLyrics") ?: text("plainLyrics")
}
