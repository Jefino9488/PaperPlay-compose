package io.max.paperplay.core.app

import io.max.paperplay.core.library.Song
import io.max.paperplay.core.sync.netplay.HostNow
import io.max.paperplay.core.sync.netplay.LocalPlay
import io.max.paperplay.core.sync.netplay.LocalSongRef
import io.max.paperplay.core.sync.netplay.NetAudio
import io.max.paperplay.core.sync.netplay.NetSong
import io.max.paperplay.core.sync.netplay.NetplayPlayer
import io.max.paperplay.core.sync.netplay.SongMatch
import io.max.paperplay.core.sync.netplay.SongSource
import io.max.paperplay.player.StreamSource
import kotlin.concurrent.Volatile

/**
 * Connects network play to the real player. Host: reads what plays and serves the audio of library songs. Guest: loads a
 * queue where songs found in the own library play from it and the others stream from the host.
 */
class PlaybackNetplayPlayer(
    private val playback: () -> PlaybackController,
    private val library: LibraryRepository,
    private val resolver: TrackResolver,
) : NetplayPlayer {
    /** Unique id of a loaded song in this device's queue to the host's id of it. Empty when nothing came from a session. */
    @Volatile private var loaded: Map<String, String> = emptyMap()

    private var externalIds: Set<String> = emptySet()

    private fun toNet(s: Song) = NetSong(s.unique_id, s.title, s.artist, s.album, s.duration_sec * 1000L, s.size)

    override fun hostNow(): HostNow {
        val np = playback().nowPlaying.value
        val from = np.index.coerceIn(0, np.queue.size)
        val window = if (np.index < 0) emptyList() else np.queue.subList(from, minOf(np.queue.size, from + 50)).map(::toNet)
        return HostNow(np.song?.let(::toNet), window, np.positionMs, np.isPlaying)
    }

    override fun localPlay(): LocalPlay {
        val np = playback().nowPlaying.value
        val id = np.song?.unique_id?.let { loaded[it] }
        return LocalPlay(id, np.positionMs, np.isPlaying, loading = false)
    }

    override fun audioFor(songId: String): StreamSource? {
        val song = library.songByUniqueId(songId) ?: return null
        return resolver.resolve(song).source
    }

    override suspend fun load(queue: List<NetSong>, index: Int, audio: NetAudio?): SongSource {
        val lib = library.repo.db.songsQueries.allSongs().executeAsList()
        val refs = lib.map { LocalSongRef(it.unique_id, it.title, it.artist, it.duration_sec.toLong()) }
        val byUid = lib.associateBy { it.unique_id }
        val songs = ArrayList<Song>()
        val map = HashMap<String, String>()
        val external = HashSet<String>()
        for (old in externalIds) resolver.setExternalSource(old, null)
        var startAt = -1
        var source = SongSource.Missing
        for ((i, ns) in queue.withIndex()) {
            val local = SongMatch.find(ns, refs)?.let { byUid[it.uniqueId] }
            val song: Song
            val from: SongSource
            if (local != null) {
                song = local
                from = SongSource.Local
            } else if (audio != null && ns.size > 0) {
                song = remoteSong(ns)
                resolver.setExternalSource(song.unique_id, audio.open(ns))
                external.add(song.unique_id)
                from = SongSource.Streamed
            } else {
                continue
            }
            if (i == index) { startAt = songs.size; source = from }
            songs.add(song)
            map[song.unique_id] = ns.id
        }
        externalIds = external
        if (startAt < 0) {
            loaded = emptyMap()
            playback().pause()
            return SongSource.Missing
        }
        loaded = map
        playback().setQueue(songs, startAt)
        return source
    }

    /** A queue entry for a song that only exists on the host. Never written to the library. */
    private fun remoteSong(n: NetSong) = Song(
        0L, 0L, "netplay:${n.id}", NETPLAY_REMOTE, n.title, n.artist, n.album, n.durationMs / 1000, n.size, "audio/mpeg", "${n.title}.mp3", 0L,
        null, null, null, null, null, null, false, false, false, 0, null, 0, null, false, 0, 0, 0, false, 0, 0, 0,
        null, null, null, null,
    )

    override fun play() = playback().play()
    override fun pause() = playback().pause()
    override fun seek(positionMs: Long) = playback().seek(positionMs)
    override fun next() = playback().next()
    override fun previous() = playback().previous()
}
