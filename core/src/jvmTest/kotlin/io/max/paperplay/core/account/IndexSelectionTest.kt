package io.max.paperplay.core.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexSelectionTest {
    private fun song(id: Long, inMain: Boolean = false, copied: Boolean = false) =
        IndexedSong(ChatPost(-1, id, "u$id", "t$id", "a", 100, 1, "audio/mpeg", "f", id), inMain, copied)

    private val songs = listOf(song(1), song(2, inMain = true), song(3, copied = true), song(4))

    @Test
    fun nothingIsPickedAtFirst() {
        assertTrue(IndexSelection().picked.isEmpty())
        assertTrue(IndexSelection().toCopy(songs).isEmpty())
    }

    @Test
    fun pickNewSkipsAvailableAndCopied() {
        assertEquals(listOf(1L, 4L), IndexSelection().pickNew(songs).toCopy(songs).map { it.messageId })
    }

    @Test
    fun handPickedAvailableSongIsCopied() {
        assertEquals(listOf(2L), IndexSelection().toggle(2).toCopy(songs).map { it.messageId })
    }

    @Test
    fun toggleTwiceUnpicks() {
        assertTrue(IndexSelection().toggle(1).toggle(1).picked.isEmpty())
    }
}
