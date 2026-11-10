package io.max.paperplay.core.recs

import io.max.paperplay.core.library.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.random.Random

enum class ShelfKind { SONGS, ALBUMS, ARTISTS }

/** One row of the Home page. Albums and artists come with a cover to show that no other row has used. */
class MixShelf(
    val title: String,
    val kind: ShelfKind,
    /** The songs of a [ShelfKind.SONGS] row. */
    val songs: List<Song>,
    /** The albums or artists of the other kinds. */
    val groups: List<MixGroup> = emptyList(),
)

class MixGroup(val name: String, val cover: Song, val songs: List<Song>)

/** A generated collection for the quick tiles. */
class MixTile(val title: String, val cover: Song, val songs: List<Song>)

class HomeMix(val tiles: List<MixTile>, val shelves: List<MixShelf>)

private const val SHELF_MAX = 12
private const val SHELF_MIN = 4
private const val STALE_AFTER_MS = 30L * 24 * 3_600_000

object HomeShelves {
    /**
     * Plans the Home page so nothing repeats: a song, or any song sharing its cover, appears at most once across
     * every row. Variety comes from a shuffle seeded by the [day], so the page stays put during a day and differs
     * the next. Songs in [gone] (the deletion record) are left out. Runs on [Dispatchers.Default] and is cancellable.
     */
    suspend fun plan(
        songs: List<Song>,
        recent: List<Song>,
        favorites: List<Song>,
        day: Moment,
        tileCount: Int = 6,
        gone: Map<String, Long> = emptyMap(),
    ): HomeMix = withContext(Dispatchers.Default) {
        val ctx = currentCoroutineContext()
        val live = DeletedSongs.without(songs, gone)
        val random = Random(day.year * 1000 + day.month * 40 + day.day)
        val seen = HashSet<String>()
        // Songs on an album look the same on a shelf, even though each has its own file.
        fun keyOf(s: Song): String = if (s.album.isNotEmpty()) "album:${s.album}" else s.cover_path ?: s.unique_id
        fun <T> shuffled(items: Iterable<T>): List<T> = items.toMutableList().also { it.shuffle(random) }

        // Takes up to the maximum unseen songs. Claims them only if the row is worth showing.
        fun take(pool: Iterable<Song>): List<Song>? {
            val picked = ArrayList<Song>()
            val keys = LinkedHashSet<String>()
            for (s in pool) {
                val key = keyOf(s)
                if (key in seen || !keys.add(key)) continue
                picked.add(s)
                if (picked.size == SHELF_MAX) break
            }
            if (picked.size < SHELF_MIN) return null
            seen.addAll(keys)
            return picked
        }

        fun groupBy(keyFn: (Song) -> String?): LinkedHashMap<String, MutableList<Song>> {
            val groups = LinkedHashMap<String, MutableList<Song>>()
            for (s in live) {
                val key = keyFn(s)
                if (!key.isNullOrEmpty()) groups.getOrPut(key) { ArrayList() }.add(s)
            }
            return groups
        }

        // Single-song "albums" would just repeat the song, so only real ones count.
        val albums = groupBy { it.album }.apply { entries.removeAll { it.value.size < 2 } }
        val artists = groupBy { firstArtist(it.artist) }
        val genres = groupBy { it.genre }

        // The first unused cover in a group, claimed for it.
        fun coverFor(list: List<Song>): Song? = list.firstOrNull { seen.add(keyOf(it)) }

        val shelves = ArrayList<MixShelf>()
        fun addSongs(title: String, pool: Iterable<Song>) {
            take(pool)?.let { shelves.add(MixShelf(title, ShelfKind.SONGS, it)) }
        }

        fun groupShelf(title: String, kind: ShelfKind, pool: Iterable<Map.Entry<String, List<Song>>>): MixShelf? {
            val picked = ArrayList<MixGroup>()
            for (e in pool) {
                if (picked.size == SHELF_MAX) break
                val cover = coverFor(e.value)
                if (cover != null) picked.add(MixGroup(e.key, cover, e.value))
            }
            return if (picked.size >= SHELF_MIN) MixShelf(title, kind, emptyList(), picked) else null
        }

        fun addGroups(title: String, kind: ShelfKind, pool: Iterable<Map.Entry<String, List<Song>>>) {
            groupShelf(title, kind, pool)?.let { shelves.add(it) }
        }

        addSongs("Recently played", DeletedSongs.without(recent, gone))

        // Chosen here, though shown further down: each album can only appear once on the page, and rows of songs
        // would otherwise use them all up first.
        val albumRow = groupShelf("Albums", ShelfKind.ALBUMS, shuffled(albums.entries))
        ctx.ensureActive()

        // Collections for the tiles, which name a whole group rather than one song.
        val tiles = ArrayList<MixTile>()
        val topGenres = genres.entries.sortedByDescending { it.value.size }
        val tilePool = buildList {
            for (g in shuffled(topGenres.filter { it.value.size >= SHELF_MIN }.take(8))) add("${g.key} mix" to shuffled(g.value))
            for (a in shuffled(artists.entries.filter { it.value.size >= SHELF_MIN })) add("${a.key} mix" to shuffled(a.value))
        }
        for ((title, list) in tilePool) {
            if (tiles.size == tileCount) break
            val cover = coverFor(list)
            if (cover != null) tiles.add(MixTile(title, cover, list))
        }

        addSongs("Picked for today", shuffled(live.filter { it.last_played_at == null }))

        // What's been played lately decides what to suggest more of.
        val liveRecent = DeletedSongs.without(recent, gone)
        val recentArtists = liveRecent.mapTo(LinkedHashSet()) { firstArtist(it.artist) }
        for (a in recentArtists.filter { (artists[it]?.size ?: 0) >= SHELF_MIN }.take(2)) {
            addSongs("More from $a", shuffled(artists.getValue(a)))
        }

        albumRow?.let { shelves.add(it) }
        addSongs("Recently added", live)

        val recentGenres = liveRecent.mapNotNullTo(LinkedHashSet()) { it.genre }
        val genre = recentGenres.firstOrNull { (genres[it]?.size ?: 0) >= SHELF_MIN }
        if (genre != null) addSongs("More $genre", shuffled(genres.getValue(genre)))
        ctx.ensureActive()

        addGroups("Artists", ShelfKind.ARTISTS, shuffled(artists.entries.filter { it.value.size >= 2 }))
        addSongs("Your favorites", shuffled(DeletedSongs.without(favorites, gone)))

        val stale = day.epochMs - STALE_AFTER_MS
        addSongs(
            "Back in rotation",
            live.filter { it.last_played_at != null && it.last_played_at!! < stale }.sortedBy { it.last_played_at },
        )

        // Whatever hasn't appeared yet, so the page keeps offering something new.
        addSongs("More to explore", shuffled(live))

        HomeMix(tiles, shelves)
    }
}
