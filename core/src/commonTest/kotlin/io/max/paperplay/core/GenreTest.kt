package io.max.paperplay.core

import io.max.paperplay.core.metadata.genreOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GenreTest {
    @Test fun numbers() {
        assertEquals("Rock", genreOf("(17)"))
        assertEquals("Rock", genreOf("17"))
        assertEquals("Metal", genreOf("(9)Metal"))
        assertEquals("Shoegaze", genreOf("Shoegaze"))
        assertNull(genreOf("999"))
    }
}
