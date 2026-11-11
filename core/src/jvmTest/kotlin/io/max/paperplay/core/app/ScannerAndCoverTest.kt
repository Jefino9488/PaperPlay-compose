package io.max.paperplay.core.app

import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.core.integrations.HttpFetcher
import io.max.paperplay.core.integrations.HttpRequest
import io.max.paperplay.core.integrations.HttpResponse
import io.max.paperplay.core.library.LibraryChat
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.metadata.extraStorefronts
import io.max.paperplay.core.metadata.firstArtist
import io.max.paperplay.core.recs.TasteSettings
import io.max.paperplay.core.telegram.FakeTdNative
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.TdConfig
import io.max.paperplay.core.telegram.TdRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ScannerAndCoverTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = TdConfig(1, "hash", "123:fake-token", "/db", "/files", "key")
    private val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun newRepo(): Pair<LibraryDatabase, LibraryRepository> {
        var now = 1_000L
        val db = LibraryDatabase(libraryDriver())
        return db to LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
    }

    private fun insertSong(
        db: LibraryDatabase,
        id: Long,
        title: String = "Track $id",
        artist: String = "Artist $id",
        duration: Long = 180L,
    ) {
        db.songsQueries.insertSong(
            Song(
                chat_id = -1001234567890L,
                message_id = id * MESSAGE_ID_STEP,
                unique_id = "u$id",
                remote_id = "r$id",
                title = title,
                artist = artist,
                album = "",
                duration_sec = duration,
                size = 500_000L,
                mime = "audio/mpeg",
                file_name = "track_$id.mp3",
                date = id,
                cover_path = null,
                release_id = null,
                cover_url = null,
                genre = null,
                year = null,
                metadata_source = null,
                favorite = false,
                cover_hi_res = false,
                user_edited = false,
                enrich_state = 0,
                lyrics = null,
                cached_bytes = 0,
                last_played_at = null,
                pinned = false,
                tags_at = 0,
                favorite_at = 0,
                cover_at = 0,
                instrumental = false,
                mood_scanned_at = 0,
                plays = 0,
                skips = 0,
                topic_id = null,
                sample_rate = null,
                bit_depth = null,
                codec = null,
            ),
        )
    }

    private suspend fun await(cond: () -> Boolean) {
        withTimeout(5_000) {
            while (!cond()) delay(10)
        }
    }

    @Test
    fun scannerFetchesRealTitleBoundsWalkWithBatch100AndStopsQuickly(): Unit = runBlocking {
        assertEquals(100, Scanner.WALK_BATCH)
        val (_, repo) = newRepo()
        val chatId = -1001234567890L
        repo.addChat(chatId, chatId.toString())

        val lastMsgId = 150L * MESSAGE_ID_STEP
        var getMessagesCalls = 0
        val batchSizes = mutableListOf<Int>()
        val fake = FakeTdNative { _, req ->
            val extra = req["@extra"]
            when (req["@type"]!!.jsonPrimitive.content) {
                "createSupergroupChat", "getSupergroup" -> listOf(
                    buildJsonObject {
                        put("@type", "ok")
                        extra?.let { put("@extra", it) }
                    },
                )
                "getChat" -> listOf(
                    buildJsonObject {
                        put("@type", "chat")
                        extra?.let { put("@extra", it) }
                        put("id", chatId)
                        put("title", "Synthwave Vault")
                        put("last_message", buildJsonObject { put("id", lastMsgId) })
                    },
                )
                "getMessages" -> {
                    getMessagesCalls++
                    val ids = req["message_ids"]!!.jsonArray.map { it.jsonPrimitive.long }
                    batchSizes += ids.size
                    val msgs = buildJsonArray {
                        if (MESSAGE_ID_STEP in ids) {
                            add(
                                buildJsonObject {
                                    put("@type", "message")
                                    put("chat_id", chatId)
                                    put("id", MESSAGE_ID_STEP)
                                    put("date", 100L)
                                    put("content", buildJsonObject {
                                        put("@type", "messageAudio")
                                        put("audio", buildJsonObject {
                                            put("title", "Night Drive")
                                            put("performer", "FM-84")
                                            put("duration", 210)
                                            put("mime_type", "audio/mpeg")
                                            put("file_name", "night.mp3")
                                            put("audio", buildJsonObject {
                                                put("id", 7)
                                                put("size", 3_000_000L)
                                                put("remote", buildJsonObject {
                                                    put("id", "rem1")
                                                    put("unique_id", "uniq1")
                                                })
                                            })
                                        })
                                    })
                                },
                            )
                        }
                    }
                    listOf(
                        buildJsonObject {
                            put("@type", "messages")
                            extra?.let { put("@extra", it) }
                            put("messages", msgs)
                        },
                    )
                }
                else -> emptyList()
            }
        }
        val client = TdClient(TdRuntime(fake, scope, Dispatchers.IO), config).also { it.start() }
        val scanner = Scanner(repo, scope, minPause = 1.milliseconds)
        scanner.attach(client)

        val progressTexts = mutableListOf<String>()
        val job = launch {
            scanner.progress.collect { p -> if (p != null) progressTexts += p.text }
        }
        val seen = scanner.scanChat(LibraryChat(chatId, chatId.toString(), null, use_bot = true, walk_done = false))
        job.cancel()

        assertNotNull(seen)
        assertEquals(setOf(MESSAGE_ID_STEP), seen)
        assertEquals("Synthwave Vault", repo.chat(chatId)!!.title)
        assertTrue(repo.chat(chatId)!!.walk_done)
        assertTrue(batchSizes.all { it == 100 })
        // Batch 1 (1..100, has message), Batch 2 (101..200, next > 150 after increment -> empty #1), Batch 3 (201..300 -> empty #2 stops).
        assertEquals(3, getMessagesCalls)
        assertTrue(progressTexts.any { it.startsWith("Syncing Synthwave Vault:") })
        assertNull(scanner.progress.value)
    }

    @Test
    fun coverServicePrioritizesTelegramThumbAndFallsBackToDeezerAndItunes(): Unit = runBlocking {
        val dataDir = Files.createTempDirectory("pp-covers").toFile().apply { deleteOnExit() }.absolutePath
        val thumbFile = File("$dataDir/td_thumb.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(jpegHeader)
            deleteOnExit()
        }
        val (db, repo) = newRepo()
        insertSong(db, 1, title = "Telegram Track", artist = "Local Artist", duration = 200L)
        insertSong(db, 2, title = "Midnight City", artist = "M83", duration = 243L)
        insertSong(db, 3, title = "Digital Love", artist = "Daft Punk", duration = 298L)

        val deezerBytes = jpegHeader + byteArrayOf(0x11)
        val itunesBytes = jpegHeader + byteArrayOf(0x22)
        val http = object : HttpFetcher {
            override suspend fun send(request: HttpRequest): HttpResponse = when {
                request.url.startsWith("https://api.deezer.com/search") -> {
                    val body = if ("Midnight" in request.url) {
                        """{"data":[{"title":"Midnight City","duration":243,"artist":{"name":"M83"},"album":{"title":"Hurry Up","cover_xl":"https://cdn.example/deezer.jpg"}}]}"""
                    } else {
                        """{"data":[]}"""
                    }
                    HttpResponse(200, mapOf("content-type" to "application/json"), body.encodeToByteArray())
                }
                request.url.startsWith("https://itunes.apple.com/search") -> {
                    val body = """{"results":[{"trackName":"Digital Love","artistName":"Daft Punk","collectionName":"Discovery","trackTimeMillis":298000,"artworkUrl100":"https://cdn.example/100x100bb.jpg","primaryGenreName":"Electronic","releaseDate":"2001-03-07T08:00:00Z"}]}"""
                    HttpResponse(200, mapOf("content-type" to "application/json"), body.encodeToByteArray())
                }
                request.url == "https://cdn.example/deezer.jpg" ->
                    HttpResponse(200, mapOf("content-type" to "image/jpeg"), deezerBytes)
                request.url == "https://cdn.example/600x600bb.jpg" ->
                    HttpResponse(200, mapOf("content-type" to "image/jpeg"), itunesBytes)
                else -> HttpResponse(404, emptyMap(), ByteArray(0))
            }
        }

        val fakeTd = FakeTdNative { _, req ->
            val extra = req["@extra"]
            when (req["@type"]!!.jsonPrimitive.content) {
                "getMessage" -> {
                    val msgId = req["message_id"]!!.jsonPrimitive.long
                    val content = if (msgId == 1L * MESSAGE_ID_STEP) {
                        buildJsonObject {
                            put("@type", "messageAudio")
                            put("audio", buildJsonObject {
                                put("album_cover_thumbnail", buildJsonObject {
                                    put("file", buildJsonObject {
                                        put("id", 91)
                                        put("local", buildJsonObject {
                                            put("path", thumbFile.absolutePath)
                                            put("is_downloading_completed", false)
                                        })
                                    })
                                })
                                put("audio", buildJsonObject { put("id", 92) })
                            })
                        }
                    } else {
                        buildJsonObject {
                            put("@type", "messageAudio")
                            put("audio", buildJsonObject { put("audio", buildJsonObject { put("id", 93) }) })
                        }
                    }
                    listOf(
                        buildJsonObject {
                            put("@type", "message")
                            extra?.let { put("@extra", it) }
                            put("id", msgId)
                            put("content", content)
                        },
                    )
                }
                "downloadFile" -> listOf(
                    buildJsonObject {
                        put("@type", "file")
                        extra?.let { put("@extra", it) }
                        put("id", req["file_id"]!!.jsonPrimitive.long)
                        put("local", buildJsonObject {
                            put("path", thumbFile.absolutePath)
                            put("is_downloading_completed", true)
                        })
                    },
                )
                else -> emptyList()
            }
        }
        val client = TdClient(TdRuntime(fakeTd, scope, Dispatchers.IO), config).also { it.start() }

        val covers = CoverService(
            dataDir = dataDir,
            td = { client },
            repo = repo,
            http = http,
            scope = scope,
            maxWorkers = 2,
            requestPauseMs = 0L,
            backgroundPauseMs = 0L,
            debounceMs = 0L,
        )
        val initialChanges = repo.changes.value
        covers.prioritize(listOf(repo.songByUniqueId("u1")!!, repo.songByUniqueId("u2")!!, repo.songByUniqueId("u3")!!))

        await {
            repo.songByUniqueId("u1")?.cover_path != null &&
                repo.songByUniqueId("u2")?.cover_path != null &&
                repo.songByUniqueId("u3")?.cover_path != null &&
                repo.changes.value > initialChanges
        }

        val s1 = repo.songByUniqueId("u1")!!
        val s2 = repo.songByUniqueId("u2")!!
        val s3 = repo.songByUniqueId("u3")!!
        assertContentEquals(jpegHeader, File(s1.cover_path!!).readBytes())
        assertTrue(s1.cover_path.startsWith("$dataDir/covers/u1_"))
        assertContentEquals(deezerBytes, File(s2.cover_path!!).readBytes())
        assertEquals("Hurry Up", s2.album)
        assertEquals("deezer", s2.metadata_source)
        assertContentEquals(itunesBytes, File(s3.cover_path!!).readBytes())
        assertEquals(2001L, s3.year)
        assertEquals("Electronic", s3.genre)
        assertEquals("itunes", s3.metadata_source)
    }

    @Test
    fun coverServiceRespectsLookupsSetting(): Unit = runBlocking {
        val dataDir = Files.createTempDirectory("pp-nolookup").toFile().apply { deleteOnExit() }.absolutePath
        val (db, repo) = newRepo()
        insertSong(db, 10, title = "Some Song", artist = "Some Artist", duration = 200L)
        repo.setPref(TasteSettings.PREF_KEY, TasteSettings(lookups = false).encode())

        var httpCalls = 0
        val http = object : HttpFetcher {
            override suspend fun send(request: HttpRequest): HttpResponse {
                httpCalls++
                return HttpResponse(404, emptyMap(), ByteArray(0))
            }
        }
        val covers = CoverService(
            dataDir = dataDir,
            td = { null },
            repo = repo,
            http = http,
            scope = scope,
            maxWorkers = 1,
            requestPauseMs = 0L,
            backgroundPauseMs = 0L,
            debounceMs = 0L,
        )
        covers.prioritize(listOf(repo.songByUniqueId("u10")!!))
        delay(100)
        assertEquals(0, httpCalls)
        assertNull(repo.songByUniqueId("u10")!!.cover_path)
    }

    @Test
    fun coverServiceRetriesRateLimitedItunesAndMatchesCjkAndSlashArtists(): Unit = runBlocking {
        assertEquals("Karen Aoki", firstArtist("Karen Aoki / Daisuke Hasegawa"))
        assertEquals("AC/DC", firstArtist("AC/DC"))
        assertEquals(listOf("TW", "IN"), extraStorefronts("分裂", "周杰倫"))
        assertEquals(listOf("JP", "IN"), extraStorefronts("コネクト", "ClariS"))

        val dataDir = Files.createTempDirectory("pp-retry").toFile().apply { deleteOnExit() }.absolutePath
        val (db, repo) = newRepo()
        insertSong(db, 20, title = "分裂", artist = "周杰倫", duration = 252L)

        var itunesAttempts = 0
        val urls = mutableListOf<String>()
        val http = object : HttpFetcher {
            override suspend fun send(request: HttpRequest): HttpResponse {
                urls += request.url
                return when {
                    request.url.startsWith("https://api.deezer.com/search") ->
                        HttpResponse(200, mapOf("content-type" to "application/json"), """{"data":[]}""".encodeToByteArray())
                    request.url.startsWith("https://itunes.apple.com/search") -> {
                        itunesAttempts++
                        if (itunesAttempts == 1) {
                            HttpResponse(429, emptyMap(), ByteArray(0))
                        } else {
                            val body = """{"results":[{"trackName":"分裂","artistName":"周杰倫","collectionName":"八度空間","trackTimeMillis":252000,"artworkUrl100":"https://cdn.example/tw_100x100bb.jpg"}]}"""
                            HttpResponse(200, mapOf("content-type" to "application/json"), body.encodeToByteArray())
                        }
                    }
                    request.url == "https://cdn.example/tw_600x600bb.jpg" ->
                        HttpResponse(200, mapOf("content-type" to "image/jpeg"), jpegHeader)
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }
        val fakeTd = FakeTdNative { _, req ->
            if (req["@type"]!!.jsonPrimitive.content == "getMessage") {
                listOf(
                    buildJsonObject {
                        put("@type", "message")
                        req["@extra"]?.let { put("@extra", it) }
                        put("id", req["message_id"]!!.jsonPrimitive.long)
                        put("content", buildJsonObject {
                            put("@type", "messageAudio")
                            put("audio", buildJsonObject {})
                        })
                    },
                )
            } else {
                emptyList()
            }
        }
        val client = TdClient(TdRuntime(fakeTd, scope, Dispatchers.IO), config).also { it.start() }

        val covers = CoverService(
            dataDir = dataDir,
            td = { client },
            repo = repo,
            http = http,
            scope = scope,
            maxWorkers = 1,
            requestPauseMs = 0L,
            backgroundPauseMs = 0L,
            debounceMs = 0L,
        )
        covers.prioritize(listOf(repo.songByUniqueId("u20")!!))
        await { repo.songByUniqueId("u20")?.cover_path != null }

        val s20 = repo.songByUniqueId("u20")!!
        assertEquals("八度空間", s20.album)
        assertEquals("itunes", s20.metadata_source)
        assertTrue(urls.first { "itunes.apple.com" in it }.contains("country=TW"))
        assertEquals(2, itunesAttempts)
    }
}
