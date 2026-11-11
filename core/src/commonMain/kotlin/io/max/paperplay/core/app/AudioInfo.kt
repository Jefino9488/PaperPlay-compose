package io.max.paperplay.core.app

/** What the start of an audio file says about its sound. Null fields are unknown. */
class AudioHeader(val codec: String? = null, val sampleRate: Int? = null, val bitDepth: Int? = null)

private fun ByteArray.tagAt(at: Int, tag: String): Boolean {
    if (at < 0 || at + tag.length > size) return false
    for (i in tag.indices) if (this[at + i] != tag[i].code.toByte()) return false
    return true
}

private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF
private fun ByteArray.u16le(at: Int) = u8(at) or (u8(at + 1) shl 8)
private fun ByteArray.u32le(at: Int) = (u16le(at).toLong() or (u16le(at + 2).toLong() shl 16))
private fun ByteArray.u16be(at: Int) = (u8(at) shl 8) or u8(at + 1)
private fun ByteArray.u32be(at: Int) = (u16be(at).toLong() shl 16) or u16be(at + 2).toLong()

/**
 * Reads sample rate and bit depth from the start of a FLAC, WAV or MP4 file. For MP4, [bytes] should hold the
 * `moov` box, which can be at the end. Null when it is none of those, or the part needed is not there.
 */
fun parseAudioHeader(bytes: ByteArray): AudioHeader? {
    if (bytes.tagAt(0, "fLaC") && bytes.size >= 26) {
        // STREAMINFO: 20 bits of sample rate, 3 of channels, 5 of bits per sample minus one.
        val rate = (bytes.u8(18) shl 12) or (bytes.u8(19) shl 4) or (bytes.u8(20) shr 4)
        val depth = (((bytes.u8(20) and 1) shl 4) or (bytes.u8(21) shr 4)) + 1
        return if (rate > 0) AudioHeader(sampleRate = rate, bitDepth = depth) else null
    }
    if (bytes.tagAt(0, "RIFF") && bytes.tagAt(8, "WAVE")) {
        var at = 12
        while (at + 8 <= bytes.size) {
            val size = bytes.u32le(at + 4)
            if (bytes.tagAt(at, "fmt ") && at + 24 <= bytes.size) {
                return AudioHeader(sampleRate = bytes.u32le(at + 12).toInt(), bitDepth = bytes.u16le(at + 22))
            }
            val next = at + 8 + size + size % 2
            if (next > bytes.size) break
            at = next.toInt()
        }
        return null
    }
    var at = 4
    while (at + 46 <= bytes.size) {
        if (!bytes.tagAt(at, "stsd")) { at++; continue }
        // After the box's version, flags and entry count: entry size, then the codec.
        val codecAt = at + 16
        if (bytes.tagAt(codecAt, "mp4a")) {
            val rate = bytes.u16be(codecAt + 28)
            return if (rate > 0) AudioHeader(sampleRate = rate) else null
        }
        if (bytes.tagAt(codecAt, "alac")) {
            // Rates over 65535 do not fit the entry's own fields, so its nested box is read when it is there.
            if (bytes.tagAt(codecAt + 36, "alac") && codecAt + 68 <= bytes.size) {
                return AudioHeader("ALAC", bytes.u32be(codecAt + 64).toInt(), bytes.u8(codecAt + 49))
            }
            return AudioHeader("ALAC", bytes.u16be(codecAt + 28), bytes.u16be(codecAt + 22))
        }
        at++
    }
    return null
}
