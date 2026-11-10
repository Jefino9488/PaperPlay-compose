package io.max.paperplay.core.metadata

import kotlinx.coroutines.delay

class HttpResponse(val status: Int, val body: ByteArray)

/** The device is offline or the connection failed. */
class NetworkException(message: String?, cause: Throwable? = null) : Exception(message, cause)

/** Tiny seam over the HTTP stack: implementations throw [NetworkException] when there is no answer. */
interface HttpFetcher {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

/** MusicBrainz asks clients to identify themselves with contact info. */
fun userAgent(contact: String = ""): String = "PaperPlay/0.1.0" + if (contact.isEmpty()) "" else " ( $contact )"

private fun encode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xff
        if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in "-._~")) append(c.toChar())
        else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
    }
}

/** `https://host/path?query` with the parameters escaped; null values are left out. */
fun httpsUrl(host: String, path: String, params: Map<String, String?> = emptyMap()): String {
    val query = params.entries.filter { it.value != null }.joinToString("&") { "${encode(it.key)}=${encode(it.value!!)}" }
    return "https://$host$path" + if (query.isEmpty()) "" else "?$query"
}

fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?')

/**
 * The bytes at [url], or null if they aren't there. A busy server (5xx or 429) is asked again after
 * each of [delaysMs], since Cover Art Archive often answers 500 for a moment. [onFailure] hears the
 * status of the answer that ended it.
 */
suspend fun fetchWithRetry(
    http: HttpFetcher,
    url: String,
    headers: Map<String, String> = emptyMap(),
    delaysMs: List<Long> = listOf(2000, 6000),
    onFailure: (Int) -> Unit = {},
): ByteArray? {
    var attempt = 0
    while (true) {
        val res = http.get(url, headers)
        if (res.status == 200) return res.body
        val busy = res.status >= 500 || res.status == 429
        if (!busy || attempt >= delaysMs.size) {
            onFailure(res.status)
            return null
        }
        delay(delaysMs[attempt++])
    }
}
