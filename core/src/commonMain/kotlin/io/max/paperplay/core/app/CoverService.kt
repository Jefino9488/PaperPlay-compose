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
import io.max.paperplay.core.telegram.TdError
import io.max.paperplay.core.telegram.request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val tdMutex = Mutex()
    private val highPriority = LinkedHashSet<String>()
    private val background = LinkedHashSet<String>()
    private val inFlight = HashSet<String>()
    private val attempted = HashSet<String>()
    private val awaitingTd = LinkedHashSet<String>()
    private val rateLimited = LinkedHashSet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val notifyWake = Channel<Unit>(Channel.CONFLATED)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var started = false
    @Volatile private var nextDeezerAt = 0L
    @Volatile private var nextItunesAt = 0L
    @Volatile private var nextTdAt = 0L
    @Volatile private var itunesBlockedUntil = 0L
    @Volatile private var tdBlockedUntil = 0L
    private var retryJob: Job? = null

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
                        if (uid !in inFlight && uid !in attempted && highPriority.add(uid)) {
                            background.remove(uid)
                            enqueued = true
                        }
                    }
                    awaitingTd.clear()
                }
                for (song in missing) {
                    val uid = song.unique_id
                    if (uid !in inFlight && uid !in attempted && uid !in highPriority && uid !in awaitingTd && uid !in rateLimited) {
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

    private enum class Outcome { Done, NeedsTd, RetryLater }

    private sealed class LookupResult {
        data class Found(val match: ExternalMatch) : LookupResult()
        data object Missing : LookupResult()
        data class RetryAfter(val delayMs: Long) : LookupResult()
    }

    private suspend fun markDone(uid: String, outcome: Outcome, retryDelayMs: Long = 0L) {
        queueMutex.withLock {
            when (outcome) {
                Outcome.Done -> {
                    attempted.add(uid)
                    awaitingTd.remove(uid)
                    rateLimited.remove(uid)
                }
                Outcome.NeedsTd -> awaitingTd.add(uid)
                Outcome.RetryLater -> {
                    rateLimited.add(uid)
                    val waitMs = retryDelayMs.coerceAtLeast(if (requestPauseMs > 0L) 1_000L else 40L)
                    if (retryJob?.isActive != true) {
                        retryJob = scope.launch {
                            delay(waitMs)
                            drainRateLimited()
                        }
                    }
                }
            }
        }
    }

    private suspend fun drainRateLimited() {
        var drained = false
        queueMutex.withLock {
            if (rateLimited.isNotEmpty()) {
                val items = rateLimited.toList()
                rateLimited.clear()
                for (uid in items) {
                    if (uid !in inFlight && uid !in attempted && highPriority.add(uid)) drained = true
                }
            }
        }
        if (drained) wake.trySend(Unit)
    }

    private suspend fun resolveOne(uid: String) {
        val song = repo.songByUniqueId(uid) ?: run {
            markDone(uid, Outcome.Done)
            return
        }
        if (!needsCover(song)) {
            markDone(uid, Outcome.Done)
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
                    markDone(uid, Outcome.Done)
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
                    markDone(uid, Outcome.Done)
                    return
                }
            }
        }

        // 3. Embedded Telegram album_cover_thumbnail / thumbnail via TDLib (skipped while Scanner is active).
        val client = td()
        var tdRateLimited = false
        if (client != null && song.chat_id != 0L && song.message_id != 0L) {
            val thumbResult = fetchTelegramThumbnail(client, song)
            if (thumbResult.rateLimited) {
                tdRateLimited = true
            } else if (thumbResult.bytes != null) {
                val outPath = writeCoverFile(uid, thumbResult.bytes)
                if (outPath != null) {
                    repo.updateCover(uniqueId = uid, coverPath = outPath, notify = false)
                    notifyWake.trySend(Unit)
                    markDone(uid, Outcome.Done)
                    return
                }
            }
        }

        // 4. External catalog lookups (Deezer -> iTunes), when enabled.
        val taste = TasteSettings.parse(repo.pref(TasteSettings.PREF_KEY))
        var httpRetryMs = 0L
        if (taste.lookups) {
            when (val res = findExternalMatch(song)) {
                is LookupResult.Found -> {
                    val coverUrl = res.match.coverUrl?.takeIf { it.isNotBlank() }
                    if (coverUrl != null) {
                        val bytes = fetchImageBytes(coverUrl)
                        if (bytes != null) {
                            val outPath = writeCoverFile(uid, bytes)
                            if (outPath != null) {
                                saveExternalCover(song, res.match, coverUrl, outPath)
                                notifyWake.trySend(Unit)
                                markDone(uid, Outcome.Done)
                                return
                            }
                        }
                    }
                }
                is LookupResult.RetryAfter -> httpRetryMs = res.delayMs
                LookupResult.Missing -> Unit
            }
        }

        when {
            client == null -> markDone(uid, Outcome.NeedsTd)
            tdRateLimited -> markDone(uid, Outcome.RetryLater, maxOf(1_000L, tdBlockedUntil - clock()))
            httpRetryMs > 0L -> markDone(uid, Outcome.RetryLater, httpRetryMs)
            else -> markDone(uid, Outcome.Done)
        }
    }

    private class TdThumbResult(val bytes: ByteArray? = null, val rateLimited: Boolean = false)

    private suspend fun fetchTelegramThumbnail(client: TdClient, song: Song): TdThumbResult {
        if (!reserveTdSlot()) return TdThumbResult(rateLimited = true)
        val msgRes = withTimeoutOrNull(10.seconds) {
            runCatchingCancellable {
                client.call(request("getMessage") {
                    put("chat_id", song.chat_id)
                    put("message_id", song.message_id)
                })
            }
        } ?: return TdThumbResult()
        val msgErr = msgRes.exceptionOrNull()
        if (msgErr is TdError && msgErr.code == 429) {
            recordTdRateLimit(msgErr.message)
            return TdThumbResult(rateLimited = true)
        }
        val msg = msgRes.getOrNull() ?: return TdThumbResult()
        val thumb = thumbFileOfMessage(msg) ?: return TdThumbResult()
        val localPath = downloadTdFile(client, thumb) ?: return TdThumbResult(rateLimited = clock() < tdBlockedUntil)
        val bytes = readWholeFile(localPath) ?: return TdThumbResult()
        return TdThumbResult(bytes = bytes.takeIf { looksLikeImage(it, null) })
    }

    private suspend fun reserveTdSlot(): Boolean {
        if (clock() < tdBlockedUntil) return false
        if (requestPauseMs <= 0L) return true
        val minGap = maxOf(requestPauseMs * 2L, 400L)
        val waitMs = tdMutex.withLock {
            if (clock() < tdBlockedUntil) return@withLock -1L
            val slotAt = maxOf(clock(), nextTdAt)
            nextTdAt = slotAt + minGap
            slotAt - clock()
        }
        if (waitMs < 0L) return false
        if (waitMs > 0L) delay(waitMs)
        return clock() >= tdBlockedUntil
    }

    private fun recordTdRateLimit(message: String) {
        val sec = Regex("retry after (\\d+)").find(message)?.groupValues?.get(1)?.toLongOrNull() ?: 15L
        tdBlockedUntil = maxOf(tdBlockedUntil, clock() + sec * 1000L)
    }

    private suspend fun downloadTdFile(client: TdClient, fileObj: JsonObject): String? {
        val local = fileObj.obj("local")
        val existingPath = local?.str("path")?.takeIf { it.isNotEmpty() }
        if (local?.bool("is_downloading_completed") == true && existingPath != null && Fs.exists(existingPath)) {
            return existingPath
        }
        val fileId = fileObj.long("id")?.toInt()?.takeIf { it != 0 } ?: return null
        val dlRes = withTimeoutOrNull(12.seconds) {
            runCatchingCancellable {
                client.call(request("downloadFile") {
                    put("file_id", fileId)
                    put("priority", 24)
                    put("offset", 0)
                    put("limit", 0)
                    put("synchronous", true)
                })
            }
        } ?: return null
        val dlErr = dlRes.exceptionOrNull()
        if (dlErr is TdError && dlErr.code == 429) recordTdRateLimit(dlErr.message)
        val downloaded = dlRes.getOrNull() ?: return null

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

    private suspend fun findExternalMatch(song: Song): LookupResult {
        val cleanT = searchTitle(cleanTitle(song.title, song.artist))
        if (cleanT.isBlank()) return LookupResult.Missing
        val cleanA = if (song.artist == "Unknown artist") "" else firstArtist(song.artist)
        val term = "$cleanT $cleanA".trim()

        searchDeezer(song, term)?.let { return LookupResult.Found(it) }
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

    private suspend fun searchItunes(song: Song, term: String): LookupResult {
        val extra = extraStorefronts(song.title, song.artist)
        val primaryIsAsian = extra.firstOrNull() in setOf("JP", "TW")
        val storefronts = if (primaryIsAsian) extra.map<String, String?> { it } + null else listOf<String?>(null) + extra
        var sawError = false
        for (country in storefronts) {
            if (!reserveItunesSlot()) {
                return LookupResult.RetryAfter((itunesBlockedUntil - clock()).coerceAtLeast(50L))
            }
            val params = buildMap {
                put("term", term)
                put("media", "music")
                put("entity", "song")
                put("limit", "10")
                if (country != null) put("country", country)
            }
            val url = urlWith("https://itunes.apple.com/search", params)
            val res = sendHttp(url) ?: run { sawError = true; continue }
            if (res.status == 403 || res.status == 429) {
                val cooldown = if (requestPauseMs > 0L) 30_000L else 50L
                itunesBlockedUntil = maxOf(itunesBlockedUntil, clock() + cooldown)
                return LookupResult.RetryAfter(cooldown)
            }
            if (res.status != 200) { sawError = true; continue }
            val root = runCatching { json.parseToJsonElement(res.text) as? JsonObject }.getOrNull() ?: continue
            val items = (root["results"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val matches = items.map { ExternalMatch.fromItunes(it) }
            val match = pickExternal(matches, song.title, song.artist, song.duration_sec.toInt())
                ?.takeIf { !it.coverUrl.isNullOrBlank() }
            if (match != null) return LookupResult.Found(match)
        }
        return if (sawError) LookupResult.RetryAfter(if (requestPauseMs > 0L) 10_000L else 40L) else LookupResult.Missing
    }

    private suspend fun paceDeezer() {
        if (requestPauseMs <= 0L) return
        val waitMs = deezerMutex.withLock {
            val slotAt = maxOf(clock(), nextDeezerAt)
            nextDeezerAt = slotAt + requestPauseMs
            slotAt - clock()
        }
        if (waitMs > 0L) delay(waitMs)
    }

    private suspend fun reserveItunesSlot(): Boolean {
        if (clock() < itunesBlockedUntil) return false
        if (requestPauseMs <= 0L) return true
        val minGap = maxOf(requestPauseMs * 15L, 3100L)
        val waitMs = itunesMutex.withLock {
            if (clock() < itunesBlockedUntil) return@withLock -1L
            val slotAt = maxOf(clock(), nextItunesAt)
            nextItunesAt = slotAt + minGap
            slotAt - clock()
        }
        if (waitMs < 0L) return false
        if (waitMs > 0L) delay(waitMs)
        return clock() >= itunesBlockedUntil
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
