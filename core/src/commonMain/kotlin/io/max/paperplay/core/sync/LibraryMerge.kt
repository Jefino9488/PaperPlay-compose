package io.max.paperplay.core.sync

/** Edit stamp: the clock, but always after [previous] so a local edit beats the version it replaces. */
fun laterStamp(now: Long, previous: Long): Long = if (now > previous) now else previous + 1

/** Per-song state that syncs. [userEdited] tags beat automatic ones; otherwise the later [tagsAt] wins. */
data class SongSync(
    val title: String, val userEdited: Boolean, val tagsAt: Long,
    val favorite: Boolean, val favoriteAt: Long, val plays: Long, val skips: Long, val lastPlayedAt: Long?,
)

/** A playlist item: removal and re-add are decided by whichever change is later. */
data class ItemSync(val addedAt: Long, val removedAt: Long?) {
    val lastChange: Long get() = if (removedAt != null && removedAt > addedAt) removedAt else addedAt
}

data class PlaylistSync(val name: String, val modifiedAt: Long, val deletedAt: Long?, val items: Map<String, ItemSync>)

data class LibraryState(
    val songs: Map<String, SongSync> = emptyMap(),
    /** Deleted files by unique id with when. */
    val gone: Map<String, Long> = emptyMap(),
    val playlists: Map<String, PlaylistSync> = emptyMap(),
)

fun remoteTagsWin(local: SongSync, remote: SongSync): Boolean =
    if (remote.userEdited != local.userEdited) remote.userEdited else remote.tagsAt > local.tagsAt

/** Merges [remote] into [local]. Pure; merging agreeing states returns an equal state. Songs only [remote] has are added unless gone. */
fun mergeLibrary(local: LibraryState, remote: LibraryState): LibraryState {
    val gone = HashMap(local.gone)
    for ((k, at) in remote.gone) if ((gone[k] ?: 0) < at) gone[k] = at
    val songs = HashMap<String, SongSync>()
    for ((k, l) in local.songs) if (k !in gone) songs[k] = l
    for ((k, r) in remote.songs) {
        if (k in gone) continue
        val l = songs[k]
        if (l == null) { songs[k] = r; continue }
        var m = l
        if (remoteTagsWin(l, r)) m = m.copy(title = r.title, userEdited = r.userEdited, tagsAt = r.tagsAt)
        if (r.favoriteAt > l.favoriteAt) m = m.copy(favorite = r.favorite, favoriteAt = r.favoriteAt)
        // Each device counts its own, so the higher value is closest without double counting.
        m = m.copy(plays = maxOf(l.plays, r.plays), skips = maxOf(l.skips, r.skips),
            lastPlayedAt = listOfNotNull(l.lastPlayedAt, r.lastPlayedAt).maxOrNull())
        songs[k] = m
    }
    val lists = HashMap(local.playlists)
    for ((u, r) in remote.playlists) {
        val l = lists[u]
        val base = if (l == null || r.modifiedAt > l.modifiedAt) r.copy(items = l?.items ?: emptyMap()) else l
        val items = HashMap(base.items)
        for ((key, ri) in r.items) { val li = items[key]; if (li == null || ri.lastChange > li.lastChange) items[key] = ri }
        lists[u] = base.copy(items = items)
    }
    return LibraryState(songs, gone, lists)
}
