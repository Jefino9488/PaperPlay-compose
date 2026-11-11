package io.max.paperplay.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioInfoTest {
    @Test
    fun flacStreamInfo() {
        val b = ByteArray(34)
        "fLaC".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        b[18] = 0x17; b[19] = 0x70; b[20] = 0x01; b[21] = 0x70
        val h = parseAudioHeader(b)!!
        assertEquals(96000, h.sampleRate)
        assertEquals(24, h.bitDepth)
    }

    @Test
    fun wavFmtChunk() {
        val b = ByteArray(44)
        "RIFF".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        "WAVE".forEachIndexed { i, c -> b[8 + i] = c.code.toByte() }
        "fmt ".forEachIndexed { i, c -> b[12 + i] = c.code.toByte() }
        b[16] = 16
        // 44100 Hz little endian, 16 bit
        b[24] = 0x44; b[25] = 0xAC.toByte(); b[34] = 16
        val h = parseAudioHeader(b)!!
        assertEquals(44100, h.sampleRate)
        assertEquals(16, h.bitDepth)
    }

    @Test
    fun unknownIsNull() {
        assertNull(parseAudioHeader(ByteArray(100)))
    }
}
