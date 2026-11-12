package io.max.paperplay.core.sync

import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.library.legacyPlaylistUuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Tags that travel with an edited song, plus the pin. */
data class SongExtra(val artist: String, val album: String, val genre: String?, val year: Long?, val pinned: Boolean)

/**
 * The song related data of one device: [state] feeds [mergeLibrary], [extra] holds the tag fields of edited songs,
 * [order] the live playlist order (unique ids) by playlist uuid. Never holds tokens, session or secrets.
 */
data class Snapshot(
    val state: LibraryState,
    val extra: Map<String, SongExtra> = emptyMap(),
    val order: Map<String, List<String>> = emptyMap(),
)

/** What an apply changed. */
data class ApplyResult(val songsChanged: Int, val playlistsChanged: Int)

/** Wire form of a [Snapshot]. */
object SnapshotCodec {
    fun encode(s: Snapshot): JsonObject = JsonObject(
        mapOf(
            "v" to JsonPrimitive(1),
            "songs" to JsonArray(
                s.state.songs.map { (id, v) ->
                    val e = s.extra[id]
                    JsonObject(
                        buildMap<String, JsonElement> {
                            put("id", JsonPrimitive(id)); put("title", JsonPrimitive(v.title)); put("edited", JsonPrimitive(v.userEdited))
                            put("tagsAt", JsonPrimitive(v.tagsAt)); put("fav", JsonPrimitive(v.favorite)); put("favAt", JsonPrimitive(v.favoriteAt))
                            put("plays", JsonPrimitive(v.plays)); put("skips", JsonPrimitive(v.skips)); put("last", v.lastPlayedAt.orNull())
                            if (e != null) {
                                put("artist", JsonPrimitive(e.artist)); put("album", JsonPrimitive(e.album)); put("genre", e.genre.orNull())
                                put("year", e.year.orNull()); put("pinned", JsonPrimitive(e.pinned))
                            }
                        },
                    )
                },
            ),
            "gone" to JsonObject(s.state.gone.mapValues { JsonPrimitive(it.value) }),
            "playlists" to JsonArray(
                s.state.playlists.map { (uuid, p) ->
                    JsonObject(
                        mapOf(
                            "uuid" to JsonPrimitive(uuid), "name" to JsonPrimitive(p.name), "modifiedAt" to JsonPrimitive(p.modifiedAt),
                            "deletedAt" to p.deletedAt.orNull(),
                            "items" to JsonArray(
                                p.items.map { (id, i) ->
                                    JsonObject(mapOf("id" to JsonPrimitive(id), "addedAt" to JsonPrimitive(i.addedAt), "removedAt" to i.removedAt.orNull()))
                                },
                            ),
                            "order" to JsonArray((s.order[uuid] ?: emptyList()).map { JsonPrimitive(it) }),
                        ),
                    )
                },
            ),
        ),
    )

    /** Throws IllegalArgumentException on a damaged message; the caller drops the message. */
    fun decode(o: JsonObject): Snapshot {
        val songs = HashMap<String, SongSync>()
        val extra = HashMap<String, SongExtra>()
        for (e in o.reqArr("songs")) {
            val j = e as? JsonObject ?: throw IllegalArgumentException("bad song")
            val id = j.reqStr("id")
            songs[id] = SongSync(
                j.reqStr("title"), j.reqFlag("edited"), j.reqLng("tagsAt"), j.reqFlag("fav"), j.reqLng("favAt"),
                j.reqLng("plays"), j.reqLng("skips"), j.lng("last"),
            )
            if (j.str("artist") != null) {
                extra[id] = SongExtra(j.reqStr("artist"), j.str("album") ?: "", j.str("genre"), j.lng("year"), j.flag("pinned") ?: false)
            }
        }
        val gone = HashMap<String, Long>()
        o.obj("gone")?.forEach { (k, v) -> gone[k] = v.longAt() }
        val lists = HashMap<String, PlaylistSync>()
        val order = HashMap<String, List<String>>()
        for (e in o.reqArr("playlists")) {
            val j = e as? JsonObject ?: throw IllegalArgumentException("bad playlist")
            val uuid = j.reqStr("uuid")
            val items = HashMap<String, ItemSync>()
            for (it in j.reqArr("items")) {
                val ij = it as? JsonObject ?: throw IllegalArgumentException("bad item")
                items[ij.reqStr("id")] = ItemSync(ij.reqLng("addedAt"), ij.lng("removedAt"))
            }
            lists[uuid] = PlaylistSync(j.reqStr("name"), j.reqLng("modifiedAt"), j.lng("deletedAt"), items)
            order[uuid] = j.arr("order")?.map { it.stringAt() } ?: emptyList()
        }
        return Snapshot(LibraryState(songs, gone, lists), extra, order)
    }
}

private fun ItemSync.live() = removedAt == null || removedAt <= addedAt

/** Builds a snapshot from the database and applies a merged one back. Songs this device does not have are skipped. */
class SnapshotStore(private val library: LibraryRepository) {
    private val db get() = library.repo.db

