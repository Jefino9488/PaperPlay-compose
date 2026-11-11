package io.max.paperplay.core.app

import io.max.paperplay.core.backup.Fs
import io.max.paperplay.core.integrations.HttpFetcher
import io.max.paperplay.core.integrations.HttpRequest
import io.max.paperplay.core.integrations.HttpResponse
import io.max.paperplay.core.integrations.defaultHttpFetcher
import io.max.paperplay.core.integrations.urlWith
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.metadata.ExternalMatch
import io.max.paperplay.core.metadata.cleanTitle
import io.max.paperplay.core.metadata.extraStorefronts
import io.max.paperplay.core.metadata.firstArtist
import io.max.paperplay.core.metadata.pickExternal
import io.max.paperplay.core.metadata.searchTitle
import io.max.paperplay.core.metadata.userAgent
import io.max.paperplay.core.recs.TasteSettings
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Resolves cover images on demand (visible screens and active playback jump ahead of the backlog)
 * and drains the remaining library in bounded background workers.
 *
 * Waterfall per song: existing [Song.cover_url] -> MusicBrainz [Song.release_id] via Cover Art
 * Archive -> Telegram message `album_cover_thumbnail` / `thumbnail` -> Deezer -> iTunes.
 * Saved image bytes are copied into `${dataDir}/covers/` so clearing TDLib cache never breaks covers.
 */
