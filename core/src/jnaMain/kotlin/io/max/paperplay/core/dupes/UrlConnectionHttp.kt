package io.max.paperplay.core.dupes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * HttpURLConnection runs on both Android and the desktop JVM, so one poster serves both
 * (the JDK HttpClient would need a second one for Android). A failed request reports nothing,
 * since its text can contain the bot token.
 */
class UrlConnectionHttp(private val timeoutMs: Int = 15_000) : HttpPoster {
    override suspend fun post(url: String, form: Map<String, String>): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
            conn.outputStream.use { it.write(body.toByteArray()) }
            // Telegram answers errors with a JSON body on a 4xx status too.
            val stream = if (conn.responseCode < 400) conn.inputStream else conn.errorStream
            stream?.use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