    /**
     * @param onlyWithState true for what is sent: songs with a favorite, plays, skips, pin or edited tags. False for the
     * full local state, which [apply] needs to compare against.
     */
    fun build(onlyWithState: Boolean = true): Snapshot {
        val songs = HashMap<String, SongSync>()
        val extra = HashMap<String, SongExtra>()
        for (s in db.songsQueries.allSongs().executeAsList()) {
            val has = s.favorite || s.plays > 0 || s.skips > 0 || s.last_played_at != null || s.user_edited || s.pinned
            if (onlyWithState && !has) continue
            songs[s.unique_id] = SongSync(s.title, s.user_edited, s.tags_at, s.favorite, s.favorite_at, s.plays, s.skips, s.last_played_at)
            extra[s.unique_id] = SongExtra(s.artist, s.album, s.genre, s.year, s.pinned)
        }
        for (p in db.playlistsQueries.emptyUuidPlaylists().executeAsList()) {
            db.playlistsQueries.setPlaylistUuid(legacyPlaylistUuid(p.id, p.name), p.id)
        }
        val entries = db.lanQueries.allEntries().executeAsList().groupBy { it.playlist_id }
        val lists = HashMap<String, PlaylistSync>()
        val order = HashMap<String, List<String>>()
        for (p in db.lanQueries.allPlaylistsWithUuid().executeAsList()) {
            val es = entries[p.id].orEmpty()
            lists[p.uuid] = PlaylistSync(
                p.name, p.modified_at, p.deleted_at, es.associate { it.unique_id to ItemSync(it.added_at, it.removed_at) },
            )
            order[p.uuid] = es.filter { it.removed_at == null }.sortedWith(compareBy({ it.position }, { it.added_at })).map { it.unique_id }
        }
        return Snapshot(LibraryState(songs, emptyMap(), lists), extra, order)
    }

    /** Merges [remote] into the database (one transaction) and returns what changed. */
    fun apply(remote: Snapshot): ApplyResult {
        val local = build(onlyWithState = false)
        val merged = mergeLibrary(local.state, remote.state)
        var songsChanged = 0
        var playlistsChanged = 0
        library.batch(soft = false) {
            val rows = db.songsQueries.allSongs().executeAsList().associateBy { it.unique_id }
            val changed = ArrayList<io.max.paperplay.core.library.Song>()
            for ((id, m) in merged.songs) {
                val row = rows[id] ?: continue
                val l = local.state.songs[id]
                val r = remote.state.songs[id]
                var n = row.copy(
                    title = m.title, user_edited = m.userEdited, tags_at = m.tagsAt, favorite = m.favorite, favorite_at = m.favoriteAt,
                    plays = m.plays, skips = m.skips, last_played_at = m.lastPlayedAt,
                )
                val re = remote.extra[id]
                if (re != null) {
                    if (l != null && r != null && remoteTagsWin(l, r)) {
                        n = n.copy(artist = re.artist, album = re.album, genre = re.genre ?: row.genre, year = re.year ?: row.year)
                    }
                    if (re.pinned && !n.pinned) n = n.copy(pinned = true)
                }
                if (n != row) changed += n
            }
            library.replaceSongs(changed)
            songsChanged = changed.size

            val have = db.lanQueries.allPlaylistsWithUuid().executeAsList().associateBy { it.uuid }
            val localEntries = db.lanQueries.allEntries().executeAsList()
            for ((uuid, mp) in merged.playlists) {
                val lp = have[uuid]
                val pid: Long
                if (lp == null) {
                    if (mp.deletedAt != null) continue
                    db.playlistsQueries.insertPlaylist(mp.name, uuid, mp.modifiedAt, null)
                    pid = db.playlistsQueries.lastInsertRowId().executeAsOne()
                    playlistsChanged++
                } else {
                    pid = lp.id
                    if (lp.name != mp.name || lp.modified_at != mp.modifiedAt || lp.deleted_at != mp.deletedAt) {
                        db.lanQueries.setPlaylistMeta(mp.name, mp.modifiedAt, mp.deletedAt, pid)
                        playlistsChanged++
                    }
                }
                val localOrder = local.order[uuid].orEmpty()
                val remoteWins = lp == null || (remote.state.playlists[uuid]?.modifiedAt ?: 0L) > lp.modified_at
                val base = if (remoteWins) remote.order[uuid].orEmpty() else localOrder
                val live = mp.items.filter { it.value.live() }.keys
                val ordered = base.filter { it in live } + live.filter { it !in base }.sorted()
                val position = ordered.withIndex().associate { it.value to (it.index + 1).toLong() }
                val here = localEntries.filter { it.playlist_id == pid }.associateBy { it.unique_id }
                for ((uid, item) in mp.items) {
                    val row = rows[uid] ?: continue
                    val removedAt = if (item.live()) null else item.removedAt
                    val pos = position[uid] ?: 0L
                    val old = here[uid]
                    if (old != null && old.added_at == item.addedAt && old.removed_at == removedAt && (removedAt != null || old.position == pos)) continue
                    db.lanQueries.insertEntryIfMissing(pid, row.chat_id, row.message_id, item.addedAt, removedAt, pos)
                    db.lanQueries.updateEntry(item.addedAt, removedAt, pos, pid, row.chat_id, row.message_id)
                    playlistsChanged++
                }
            }
        }
        return ApplyResult(songsChanged, playlistsChanged)
    }
}

