package io.max.paperplay.core.recs

import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ln

/**
 * Light suggestions: for each song a short list of its closest songs, worked out once from what the library
 * already knows (playlists and topics it shares, artist, album, genre and decade), never from audio.
 */
object Suggestions {
    private const val WINDOW = 6 // How many following songs of a group each song is linked to.
    private const val KEEP = 20 // Neighbours kept per song.
    private const val GROUP_CAP = 400 // A group larger than this says little about any pair.

    // Weights of the signals. A shared playlist says most; the rest fill in.
    private const val W_GROUP = 0.4
    private const val W_ARTIST = 0.25
    private const val W_ALBUM = 0.25
    private const val W_GENRE = 0.2

    /**
     * Neighbour table: unique id to its closest unique ids, closest first. [groups] are lists of unique ids that
     * belong together (a playlist, a topic). Runs on [Dispatchers.Default] and is cancellable.
     */
    suspend fun neighbours(songs: List<Song>, groups: List<List<String>>): Map<String, List<String>> =
        withContext(Dispatchers.Default) {
            val ctx = currentCoroutineContext()
            val score = HashMap<String, MutableMap<String, Double>>()
            fun link(a: String, b: String, w: Double) {
                if (a == b) return
                val m = score.getOrPut(a) { LinkedHashMap() }
                m[b] = (m[b] ?: 0.0) + w
                val n = score.getOrPut(b) { LinkedHashMap() }
                n[a] = (n[a] ?: 0.0) + w
            }

            fun linkRun(ids: List<String>, w: Double) {
                for (i in ids.indices) {
                    var j = i + 1
                    while (j < ids.size && j <= i + WINDOW) link(ids[i], ids[j++], w)
                }
            }

            for (g in groups) if (g.size > 1 && g.size <= GROUP_CAP) linkRun(g, W_GROUP)
            ctx.ensureActive()

            fun bucket(key: (Song) -> String?, w: Double) {
                val by = LinkedHashMap<String, MutableList<String>>()
                for (s in songs) {
                    val k = key(s)
                    if (!k.isNullOrEmpty()) by.getOrPut(k) { ArrayList() }.add(s.unique_id)
                }
                for (list in by.values) if (list.size > 1) linkRun(list, w)
                ctx.ensureActive()
            }

            bucket({ firstArtist(it.artist).lowercase() }, W_ARTIST)
            bucket({ if (it.album.isEmpty()) null else "${it.album.lowercase()}|${firstArtist(it.artist).lowercase()}" }, W_ALBUM)
            bucket({ if (it.genre == null || it.year == null) null else "${it.genre!!.lowercase()}|${it.year!! / 10}" }, W_GENRE)

            score.mapValues { e -> e.value.entries.sortedByDescending { it.value }.take(KEEP).map { it.key } }
        }

    /**
     * Songs to suggest from [seeds] (what was played or liked lately): the union of their neighbours, ranked by how
     * many seeds point at them, at most [perArtist] from one artist, never a seed or an [exclude]d song.
     */
    fun suggestFrom(
        seeds: Iterable<Song>,
        neighbours: Map<String, List<String>>,
        byId: Map<String, Song>,
        exclude: Set<String> = emptySet(),
        limit: Int = 12,
        perArtist: Int = 2,
    ): List<Song> {
        val seedList = seeds.toList()
        val seedIds = seedList.mapTo(HashSet()) { it.unique_id }
        val votes = LinkedHashMap<String, Double>()
        for (seed in seedList) {
            val list = neighbours[seed.unique_id] ?: continue
            // Implicit feedback, as in MetaBrainz's recommender: what was played more counts more, and a song
            // mostly skipped counts for little.
            val weight = (1 + ln(1.0 + seed.plays) - 0.5 * seed.skips).coerceIn(0.2, 4.0)
            for (i in list.indices) {
                // Earlier neighbours are closer, so they count more.
                votes[list[i]] = (votes[list[i]] ?: 0.0) + weight * (1 - i.toDouble() / (list.size + 1))
            }
        }
        val ranked = votes.entries
            .filter { it.key !in seedIds && it.key !in exclude && byId[it.key] != null }
            .sortedByDescending { it.value }
        val out = ArrayList<Song>()
        val perArtistCount = HashMap<String, Int>()
        for (e in ranked) {
            val song = byId.getValue(e.key)
            val artist = firstArtist(song.artist).lowercase()
            if ((perArtistCount[artist] ?: 0) >= perArtist) continue
            perArtistCount[artist] = (perArtistCount[artist] ?: 0) + 1
            out.add(song)
            if (out.size == limit) break
        }
        return out
    }

    /** "Suggested for you": the newest few plays and likes seed the shelf, and what was just played is left out. */
    fun forYou(neighbours: Map<String, List<String>>, songs: List<Song>, recent: List<Song>, liked: List<Song>): List<Song> =
        suggestFrom(
            recent.take(6) + liked.take(6),
            neighbours,
            songs.associateBy { it.unique_id },
            exclude = recent.mapTo(HashSet()) { it.unique_id },
        )

    /** Groups for [neighbours]: each live playlist, and each topic's songs (the General topic says little). */
    fun groupsOf(repo: LibraryRepo, songs: List<Song>): List<List<String>> {
        val lists = repo.db.playlistsQueries.livePlaylists().executeAsList().map { p ->
            repo.db.playlistsQueries.playlistSongs(p.id).executeAsList().map { it.unique_id }
        }
        val topics = LinkedHashMap<Pair<Long, Long>, MutableList<String>>()
        for (s in songs) {
            val t = s.topic_id
            if (t != null && t != GENERAL_TOPIC_ID) topics.getOrPut(s.chat_id to t) { ArrayList() }.add(s.unique_id)
        }
        return lists + topics.values
    }
}
