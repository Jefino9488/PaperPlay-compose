package io.max.paperplay.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryMergeTest {
    private fun song(title: String = "t", edited: Boolean = false, tagsAt: Long = 0, fav: Boolean = false, favAt: Long = 0, plays: Long = 0) =
        SongSync(title, edited, tagsAt, fav, favAt, plays, 0, null)

    @Test fun laterStampAfterFuturePeer() {
        assertEquals(5001, laterStamp(1000, 5000)); assertEquals(1001, laterStamp(1000, 1000)); assertEquals(2000, laterStamp(2000, 1000))
    }

    @Test fun favoriteAndUnfavoriteReach() {
        val a = LibraryState(songs = mapOf("1" to song(fav = true, favAt = 10)))
        val b = LibraryState(songs = mapOf("1" to song()))
        assertEquals(true, mergeLibrary(b, a).songs["1"]!!.favorite)
        val b2 = LibraryState(songs = mapOf("1" to song(fav = false, favAt = 20)))
        assertEquals(false, mergeLibrary(a, b2).songs["1"]!!.favorite)
    }

    @Test fun personEditBeatsNewerAutomatic() {
        val a = LibraryState(songs = mapOf("1" to song("edited", true, 5)))
        val b = LibraryState(songs = mapOf("1" to song("matched", false, 9)))
        assertEquals("edited", mergeLibrary(a, b).songs["1"]!!.title)
        assertEquals("edited", mergeLibrary(b, a).songs["1"]!!.title)
    }

    @Test fun playsTakeHigher() {
        val m = mergeLibrary(LibraryState(songs = mapOf("1" to song(plays = 3))), LibraryState(songs = mapOf("1" to song(plays = 5))))
        assertEquals(5, m.songs["1"]!!.plays)
    }

    @Test fun goneSongStaysGone() {
        val a = LibraryState(gone = mapOf("1" to 5L))
        val b = LibraryState(songs = mapOf("1" to song()))
        assertNull(mergeLibrary(b, a).songs["1"]); assertNull(mergeLibrary(a, b).songs["1"])
    }

    @Test fun removedItemAndDeletedPlaylist() {
        val on = PlaylistSync("Mix", 1, null, mapOf("s" to ItemSync(1, null)))
        val off = PlaylistSync("Mix", 1, null, mapOf("s" to ItemSync(1, 5)))
        assertEquals(5L, mergeLibrary(LibraryState(playlists = mapOf("p" to on)), LibraryState(playlists = mapOf("p" to off))).playlists["p"]!!.items["s"]!!.removedAt)
        val del = on.copy(modifiedAt = 9, deletedAt = 9)
        assertEquals(9L, mergeLibrary(LibraryState(playlists = mapOf("p" to on)), LibraryState(playlists = mapOf("p" to del))).playlists["p"]!!.deletedAt)
    }

    @Test fun agreeingStatesAreStable() {
        val s = LibraryState(songs = mapOf("1" to song(fav = true, favAt = 3)), gone = mapOf("x" to 1L))
        assertEquals(s, mergeLibrary(s, s))
    }
}
