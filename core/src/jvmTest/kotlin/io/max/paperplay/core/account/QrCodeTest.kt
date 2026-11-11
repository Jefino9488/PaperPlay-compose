package io.max.paperplay.core.account

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrCodeTest {
    private val link = "tg://login?token=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdEfGh"

    @Test
    fun sizeAndFinders() {
        val m = assertNotNull(QrCode.encode(link))
        assertEquals(m.size, m[0].size)
        assertEquals(0, (m.size - 17) % 4)
        assertTrue(m[0][0] && m[0][6] && m[6][0] && m[3][3] && !m[1][1])
        // Written out so a decoder can check the symbol.
        File("build/qr-sample.txt").apply { parentFile.mkdirs() }.writeText(m.joinToString("\n") { r -> r.joinToString("") { if (it) "1" else "0" } })
    }

    @Test
    fun tooLongIsNull() {
        assertNull(QrCode.encode("x".repeat(300)))
    }
}
