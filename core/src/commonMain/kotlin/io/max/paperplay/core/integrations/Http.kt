package io.max.paperplay.core.integrations

class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

/** Header names are lower case. */
class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    val text: String get() = body.decodeToString()
}

/** Throws on a network failure; any HTTP status, including errors, is a normal response. */
interface HttpFetcher {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** Where tokens live (Keystore on Android, a protected file on desktop). Never a plain preference. */
interface SecretStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
}

/**
 * Sends the person to [authorizeUrl] and returns the query parameters of the callback
 * on [redirectUri] (`code`, `state`, or `error`). Throws [SpotifyLoginError] if the page
 * can't be opened or the callback can't be received. The UI wires it per platform.
 */
interface AuthRedirect {
    suspend fun authorize(authorizeUrl: String, redirectUri: String): Map<String, String>
}

internal expect fun sha256(bytes: ByteArray): ByteArray
internal expect fun secureRandomBytes(count: Int): ByteArray

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

internal fun percentEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = (b.toInt() and 0xff).toChar()
        if (c in UNRESERVED) append(c) else append('%').append((b.toInt() and 0xff).toString(16).uppercase().padStart(2, '0'))
    }
}

internal fun formEncode(params: Map<String, String>): String =
    params.entries.joinToString("&") { "${percentEncode(it.key)}=${percentEncode(it.value)}" }

internal fun urlWith(base: String, params: Map<String, String>): String =
    if (params.isEmpty()) base else "$base?${formEncode(params)}"
