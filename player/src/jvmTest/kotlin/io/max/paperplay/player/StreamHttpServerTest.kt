package io.max.paperplay.player

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ByteArraySource(private val data: ByteArray) : StreamSource {
    val reads = java.util.concurrent.CopyOnWriteArrayList<Long>()
    override val size get() = data.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        reads += offset
        if (offset >= data.size) return ByteArray(0)
        return data.copyOfRange(offset.toInt(), minOf(data.size.toLong(), offset + length).toInt())
    }
}

class StreamHttpServerTest {
    private val data = ByteArray(300_000) { (it * 31 + it / 251).toByte() }
    private val server = StreamHttpServer()
    private val url = server.register("t1", ByteArraySource(data))
    private val client = HttpClient.newHttpClient()

    @AfterTest
    fun tearDown() = server.close()

    private fun get(range: String? = null, method: String = "GET", u: String = url): HttpResponse<ByteArray> {
        val b = HttpRequest.newBuilder(URI(u)).method(method, HttpRequest.BodyPublishers.noBody())
        if (range != null) b.header("Range", range)
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    @Test
    fun fullGetAdvertisesRanges() {
        val r = get()
        assertEquals(200, r.statusCode())
        assertEquals("bytes", r.headers().firstValue("Accept-Ranges").get())
        assertContentEquals(data, r.body())
    }

    @Test
    fun boundedRange() {
        val r = get("bytes=10-99999")
        assertEquals(206, r.statusCode())
        assertEquals("bytes 10-99999/300000", r.headers().firstValue("Content-Range").get())
        assertContentEquals(data.copyOfRange(10, 100_000), r.body())
    }

    @Test
    fun openEndedRangeCrossesChunks() {
        val r = get("bytes=250000-")
        assertEquals(206, r.statusCode())
        assertContentEquals(data.copyOfRange(250_000, 300_000), r.body())
    }

    @Test
    fun suffixRangeAndClampedEnd() {
        assertContentEquals(data.copyOfRange(299_990, 300_000), get("bytes=-10").body())
        val r = get("bytes=299990-999999999")
        assertEquals("bytes 299990-299999/300000", r.headers().firstValue("Content-Range").get())
        assertContentEquals(data.copyOfRange(299_990, 300_000), r.body())
    }

    @Test
    fun unsatisfiableRange() {
        val r = get("bytes=300000-")
        assertEquals(416, r.statusCode())
        assertEquals("bytes */300000", r.headers().firstValue("Content-Range").get())
    }

    @Test
    fun headAndMissing() {
        val h = get(method = "HEAD")
        assertEquals(200, h.statusCode())
        assertEquals("300000", h.headers().firstValue("Content-Length").get())
        assertEquals(404, get(u = url.replace("t1", "nope")).statusCode())
    }
}
