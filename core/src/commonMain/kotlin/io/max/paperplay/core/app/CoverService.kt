package io.max.paperplay.core.app

import io.max.paperplay.core.backup.Fs
import io.max.paperplay.core.integrations.HttpFetcher
import io.max.paperplay.core.integrations.defaultHttpFetcher
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.metadata.CoverImage
import io.max.paperplay.core.metadata.CoverQuery
import io.max.paperplay.core.metadata.CoverSources
import io.max.paperplay.core.metadata.CoverWork
import io.max.paperplay.core.metadata.coverWorkFor
import io.max.paperplay.core.metadata.ExternalMatch
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val maxWorkers: Int = 1,
    private val requestPauseMs: Long = 200L,
    private val backgroundPauseMs: Long = 1500L,
    private val debounceMs: Long = 200L,
    private val startDelayMs: Long = 30_000L,
    /** The client that can read [song]'s post when it is not the main one (a song only an extra bot can read). */
    private val tdFor: (Song) -> TdClient? = { null },
) {
    private val queueMutex = Mutex()
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
    private val sources = CoverSources(http, clock)
    private val upgradeAsked = HashSet<String>()
    private val albumLocks = HashMap<String, Mutex>()
    private val albumLocksMutex = Mutex()
    private val giveUpMutex = Mutex()
    private var giveUps: MutableMap<String, Long>? = null
    private var giveUpDirty = 0
    private val _queued = MutableStateFlow(0)

    /** Covers waiting to load (on screen first, then the library backlog), for the progress UI. */
    val queued: StateFlow<Int> = _queued

    @Volatile private var started = false
    @Volatile private var startQueued = false
    @Volatile private var nextTdAt = 0L
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
                repo.invalidateSoon()
            }
        }
        repeat(maxWorkers.coerceAtLeast(1)) {
            scope.launch { workerLoop() }
        }
    }

    /** Starts the background cover workers and queues any songs still missing cover files. */
    fun start() {
        ensureStarted()
        if (startQueued) return
        startQueued = true
        // The backlog is queued after a quiet half minute; screens ask for what they show before that.
        scope.launch {
            delay(startDelayMs)
            kick(retryTdMissing = true)
        }
    }

    /** Queues [songs] ahead of background cover work so visible cards and playback populate immediately. */
    fun prioritize(songs: Collection<Song>) {
        val need = songs.filter { needsCover(it) || coverWorkFor(it) == CoverWork.Upgrade }.map { it.unique_id }
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
            _queued.value = highPriority.size + background.size
            if (enqueued) wake.trySend(Unit)
        }
    }

    /** Asks for the cover of a song on screen or playing; a thumbnail with a full-size source is upgraded too. */
    fun requestCover(song: Song) = prioritize(listOf(song))

    /**
     * Queues the whole library: songs with no cover first, then thumbnails that can be upgraded to
     * full size. Songs no source had a cover for are left alone for a week. Songs on screen still jump ahead.
     */
    fun coverAll() = kick(retryTdMissing = true)

    /** Tries these songs again even if nothing was found for them recently. */
    fun retrySongs(songs: Collection<Song>) {
        ensureStarted()
        scope.launch {
            val map = giveUpMap()
            giveUpMutex.withLock { songs.forEach { map.remove(it.unique_id) } }
            queueMutex.withLock {
                songs.forEach { attempted.remove(it.unique_id); rateLimited.remove(it.unique_id) }
            }
            persistGiveUps()
            prioritize(songs)
        }
    }

    private suspend fun giveUpMap(): MutableMap<String, Long> = giveUpMutex.withLock {
        giveUps ?: HashMap<String, Long>().also { m ->
            runCatching {
                (json.parseToJsonElement(repo.pref(GIVE_UP_KEY) ?: "{}") as? JsonObject)?.forEach { (k, v) ->
                    (v as? JsonPrimitive)?.content?.toLongOrNull()?.let { m[k] = it }
                }
            }
            giveUps = m
        }
    }

    private suspend fun gaveUpRecently(uid: String): Boolean {
        val at = giveUpMap()[uid] ?: return false
        return clock() - at < GIVE_UP_MS
    }

    private suspend fun recordGiveUp(uid: String, found: Boolean) {
        val map = giveUpMap()
        val flush = giveUpMutex.withLock {
            if (found) { if (map.remove(uid) != null) giveUpDirty++ } else { map[uid] = clock(); giveUpDirty++ }
            giveUpDirty >= 25
        }
        if (flush) persistGiveUps()
    }

    private suspend fun persistGiveUps() {
        val text = giveUpMutex.withLock {
            giveUpDirty = 0
            val m = giveUps ?: return
            JsonObject(m.mapValues { JsonPrimitive(it.value) }).toString()
        }
        runCatching { repo.setPref(GIVE_UP_KEY, text) }
    }

    /** Refreshes the background queue from [LibraryRepository.songsNeedingCovers]. */
    fun kick(retryTdMissing: Boolean = false) {
        ensureStarted()
        scope.launch {
            val missing = repo.coverCandidateIds().filter { !gaveUpRecently(it) }
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
                for (uid in missing) {
                    if (uid !in inFlight && uid !in attempted && uid !in highPriority && uid !in awaitingTd && uid !in rateLimited) {
                        if (background.add(uid)) enqueued = true
                    }
                }
                _queued.value = highPriority.size + background.size
            }
            if (enqueued) wake.trySend(Unit)
        }
    }

    private suspend fun workerLoop() {
        while (currentCoroutineContext().isActive) {
            // Background work waits while the song-info run is going; screens and playback do not.
            val item = nextWorkItem(allowBackground = !repo.enrichRunning.value)
            if (item == null) {
                if (repo.enrichRunning.value) withTimeoutOrNull(2.seconds) { wake.receive() } else wake.receive()
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

    private suspend fun nextWorkItem(allowBackground: Boolean = true): Pair<String, Boolean>? = queueMutex.withLock {
        val high = highPriority.firstOrNull()
        if (high != null) {
            highPriority.remove(high)
            inFlight.add(high)
            _queued.value = highPriority.size + background.size
            return@withLock high to true
        }
        val bg = if (allowBackground) background.firstOrNull() else null
        if (bg != null) {
            background.remove(bg)
            inFlight.add(bg)
            _queued.value = highPriority.size + background.size
            return@withLock bg to false
        }
        if (allowBackground) _queued.value = 0
        null
    }

    private fun effectiveBackgroundGapMs(): Long {
        val rpm = repo.pref("rate_covers")?.toLongOrNull()?.coerceIn(1L, 1200L)
        return if (rpm != null) 60_000L / rpm else backgroundPauseMs
    }

    private enum class Outcome { Done, NeedsTd, RetryLater }

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
        // Tracks of one album are looked up one at a time, so the first finds the art and shares it.
        val key = CoverSources.albumKey(song.album, song.artist)
        if (key == null) {
            resolveSong(uid)
            return
        }
        val lock = albumLocksMutex.withLock {
            if (albumLocks.size > 2000) albumLocks.clear()
            albumLocks.getOrPut(key) { Mutex() }
        }
        lock.withLock { resolveSong(uid) }
    }

    private fun queryOf(song: Song) = CoverQuery(
        title = song.title, artist = song.artist, album = song.album, durationSec = song.duration_sec.toInt(),
        releaseId = song.release_id?.takeIf { it.isNotBlank() }, coverUrl = song.cover_url?.takeIf { it.isNotBlank() },
    )

    private suspend fun resolveSong(uid: String) {
        // Read again: a sibling track may have shared a cover while this waited.
        val song = repo.songByUniqueId(uid) ?: run {
            markDone(uid, Outcome.Done)
            return
        }
        val lookups = TasteSettings.parse(repo.pref(TasteSettings.PREF_KEY)).lookups
        val asked = queueMutex.withLock { upgradeAsked.remove(uid) }
        if (!needsCover(song)) {
            if (coverWorkFor(song) == CoverWork.Upgrade || (asked && lookups && !song.cover_hi_res && !song.user_edited && song.cover_at == 0L)) {
                upgradeOne(song, lookups)
            }
            markDone(uid, Outcome.Done)
            return
        }

        // 0. Another track of the album already has the full-size cover.
        if (shareFromAlbum(song)) {
            markDone(uid, Outcome.Done)
            return
        }

        // 1. Stored sources: the saved link, then MusicBrainz release art (falling back to the album's art).
        val hasSource = song.release_id != null || !song.cover_url.isNullOrBlank()
        if (hasSource) {
            val img = runCatchingCancellable { sources.find(queryOf(song), lookups = false) }.getOrNull()
            if (img != null && saveImage(song, img)) {
                markDone(uid, Outcome.Done)
                return
            }
        }

        // 2. Embedded Telegram album_cover_thumbnail / thumbnail via TDLib (skipped while Scanner is active).
        val client = botOwnerOf(song.remote_id)?.let { tdFor(song) } ?: td()
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
                    recordGiveUp(uid, found = true)
                    markDone(uid, Outcome.Done)
                    // The thumbnail is only a stand-in; the full-size cover comes right after.
                    if (lookups) queueUpgrade(uid)
                    return
                }
            }
        }

        // 3. Search services, all asked at once: Deezer and iTunes (the stored sources were tried already).
        var httpRetryMs = 0L
        if (lookups) {
            val img = runCatchingCancellable {
                sources.best(sources.candidates(queryOf(song), lookups = true, includeStored = false))
            }.getOrNull()
            if (img != null && saveImage(song, img)) {
                markDone(uid, Outcome.Done)
                return
            }
            if (sources.itunesBlocked) httpRetryMs = 30_000L
        }

        when {
            client == null -> markDone(uid, Outcome.NeedsTd)
            tdRateLimited -> markDone(uid, Outcome.RetryLater, maxOf(1_000L, tdBlockedUntil - clock()))
            httpRetryMs > 0L -> markDone(uid, Outcome.RetryLater, httpRetryMs)
            else -> {
                recordGiveUp(uid, found = false)
                markDone(uid, Outcome.Done)
            }
        }
    }

    /** After a thumbnail is saved, the same song is looked at again for a full-size cover. */
    private suspend fun queueUpgrade(uid: String) {
        val added = queueMutex.withLock {
            attempted.remove(uid)
            upgradeAsked.add(uid)
            val ok = uid !in inFlight && background.add(uid)
            _queued.value = highPriority.size + background.size
            ok
        }
        if (added) wake.trySend(Unit)
    }

    /** Replaces a thumbnail with the best full-size cover any source has. The thumbnail stays if none does. */
    private suspend fun upgradeOne(song: Song, lookups: Boolean) {
        if (shareFromAlbum(song)) return
        val img = runCatchingCancellable { sources.find(queryOf(song), lookups = lookups) }.getOrNull()
        if (img == null) {
            recordGiveUp(song.unique_id, found = false)
            return
        }
        saveImage(song, img)
    }

    /**
     * Writes [img] as [song]'s cover and shares it, with the album info the match carried, to the other
     * tracks of the same album. An old thumbnail file is removed a little later, once screens let go of it.
     */
    private suspend fun saveImage(song: Song, img: CoverImage): Boolean {
        val uid = song.unique_id
        val outPath = writeCoverFile(uid, img.bytes) ?: return false
        val match = img.match
        if (match != null) {
            saveExternalCover(song, match, img.url, outPath)
        } else {
            repo.updateCover(
                uniqueId = uid,
                coverPath = outPath,
                coverUrl = song.cover_url ?: img.url,
                coverHiRes = true,
                metadataSource = song.metadata_source ?: "musicbrainz",
                enrichState = if (song.enrich_state == 0L && song.release_id != null) 1L else null,
                notify = false,
            )
        }
        deleteLater(song.cover_path, outPath)
        recordGiveUp(uid, found = true)
        shareToAlbum(song, img.bytes, img.url, match)
        notifyWake.trySend(Unit)
        return true
    }

    private fun deleteLater(old: String?, keep: String) {
        if (old.isNullOrEmpty() || old == keep) return
        scope.launch {
            delay(10_000L)
            runCatching { Fs.deleteRecursively(old) }
        }
    }

    /** Tracks of [song]'s album, other than itself. */
    private fun siblings(song: Song, album: String = song.album): List<Song> {
        val key = CoverSources.albumKey(album, song.artist) ?: return emptyList()
        return repo.albumSongs(album).filter { it.unique_id != song.unique_id && CoverSources.albumKey(it.album, it.artist) == key }
    }

    /** Copies a full-size cover from another track of the album when it has one. */
    private fun shareFromAlbum(song: Song): Boolean {
        val donor = siblings(song).firstOrNull { s ->
            val p = s.cover_path
            s.cover_hi_res && !p.isNullOrEmpty() && Fs.exists(p) && s.cover_at == 0L
        } ?: return false
        val bytes = runCatching { readWholeFile(donor.cover_path!!) }.getOrNull() ?: return false
        val out = writeCoverFile(song.unique_id, bytes) ?: return false
        repo.updateCover(uniqueId = song.unique_id, coverPath = out, coverUrl = donor.cover_url, coverHiRes = true, notify = false)
        deleteLater(song.cover_path, out)
        notifyWake.trySend(Unit)
        return true
    }

    /**
     * Gives the other tracks of the album the same cover when they lack one or hold only a thumbnail
     * (never a cover someone chose), and fills the album's year and genre where they are blank.
     */
    private suspend fun shareToAlbum(song: Song, bytes: ByteArray, url: String, match: ExternalMatch?) {
        val album = song.album.ifBlank { match?.album.orEmpty() }
        for (s in siblings(song, album)) {
            val wants = needsCover(s) || coverWorkFor(s) == CoverWork.Upgrade
            val free = !s.user_edited && s.cover_at == 0L
            val out = if (wants && free) writeCoverFile(s.unique_id, bytes) else null
            val year = if (!s.user_edited && s.year == null) match?.year?.toLong() else null
            val genre = if (!s.user_edited && s.genre.isNullOrBlank()) match?.genre?.takeIf { it.isNotBlank() } else null
            if (out == null && year == null && genre == null) continue
            repo.updateCover(
                uniqueId = s.unique_id, coverPath = out, coverUrl = if (out != null) url else null,
                coverHiRes = if (out != null) true else null, year = year, genre = genre, notify = false,
            )
            if (out != null) {
                deleteLater(s.cover_path, out)
                queueMutex.withLock {
                    attempted.add(s.unique_id); background.remove(s.unique_id); highPriority.remove(s.unique_id)
                }
            }
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
        const val GIVE_UP_KEY = "covers_nofind"
        const val GIVE_UP_MS = 7L * 24 * 60 * 60 * 1000
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
