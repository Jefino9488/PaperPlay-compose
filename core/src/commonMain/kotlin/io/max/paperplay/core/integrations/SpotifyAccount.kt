package io.max.paperplay.core.integrations

import io.max.paperplay.core.app.SecretStore
import io.max.paperplay.core.backup.SecretKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.time.Clock
import kotlin.time.Instant

class SpotifyLoginError(message: String) : Exception(message)

/**
 * The owner's own Spotify login, with a client id they made and PKCE (no client secret).
 * It lets a playlist import read every track through the Web API instead of the first 100
 * of the public page. The refresh token and client id live in the secret store.
 */
class SpotifyAccount(private val secrets: SecretStore, private val http: HttpFetcher) {
    private var accessToken: String? = null
    private var expiresAtMs = 0L

    var clientId: String?
        get() = secrets.get(SecretKeys.SPOTIFY_CLIENT_ID)?.takeIf { it.isNotBlank() }
        set(v) {
            val t = v?.trim().orEmpty()
            if (t.isEmpty()) secrets.remove(SecretKeys.SPOTIFY_CLIENT_ID) else secrets.put(SecretKeys.SPOTIFY_CLIENT_ID, t)
        }

    val connected: Boolean get() = secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN) != null

    fun disconnect() {
        secrets.remove(SecretKeys.SPOTIFY_REFRESH_TOKEN)
        accessToken = null
    }

    /** Signs in through the browser; [redirect] returns the callback on [redirectUri]. */
    suspend fun connect(redirect: AuthRedirect, redirectUri: String) {
        val id = clientId ?: throw SpotifyLoginError("Enter your client id first.")
        val verifier = randomText(64)
        val state = randomText(16)
        val challenge = base64Url(sha256(verifier.encodeToByteArray()))
        val url = urlWith(
            "https://accounts.spotify.com/authorize",
            mapOf(
                "client_id" to id,
                "response_type" to "code",
                "redirect_uri" to redirectUri,
                "scope" to SCOPES,
                "state" to state,
                "code_challenge_method" to "S256",
                "code_challenge" to challenge,
            ),
        )
        val q = redirect.authorize(url, redirectUri)
        if (q["state"] != state) throw SpotifyLoginError("Spotify's reply did not match. Try again.")
        val code = q["code"] ?: throw SpotifyLoginError("Spotify refused: ${q["error"] ?: "unknown reason"}.")
        token(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri,
                "client_id" to id,
                "code_verifier" to verifier,
            ),
        )
    }

    /** The account's display name, or null if it can't be read. */
    suspend fun userName(): String? {
        val res = get("https://api.spotify.com/v1/me") ?: return null
        if (res.status != 200) return null
        val o = runCatching { Json.parseToJsonElement(res.text) as? JsonObject }.getOrNull() ?: return null
        return o.str("display_name") ?: o.str("id")
    }

    /**
     * Reads the whole playlist [id] through the API. Null when not logged in, or when Spotify won't
     * show this playlist to the account (not owned or shared), so the caller can fall back.
     */
    suspend fun fetchPlaylist(id: String): FetchedPlaylist? {
        if (!connected) return null
        val about = get("https://api.spotify.com/v1/playlists/$id?fields=name") ?: return null
        if (about.status == 403 || about.status == 404) return null
        if (about.status != 200) throw ImportError("Spotify answered with error ${about.status}.")
        val name = (Json.parseToJsonElement(about.text) as? JsonObject)?.str("name") ?: "Imported playlist"
        val tracks = ArrayList<ImportedTrack>()
        var next: String? = "https://api.spotify.com/v1/playlists/$id/items?limit=100"
        while (next != null) {
            val res = get(next) ?: return null
            if (res.status == 403 || res.status == 404) return null
            if (res.status != 200) throw ImportError("Spotify answered with error ${res.status}.")
            val page = Json.parseToJsonElement(res.text) as? JsonObject ?: throw ImportError("Spotify's answer could not be read.")
            tracks += parseSpotifyItems(page["items"] as? JsonArray ?: JsonArray(emptyList()))
            next = page.str("next")
        }
        return FetchedPlaylist(name, tracks)
    }

    private suspend fun get(url: String): HttpResponse? {
        val t = accessToken() ?: return null
        return http.send(HttpRequest(url, headers = mapOf("Authorization" to "Bearer $t")))
    }

    private suspend fun accessToken(): String? {
        val now = Clock.System.now().toEpochMilliseconds()
        accessToken?.let { if (now < expiresAtMs) return it }
        val refresh = secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN) ?: return null
        val id = clientId ?: return null
        return try {
            token(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to id))
        } catch (e: SpotifyLoginError) {
            null
        }
    }

    private suspend fun token(form: Map<String, String>): String {
        val res = http.send(
            HttpRequest(
                "https://accounts.spotify.com/api/token", "POST",
                mapOf("Content-Type" to "application/x-www-form-urlencoded"), formEncode(form).encodeToByteArray(),
            ),
        )
        if (res.status != 200) {
            // A refused refresh token means the login is over.
            if (form["grant_type"] == "refresh_token" && res.status == 400) disconnect()
            throw SpotifyLoginError("Spotify refused the sign-in (${res.status}). Check your client id.")
        }
        val o = Json.parseToJsonElement(res.text) as? JsonObject ?: throw SpotifyLoginError("Spotify's answer could not be read.")
        val access = o.str("access_token") ?: throw SpotifyLoginError("Spotify's answer could not be read.")
        val secs = (o["expires_in"] as? JsonPrimitive)?.intOrNull ?: 3600
        accessToken = access
        expiresAtMs = Clock.System.now().toEpochMilliseconds() + (secs - 60) * 1000L
        o.str("refresh_token")?.let { secrets.put(SecretKeys.SPOTIFY_REFRESH_TOKEN, it) }
        return access
    }

    private companion object {
        const val SCOPES = "playlist-read-private playlist-read-collaborative"
        const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun randomText(n: Int): String =
            secureRandomBytes(n).joinToString("") { LETTERS[(it.toInt() and 0xff) % LETTERS.length].toString() }

        /** Base64url without padding. */
        fun base64Url(b: ByteArray): String = buildString {
            var i = 0
            while (i < b.size) {
                val n = ((b[i].toInt() and 0xff) shl 16) or
                    ((if (i + 1 < b.size) b[i + 1].toInt() and 0xff else 0) shl 8) or
                    (if (i + 2 < b.size) b[i + 2].toInt() and 0xff else 0)
                append(B64[(n shr 18) and 63]).append(B64[(n shr 12) and 63])
                if (i + 1 < b.size) append(B64[(n shr 6) and 63])
                if (i + 2 < b.size) append(B64[n and 63])
                i += 3
            }
        }
    }
}

/** Tracks from one page of Spotify's playlist items. Local files and episodes are left out. */
fun parseSpotifyItems(items: JsonArray): List<ImportedTrack> = items.mapNotNull { raw ->
    val entry = raw as? JsonObject ?: return@mapNotNull null
    val t = (entry["item"] ?: entry["track"]) as? JsonObject ?: return@mapNotNull null
    val uri = t.str("uri")?.takeIf { it.startsWith("spotify:track:") } ?: return@mapNotNull null
    val artists = ((t["artists"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { (it as? JsonObject)?.str("name") }.joinToString(", ")
    val added = entry.str("added_at")?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
    ImportedTrack(uri, t.str("name") ?: "", artists, millisToSeconds(t["duration_ms"]), added)
}
