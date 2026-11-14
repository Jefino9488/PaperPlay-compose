package io.max.paperplay.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Loopback HTTP server exposing [StreamSource]s with Range support. */
class StreamHttpServer : AutoCloseable {
    // Only the most recently registered songs stay: every song played used to stay registered, with its buffers, until the app closed.
    private val sources: MutableMap<String, StreamSource> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, StreamSource>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamSource>?) = size > MAX_SOURCES
        },
    )
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool { r -> Thread(r, "stream-http").apply { isDaemon = true } }
        createContext("/") { handle(it) }
        start()
    }

    /** Registers [source] under [key] (URL-safe) and returns its URL. */
    fun register(key: String, source: StreamSource): String {
        sources[key] = source
        return "http://127.0.0.1:${server.address.port}/$key"
    }

    private fun handle(ex: HttpExchange) = ex.use {
        val source = sources[ex.requestURI.path.removePrefix("/")]
        val head = ex.requestMethod == "HEAD"
        if (source == null || (!head && ex.requestMethod != "GET")) {
            ex.sendResponseHeaders(if (source == null) 404 else 405, -1)
            return
        }
        val size = source.size
        ex.responseHeaders["Accept-Ranges"] = listOf("bytes")
        ex.responseHeaders["Content-Type"] = listOf("application/octet-stream")
        val header = ex.requestHeaders.getFirst("Range")
        val range = header?.let { parseRange(it, size) }
        if (header != null && range == null) {
            ex.responseHeaders["Content-Range"] = listOf("bytes */$size")
            ex.sendResponseHeaders(416, -1)
            return
        }
        val start = range?.first ?: 0L
        val end = range?.second ?: (size - 1)
        val length = maxOf(0L, end - start + 1)
        if (range != null) ex.responseHeaders["Content-Range"] = listOf("bytes $start-$end/$size")
        val status = if (range != null) 206 else 200
        if (head) {
            ex.responseHeaders["Content-Length"] = listOf(length.toString())
            ex.sendResponseHeaders(status, -1)
            return
        }
        ex.sendResponseHeaders(status, if (length == 0L) -1 else length)
        var pos = start
        while (pos <= end) {
            val chunk = source.read(pos, minOf(CHUNK.toLong(), end - pos + 1).toInt())
            if (chunk.isEmpty()) break
            ex.responseBody.write(chunk)
            pos += chunk.size
        }
    }

    override fun close() = server.stop(0)

    companion object {
        private const val CHUNK = 64 * 1024
        private const val MAX_SOURCES = 8

        /** Single "bytes=a-b" / "a-" / "-n" range clamped to [size]; null if malformed or unsatisfiable. */
        internal fun parseRange(header: String, size: Long): Pair<Long, Long>? {
            val m = Regex("""bytes=(\d*)-(\d*)""").matchEntire(header.trim()) ?: return null
            val (a, b) = m.destructured
            if (a.isEmpty() && b.isEmpty()) return null
            val start: Long
            val end: Long
            if (a.isEmpty()) {
                val n = (b.toLongOrNull() ?: return null).coerceAtMost(size)
                start = size - n
                end = size - 1
            } else {
                start = a.toLongOrNull() ?: return null
                end = if (b.isEmpty()) size - 1 else minOf(b.toLongOrNull() ?: return null, size - 1)
            }
            return if (start >= size || start > end) null else start to end
        }
    }
}