class CoverService(
    private val dataDir: String,
    private val td: () -> TdClient?,
    private val repo: LibraryRepository,
    private val http: HttpFetcher = defaultHttpFetcher(),
    private val scope: CoroutineScope,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val maxWorkers: Int = 3,
    private val requestPauseMs: Long = 200L,
    private val backgroundPauseMs: Long = 250L,
    private val debounceMs: Long = 200L,
) {
    private val queueMutex = Mutex()
    private val deezerMutex = Mutex()
    private val itunesMutex = Mutex()
    private val highPriority = LinkedHashSet<String>()
    private val background = LinkedHashSet<String>()
    private val inFlight = HashSet<String>()
    private val attempted = HashSet<String>()
    private val awaitingTd = HashSet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val notifyWake = Channel<Unit>(Channel.CONFLATED)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var started = false
    @Volatile private var lastDeezerAt = 0L
    @Volatile private var lastItunesAt = 0L

    private fun coversDir(): String = "${dataDir.trimEnd('/')}/covers"

    private fun needsCover(song: Song): Boolean {
        val path = song.cover_path
        return path.isNullOrEmpty() || !Fs.exists(path)
    }

    private fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            for (signal in notifyWake) {
                if (debounceMs > 0L) delay(debounceMs)
                notifyWake.tryReceive()
                repo.invalidate()
            }
        }
        repeat(maxWorkers.coerceAtLeast(1)) {
            scope.launch { workerLoop() }
        }
    }

    /** Starts the background cover workers and queues any songs still missing cover files. */
    fun start() {
        ensureStarted()
        kick(retryTdMissing = true)
    }

    /** Queues [songs] ahead of background cover work so visible cards and playback populate immediately. */
    fun prioritize(songs: Collection<Song>) {
        val need = songs.filter(::needsCover).map { it.unique_id }
        if (need.isEmpty()) return
        ensureStarted()
        scope.launch {
            var enqueued = false
            queueMutex.withLock {
                for (uid in need) {
                    if (uid !in inFlight && uid !in attempted) {
                        awaitingTd.remove(uid)
                        background.remove(uid)
                        if (highPriority.add(uid)) enqueued = true
                    }
                }
            }
            if (enqueued) wake.trySend(Unit)
        }
    }

    /** Refreshes the background queue from [LibraryRepository.songsNeedingCovers]. */
    fun kick(retryTdMissing: Boolean = false) {
        ensureStarted()
        scope.launch {
            val missing = repo.songsNeedingCovers()
            var enqueued = false
            queueMutex.withLock {
                if (retryTdMissing && awaitingTd.isNotEmpty()) {
                    for (uid in awaitingTd) {
                        if (uid !in inFlight && uid !in attempted && background.add(uid)) enqueued = true
                    }
                    awaitingTd.clear()
                }
                for (song in missing) {
                    val uid = song.unique_id
                    if (uid !in inFlight && uid !in attempted && uid !in highPriority && uid !in awaitingTd) {
                        if (background.add(uid)) enqueued = true
                    }
                }
            }
            if (enqueued) wake.trySend(Unit)
        }
    }

    private suspend fun workerLoop() {
        while (currentCoroutineContext().isActive) {
            val item = nextWorkItem()
            if (item == null) {
                wake.receive()
                continue
            }
            val (uid, isHigh) = item
            try {
                if (!isHigh && backgroundPauseMs > 0L) {
                    val gap = effectiveBackgroundGapMs()
                    if (gap > 0L) delay(gap)
                }
                resolveOne(uid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Keep workers alive across unexpected IO/parse issues on one track.
            } finally {
                queueMutex.withLock { inFlight.remove(uid) }
            }
        }
    }

    private suspend fun nextWorkItem(): Pair<String, Boolean>? = queueMutex.withLock {
        val high = highPriority.firstOrNull()
        if (high != null) {
            highPriority.remove(high)
            inFlight.add(high)
            return@withLock high to true
        }
        val bg = background.firstOrNull()
        if (bg != null) {
            background.remove(bg)
            inFlight.add(bg)
            return@withLock bg to false
        }
        null
    }

    private fun effectiveBackgroundGapMs(): Long {
        val rpm = repo.pref("rate_covers")?.toLongOrNull()?.coerceIn(1L, 1200L)
        return if (rpm != null) 60_000L / rpm else backgroundPauseMs
    }

    private suspend fun markDone(uid: String, tdConnected: Boolean, saved: Boolean) {
        queueMutex.withLock {
            if (saved || tdConnected) attempted.add(uid) else awaitingTd.add(uid)
        }
    }

    private suspend fun resolveOne(uid: String) {
        val song = repo.songByUniqueId(uid) ?: run {
            markDone(uid, tdConnected = true, saved = false)
            return
        }
        if (!needsCover(song)) {
            markDone(uid, tdConnected = true, saved = true)
            return
        }

        // 1. Existing cover_url already stored on the row.
        val knownUrl = song.cover_url?.takeIf { it.isNotBlank() }
        if (knownUrl != null) {
            val bytes = fetchImageBytes(knownUrl)
            if (bytes != null) {
                val outPath = writeCoverFile(uid, bytes)
                if (outPath != null) {
                    repo.updateCover(uniqueId = uid, coverPath = outPath, notify = false)
                    notifyWake.trySend(Unit)
                    markDone(uid, tdConnected = true, saved = true)
                    return
                }
            }
        }

        // 2. MusicBrainz release_id via Cover Art Archive.
        val releaseId = song.release_id?.takeIf { it.isNotBlank() }
        if (releaseId != null) {
            val caaUrl = "https://coverartarchive.org/release/$releaseId/front-500"
            val bytes = fetchImageBytes(caaUrl)
            if (bytes != null) {
                val outPath = writeCoverFile(uid, bytes)
                if (outPath != null) {
                    repo.updateCover(
                        uniqueId = uid,
                        coverPath = outPath,
                        coverUrl = song.cover_url ?: caaUrl,
                        coverHiRes = true,
                        metadataSource = song.metadata_source ?: "musicbrainz",
                        enrichState = if (song.enrich_state == 0L) 1L else null,
                        notify = false,
                    )
                    notifyWake.trySend(Unit)
                    markDone(uid, tdConnected = true, saved = true)
                    return
                }
            }
        }

        // 3. Embedded Telegram album_cover_thumbnail / thumbnail via TDLib.
        val client = td()
        if (client != null && song.chat_id != 0L && song.message_id != 0L) {
            val thumbBytes = fetchTelegramThumbnail(client, song)
            if (thumbBytes != null) {
                val outPath = writeCoverFile(uid, thumbBytes)
                if (outPath != null) {
                    repo.updateCover(uniqueId = uid, coverPath = outPath, notify = false)
                    notifyWake.trySend(Unit)
                    markDone(uid, tdConnected = true, saved = true)
                    return
                }
            }
        }

        // 4. External catalog lookups (Deezer -> iTunes), when enabled.
        val taste = TasteSettings.parse(repo.pref(TasteSettings.PREF_KEY))
        if (taste.lookups) {
            val match = findExternalMatch(song)
            val coverUrl = match?.coverUrl?.takeIf { it.isNotBlank() }
            if (match != null && coverUrl != null) {
                val bytes = fetchImageBytes(coverUrl)
                if (bytes != null) {
                    val outPath = writeCoverFile(uid, bytes)
                    if (outPath != null) {
                        saveExternalCover(song, match, coverUrl, outPath)
                        notifyWake.trySend(Unit)
                        markDone(uid, tdConnected = client != null, saved = true)
                        return
                    }
                }
            }
        }

        markDone(uid, tdConnected = client != null, saved = false)
    }

    private suspend fun fetchTelegramThumbnail(client: TdClient, song: Song): ByteArray? {
        val msg = withTimeoutOrNull(10.seconds) {
            runCatchingCancellable {
                client.call(request("getMessage") {
                    put("chat_id", song.chat_id)
                    put("message_id", song.message_id)
                })
            }.getOrNull()
        } ?: return null
        val thumb = thumbFileOfMessage(msg) ?: return null
        val localPath = downloadTdFile(client, thumb) ?: return null
        val bytes = readWholeFile(localPath) ?: return null
        return bytes.takeIf { looksLikeImage(it, null) }
    }

    private suspend fun downloadTdFile(client: TdClient, fileObj: JsonObject): String? {
        val local = fileObj.obj("local")
        val existingPath = local?.str("path")?.takeIf { it.isNotEmpty() }
        if (local?.bool("is_downloading_completed") == true && existingPath != null && Fs.exists(existingPath)) {
            return existingPath
        }
        val fileId = fileObj.long("id")?.toInt()?.takeIf { it != 0 } ?: return null
        val downloaded = withTimeoutOrNull(12.seconds) {
            runCatchingCancellable {
                client.call(request("downloadFile") {
                    put("file_id", fileId)
                    put("priority", 24)
                    put("offset", 0)
                    put("limit", 0)
                    put("synchronous", true)
                })
            }.getOrNull()
        } ?: return null

        val afterLocal = downloaded.obj("local")
        val afterPath = afterLocal?.str("path")?.takeIf { it.isNotEmpty() }
        if (afterLocal?.bool("is_downloading_completed") == true && afterPath != null) {
            return afterPath
        }

        val updated = withTimeoutOrNull(6.seconds) {
            client.updates.first { u ->
                val f = u.takeIf { it.type == "updateFile" }?.obj("file") ?: return@first false
                f.long("id")?.toInt() == fileId && f.obj("local")?.bool("is_downloading_completed") == true
            }.obj("file")
        }
        return updated?.obj("local")?.str("path")?.takeIf { it.isNotEmpty() }
    }

    private suspend fun findExternalMatch(song: Song): ExternalMatch? {
        val cleanT = searchTitle(cleanTitle(song.title, song.artist))
        if (cleanT.isBlank()) return null
        val cleanA = if (song.artist == "Unknown artist") "" else firstArtist(song.artist)
        val term = "$cleanT $cleanA".trim()

        searchDeezer(song, term)?.let { return it }
        return searchItunes(song, term)
    }

    private suspend fun searchDeezer(song: Song, term: String): ExternalMatch? {
        paceDeezer()
        val url = urlWith("https://api.deezer.com/search", mapOf("q" to term, "limit" to "10"))
        val res = sendHttp(url) ?: return null
        if (res.status != 200) return null
        val root = runCatching { json.parseToJsonElement(res.text) as? JsonObject }.getOrNull() ?: return null
        val items = (root["data"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val matches = items.map { ExternalMatch.fromDeezer(it) }
        return pickExternal(matches, song.title, song.artist, song.duration_sec.toInt())
            ?.takeIf { !it.coverUrl.isNullOrBlank() }
    }

    private suspend fun searchItunes(song: Song, term: String): ExternalMatch? {
        val storefronts = listOf<String?>(null) + extraStorefronts(song.title, song.artist)
        for (country in storefronts) {
            paceItunes()
            val params = buildMap {
                put("term", term)
                put("media", "music")
                put("entity", "song")
                put("limit", "10")
                if (country != null) put("country", country)
            }
            val url = urlWith("https://itunes.apple.com/search", params)
            val res = sendHttp(url) ?: continue
            if (res.status != 200) continue
            val root = runCatching { json.parseToJsonElement(res.text) as? JsonObject }.getOrNull() ?: continue
            val items = (root["results"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val matches = items.map { ExternalMatch.fromItunes(it) }
            val match = pickExternal(matches, song.title, song.artist, song.duration_sec.toInt())
                ?.takeIf { !it.coverUrl.isNullOrBlank() }
            if (match != null) return match
        }
        return null
    }

    private suspend fun paceDeezer() {
        if (requestPauseMs <= 0L) return
        deezerMutex.withLock {
            val wait = (lastDeezerAt + requestPauseMs) - clock()
            if (wait > 0L) delay(wait)
            lastDeezerAt = clock()
        }
    }

    private suspend fun paceItunes() {
        if (requestPauseMs <= 0L) return
        val minGap = maxOf(requestPauseMs * 6L, 1200L)
        itunesMutex.withLock {
            val wait = (lastItunesAt + minGap) - clock()
            if (wait > 0L) delay(wait)
            lastItunesAt = clock()
        }
    }

    private suspend fun fetchImageBytes(url: String): ByteArray? {
        val retryDelays = if (requestPauseMs > 0L) listOf(1000L) else emptyList()
        var attempt = 0
        while (true) {
            val res = sendHttp(url) ?: return null
            if (res.status == 200) {
                return res.body.takeIf { looksLikeImage(it, res.headers["content-type"]) }
            }
            if ((res.status == 429 || res.status >= 500) && attempt < retryDelays.size) {
                delay(retryDelays[attempt++])
                continue
            }
            return null
        }
    }

    private suspend fun sendHttp(url: String): HttpResponse? = runCatchingCancellable {
        http.send(HttpRequest(url, headers = mapOf("User-Agent" to userAgent())))
    }.getOrNull()

    private fun saveExternalCover(song: Song, match: ExternalMatch, coverUrl: String, outPath: String) {
        val keepUserTags = song.user_edited
        val newAlbum = if (!keepUserTags && song.album.isBlank()) match.album.takeIf { it.isNotBlank() } else null
        val newYear = if (!keepUserTags && song.year == null) match.year?.toLong() else null
        val newGenre = if (!keepUserTags && song.genre.isNullOrBlank()) match.genre?.takeIf { it.isNotBlank() } else null
        val newSource = if (song.metadata_source.isNullOrBlank()) match.source.lowercase() else null
        repo.updateCover(
            uniqueId = song.unique_id,
            coverPath = outPath,
            coverUrl = coverUrl,
            coverHiRes = true,
            album = newAlbum,
            year = newYear,
            genre = newGenre,
            metadataSource = newSource,
            enrichState = if (song.enrich_state == 0L) 1L else null,
            notify = false,
        )
    }

    private fun writeCoverFile(uid: String, bytes: ByteArray): String? {
        val safe = uid.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = coversDir()
        val path = "$dir/${safe}_${clock()}.jpg"
        return runCatching {
            Fs.mkdirs(dir)
            Fs.write(path, bytes)
            path
        }.getOrNull()
    }

    private fun readWholeFile(path: String): ByteArray? {
        val chunkSize = 64 * 1024
        val chunks = ArrayList<ByteArray>()
        var offset = 0L
        var total = 0
        while (total < MAX_THUMB_BYTES) {
            val part = runCatching { readRange(path, offset, chunkSize) }.getOrNull() ?: return null
            if (part.isEmpty()) break
            chunks.add(part)
            total += part.size
            offset += part.size
            if (part.size < chunkSize) break
        }
        if (total == 0) return null
        val out = ByteArray(total)
        var pos = 0
        for (c in chunks) {
            c.copyInto(out, pos)
            pos += c.size
        }
        return out
    }

    private companion object {
        const val MAX_THUMB_BYTES = 10 * 1024 * 1024
    }
}

private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    Result.failure(t)
}

private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

private fun looksLikeImage(bytes: ByteArray, contentType: String?): Boolean {
    if (bytes.size < 4) return false
    if (contentType != null) {
        val mime = contentType.substringBefore(';').trim().lowercase()
        if (mime.startsWith("image/")) return true
        if (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")) return false
    }
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    val b2 = bytes[2].toInt() and 0xFF
    val b3 = bytes[3].toInt() and 0xFF
    if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true // JPEG
    if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true // PNG
    if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return true // GIF
    return bytes.size >= 12 &&
        b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() // WebP
}
