package io.max.paperplay.core.integrations

import io.max.paperplay.core.library.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Sends finished listens to ListenBrainz with the owner's user token. */
class ListenBrainz(private val http: HttpFetcher) {
    private fun auth(token: String) = mapOf("Authorization" to "Token $token")

    /** The user name the token belongs to, or null when ListenBrainz does not accept it. Throws on a network failure. */
    suspend fun validate(token: String): String? {
        val r = http.send(HttpRequest("https://api.listenbrainz.org/1/validate-token", headers = auth(token)))
        val o = runCatching { Json.parseToJsonElement(r.text) as? JsonObject }.getOrNull() ?: return null
        if ((o["valid"] as? JsonPrimitive)?.boolean != true) return null
        return (o["user_name"] as? JsonPrimitive)?.contentOrNull
    }

    /** True when ListenBrainz took the listen. A song needs at least a title and artist. */
    suspend fun submit(token: String, song: Song, listenedAtSec: Long): Boolean {
        if (song.title.isBlank() || song.artist.isBlank()) return false
        val body = buildJsonObject {
            put("listen_type", "single")
            putJsonArray("payload") {
                add(buildJsonObject {
                    put("listened_at", listenedAtSec)
                    put("track_metadata", buildJsonObject {
                        put("artist_name", song.artist)
                        put("track_name", song.title)
                        if (song.album.isNotBlank()) put("release_name", song.album)
                        put("additional_info", buildJsonObject {
                            put("media_player", "PaperPlay")
                            if (song.duration_sec > 0) put("duration_ms", song.duration_sec * 1000L)
                        })
                    })
                })
            }
        }.toString()
        val r = http.send(HttpRequest(
            "https://api.listenbrainz.org/1/submit-listens", "POST",
            auth(token) + ("Content-Type" to "application/json"), body.encodeToByteArray(),
        ))
        return r.status == 200
    }
}
