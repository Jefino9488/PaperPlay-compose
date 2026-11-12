package io.max.paperplay.core

import io.max.paperplay.core.library.splitAlbum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlbumSplitTest {
    private fun songs(vararg artists: String) = artists.toList()

    @Test fun oneArtistStaysWhole() {
        val r = splitAlbum(songs("Muse", "Muse")) { it }
        assertEquals(1, r.size); assertNull(r[0].first)
    }

    @Test fun twoUnrelatedArtistsSplit() {
        val r = splitAlbum(songs("Queen", "Queen", "ABBA", "ABBA")) { it }
        assertEquals(listOf("ABBA", "Queen"), r.map { it.first })
    }

    @Test fun dominantArtistKeepsGuests() {
        val r = splitAlbum(songs("Muse", "Muse", "Muse", "Guest")) { it }
        assertEquals(1, r.size); assertNull(r[0].first)
    }

    @Test fun compilationStaysWhole() {
        val r = splitAlbum(songs("A", "B", "C", "D", "E")) { it }
        assertEquals(1, r.size); assertNull(r[0].first)
    }

    @Test fun artistCaseAndSpacesIgnored() {
        val r = splitAlbum(songs("Muse", " muse ", "MUSE")) { it }
        assertEquals(1, r.size)
    }
}
