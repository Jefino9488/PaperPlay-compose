package io.max.paperplay.core.sync.netplay

import io.max.paperplay.core.sync.arr
import io.max.paperplay.core.sync.flag
import io.max.paperplay.core.sync.lng
import io.max.paperplay.core.sync.obj
import io.max.paperplay.core.sync.reqLng
import io.max.paperplay.core.sync.reqStr
import io.max.paperplay.core.sync.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

/**
 * Estimates the host clock from ping and pong pairs. Each sample gives an offset (host clock minus this clock) and a round
 * trip; the sample with the smallest round trip of the last [keep] is the most trustworthy, so its offset is used.
 */
class ClockSync(private val keep: Int = 8) {
    private class Sample(val rtt: Long, val offset: Long)

    private val lock = Any()
    private val samples = ArrayList<Sample>()

    /** [sentLocal] is this clock when the ping left, [hostTime] the host clock in the pong, [recvLocal] this clock when it arrived. */
    fun add(sentLocal: Long, hostTime: Long, recvLocal: Long) {
        val rtt = recvLocal - sentLocal
        if (rtt < 0) return
        synchronized(lock) {
            samples.add(Sample(rtt, hostTime - (sentLocal + rtt / 2)))
            while (samples.size > keep) samples.removeAt(0)
        }
    }

    private fun best(): Sample? = synchronized(lock) { samples.minByOrNull { it.rtt } }

    val offsetMs: Long get() = best()?.offset ?: 0L
    val rttMs: Long get() = best()?.rtt ?: 0L
    val ready: Boolean get() = synchronized(lock) { samples.isNotEmpty() }

    /** The host clock when this clock reads [localNow]. */
    fun hostNow(localNow: Long): Long = localNow + offsetMs
}

/** What a guest should do to follow the host. */
data class GuestStep(val load: Boolean = false, val seekTo: Long? = null, val setPlaying: Boolean? = null) {
    val idle get() = !load && seekTo == null && setPlaying == null
}

object Drift {
    /** A guest seeks when it is further than this from where the host should be now. */
    const val THRESHOLD_MS = 300L

    /** Where the host is at [hostNow]: its stamped position, moved on by the time since when it is playing. */
    fun expectedPosition(s: HostState, hostNow: Long): Long =
        if (s.playing) s.positionMs + (hostNow - s.hostTimeMs).coerceAtLeast(0) else s.positionMs

    fun plan(host: HostState?, hostNow: Long, local: LocalPlay, thresholdMs: Long = THRESHOLD_MS): GuestStep {
        if (host == null) return GuestStep()
        val song = host.song ?: return GuestStep()
        if (local.songId != song.id) return GuestStep(load = true)
        if (local.loading) return GuestStep()
        var expected = expectedPosition(host, hostNow).coerceAtLeast(0)
        if (song.durationMs > 0) expected = expected.coerceAtMost(song.durationMs)
        val off = local.positionMs - expected
        val seek = if (off > thresholdMs || off < -thresholdMs) expected else null
        val playing = if (host.playing != local.playing) host.playing else null
        return GuestStep(false, seek, playing)
    }
}

/** A song of the own library, as much as matching needs. */
class LocalSongRef(val uniqueId: String, val title: String, val artist: String, val durationSec: Long) {
    internal val normTitle = SongMatch.normalize(title)
    internal val normArtist = SongMatch.normalize(artist)
}

object SongMatch {
    const val TOLERANCE_MS = 3_000L

    fun normalize(s: String): String = buildString { for (c in s.lowercase()) if (c.isLetterOrDigit()) append(c) }

    /**
     * The own song that is the same as [target]: the same unique id, or the same title and artist with a duration within
     * [toleranceMs]. Songs with an unknown duration on either side do not match by name. The closest duration wins.
     */
    fun find(target: NetSong, library: List<LocalSongRef>, toleranceMs: Long = TOLERANCE_MS): LocalSongRef? {
        library.firstOrNull { it.uniqueId == target.id }?.let { return it }
        if (target.durationMs <= 0) return null
        val title = normalize(target.title)
        if (title.isEmpty()) return null
        val artist = normalize(target.artist)
        var best: LocalSongRef? = null
        var bestDiff = Long.MAX_VALUE
        for (c in library) {
            if (c.durationSec <= 0) continue
            val diff = kotlin.math.abs(c.durationSec * 1000 - target.durationMs)
            if (diff > toleranceMs || diff >= bestDiff) continue
            if (c.normTitle != title || c.normArtist != artist) continue
            best = c
            bestDiff = diff
        }
        return best
    }
}

/** JSON of the session messages (the same text on every platform). */
internal object NetCodec {
    fun song(s: NetSong): JsonObject = buildJsonObject {
        put("id", s.id); put("title", s.title); put("artist", s.artist); put("album", s.album)
        put("dur", s.durationMs); put("size", s.size)
    }

    fun songOf(o: JsonObject): NetSong = NetSong(
        o.reqStr("id").take(200), o.str("title").orEmpty().take(300), o.str("artist").orEmpty().take(300),
        o.str("album").orEmpty().take(300), (o.lng("dur") ?: 0).coerceAtLeast(0), (o.lng("size") ?: 0).coerceAtLeast(0),
    )

    fun state(s: HostState): JsonObject = buildJsonObject {
        put("t", "state"); put("seq", s.seq); put("pos", s.positionMs); put("playing", s.playing); put("ht", s.hostTimeMs)
        s.song?.let { put("song", song(it)) }
    }

    fun stateOf(o: JsonObject): HostState = HostState(
        o.reqLng("seq"), o.obj("song")?.let { songOf(it) }, o.reqLng("pos").coerceAtLeast(0),
        o.flag("playing") ?: false, o.reqLng("ht"),
    )

    fun queue(songs: List<NetSong>): JsonObject = buildJsonObject {
        put("t", "queue")
        put("items", buildJsonArray { for (s in songs) add(song(s)) })
    }

    fun queueOf(o: JsonObject): List<NetSong> = o.arr("items").orEmpty().take(MAX_QUEUE).mapNotNull { (it as? JsonObject)?.let(::songOf) }

    /** Songs sent to guests at most: the current one and the next ones. */
    const val MAX_QUEUE = 50
}
