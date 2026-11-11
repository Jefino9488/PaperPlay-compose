package io.max.paperplay.core.integrations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** [HttpFetcher] over HttpURLConnection, shared by Android and desktop. */
class JavaHttp(private val timeoutMs: Int = 15_000) : HttpFetcher {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val c = URL(request.url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = request.method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "PaperPlay")
            request.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            request.body?.let { c.doOutput = true; c.outputStream.use { o -> o.write(it) } }
            val status = c.responseCode
            val body = (if (status < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            HttpResponse(status, c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }, body)
        } finally {
            c.disconnect()
        }
    }
}
