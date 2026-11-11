package io.max.paperplay.core.app

import io.max.paperplay.core.library.Song
import io.max.paperplay.core.telegram.FakeTdNative
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.TdConfig
import io.max.paperplay.core.telegram.TdRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = TdConfig(1, "hash", "123:fake-token", "/db", "/files", "key")

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun song(
        id: Long,
        size: Long = 1_000_000L,
        durationSec: Long = 100L,
        pinned: Boolean = false,
    ) = Song(
        1L, id, "u$id", "r$id", "Title $id", "Artist", "Album", durationSec, size, "audio/flac", "t$id.flac", id,
        null, null, null, null, null, null, false, false, false, 0, null, 0, null, pinned, 0, 0, 0, false, 0, 0, 0,
        null, null, null, null,
    )

    private fun messageReply(req: JsonObject, fileId: Int, path: String, completed: Boolean, size: Long): JsonObject =
        buildJsonObject {
            put("@type", "message")
            req["@extra"]?.let { put("@extra", it) }
            put("chat_id", 1L)
            put("id", req["message_id"] ?: JsonPrimitive(1L))
            put("content", buildJsonObject {
                put("@type", "messageAudio")
                put("audio", buildJsonObject {
                    put("audio", buildJsonObject {
                        put("id", fileId)
                        put("size", size)
                        put("local", buildJsonObject {
                            put("path", path)
                            put("is_downloading_completed", completed)
                            put("is_downloading_active", false)
                            put("downloaded_size", if (completed) size else 0L)
                        })
                    })
                })
            })
        }

    private fun fileReply(req: JsonObject, fileId: Int, path: String, completed: Boolean, active: Boolean, downloaded: Long): JsonObject =
        buildJsonObject {
            put("@type", "file")
            req["@extra"]?.let { put("@extra", it) }
            put("id", fileId)
            put("local", buildJsonObject {
                put("path", path)
                put("is_downloading_completed", completed)
                put("is_downloading_active", active)
                put("downloaded_size", downloaded)
            })
        }

    private fun sizeReply(req: JsonObject, size: Long): JsonObject = buildJsonObject {
        put("@type", "count")
        req["@extra"]?.let { put("@extra", it) }
        put("count", size)
        put("size", size)
    }

    private fun okReply(req: JsonObject): JsonObject = buildJsonObject {
        put("@type", "ok")
        req["@extra"]?.let { put("@extra", it) }
    }

    @Test
    fun completedSongReadsDirectlyWithoutDownloading() = runBlocking {
        val data = ByteArray(4096) { (it * 7).toByte() }
        val tmp = Files.createTempFile("pp-done", ".bin").toFile().apply {
            writeBytes(data)
            deleteOnExit()
        }
        val fake = FakeTdNative { _, req ->
            when (req["@type"]!!.jsonPrimitive.content) {
                "getMessage" -> listOf(messageReply(req, 10, tmp.absolutePath, completed = true, size = data.size.toLong()))
                else -> emptyList()
            }
        }
        val client = TdClient(TdRuntime(fake, scope, Dispatchers.IO), config).also { it.start() }
        val src = TdStreamSource({ client }, song(1, size = data.size.toLong(), durationSec = 10))
        val chunk = src.read(512, 1024)
        assertContentEquals(data.copyOfRange(512, 1536), chunk)
        assertFalse(fake.sent.any { it["@type"]!!.jsonPrimitive.content == "downloadFile" })
        assertFalse(fake.sent.any { it["@type"]!!.jsonPrimitive.content == "getFileDownloadedPrefixSize" })
    }

    @Test
    fun streamingSongUsesBitrateSlidingWindowAndCancelsOnPause() = runBlocking {
        val data = ByteArray(200_000) { (it * 13).toByte() }
        val tmp = Files.createTempFile("pp-stream", ".bin").toFile().apply {
            writeBytes(data)
            deleteOnExit()
        }
        var downloadedPrefix = 0L
        val fake = FakeTdNative { _, req ->
            when (req["@type"]!!.jsonPrimitive.content) {
                "getMessage" -> listOf(messageReply(req, 20, tmp.absolutePath, completed = false, size = 1_000_000L))
                "getFileDownloadedPrefixSize" -> listOf(sizeReply(req, downloadedPrefix))
                "downloadFile" -> {
                    val limit = req["limit"]!!.jsonPrimitive.long
                    downloadedPrefix = if (limit == 0L) 1_000_000L else limit
                    listOf(fileReply(req, 20, tmp.absolutePath, completed = false, active = true, downloaded = downloadedPrefix))
                }
                "cancelDownloadFile" -> listOf(okReply(req))
                else -> emptyList()
            }
        }
        val client = TdClient(TdRuntime(fake, scope, Dispatchers.IO), config).also { it.start() }
        // 1,000,000 bytes / 100s = 10,000 B/s; 10s sliding window = 100,000 bytes.
        val src = TdStreamSource({ client }, song(2, size = 1_000_000L, durationSec = 100L), windowSeconds = 10L)
        val chunk = src.read(0L, 65_536)
        assertEquals(65_536, chunk.size)
        assertContentEquals(data.copyOfRange(0, 65_536), chunk)

        val dl = fake.sent.first { it["@type"]!!.jsonPrimitive.content == "downloadFile" }
        assertEquals(100_000L, dl["limit"]!!.jsonPrimitive.long)

        src.cancel()
        assertTrue(fake.sent.any { it["@type"]!!.jsonPrimitive.content == "cancelDownloadFile" })
    }

    @Test
    fun pinnedSongDownloadsFullFileAndSkipsCancel() = runBlocking {
        val data = ByteArray(4096) { it.toByte() }
        val tmp: File = Files.createTempFile("pp-pin", ".bin").toFile().apply {
            writeBytes(data)
            deleteOnExit()
        }
        var prefix = 0L
        val fake = FakeTdNative { _, req ->
            when (req["@type"]!!.jsonPrimitive.content) {
                "getMessage" -> listOf(messageReply(req, 30, tmp.absolutePath, completed = false, size = data.size.toLong()))
                "getFileDownloadedPrefixSize" -> listOf(sizeReply(req, prefix))
                "downloadFile" -> {
                    prefix = data.size.toLong()
                    listOf(fileReply(req, 30, tmp.absolutePath, completed = true, active = false, downloaded = prefix))
                }
                "cancelDownloadFile" -> listOf(okReply(req))
                else -> emptyList()
            }
        }
        val client = TdClient(TdRuntime(fake, scope, Dispatchers.IO), config).also { it.start() }
        val src = TdStreamSource({ client }, song(3, size = data.size.toLong(), durationSec = 10L, pinned = true))
        val chunk = src.read(0L, 1024)
        assertEquals(1024, chunk.size)
        val dl = fake.sent.first { it["@type"]!!.jsonPrimitive.content == "downloadFile" }
        assertEquals(0L, dl["limit"]!!.jsonPrimitive.long)

        src.cancel()
        assertFalse(fake.sent.any { it["@type"]!!.jsonPrimitive.content == "cancelDownloadFile" })
    }

    @Test
    fun trackResolverRemainsLazyUntilOpenedAndWindowDropsStale() = runBlocking {
        val fake = FakeTdNative { _, req ->
            when (req["@type"]!!.jsonPrimitive.content) {
                "getMessage" -> listOf(messageReply(req, req["message_id"]!!.jsonPrimitive.long.toInt(), "/tmp/none", completed = false, size = 10_000L))
                "cancelDownloadFile" -> listOf(okReply(req))
                else -> emptyList()
            }
        }
        val client = TdClient(TdRuntime(fake, scope, Dispatchers.IO), config).also { it.start() }
        val resolver = TrackResolver({ client })
        val songs = (1L..50L).map { song(it, size = 10_000L, durationSec = 10L) }
        val items = songs.map(resolver::resolve)
        assertTrue(resolver.activeIds().isEmpty())

        resolver.warm(songs[0])
        resolver.warm(songs[1])
        assertEquals(setOf("u1", "u2"), resolver.activeIds())

        resolver.window(songs[2], songs[3], playing = true)
        assertTrue(resolver.activeIds().isEmpty())
        assertTrue(fake.sent.count { it["@type"]!!.jsonPrimitive.content == "cancelDownloadFile" } >= 2)
        // Accessing QueueItem.source resolves lazily on demand.
        assertTrue(items[2].source != null)
        assertEquals(setOf("u3"), resolver.activeIds())
    }
}
