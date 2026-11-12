package io.max.paperplay.core.metadata

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Tags and cover read from inside an audio file. Null fields were not present. */
class EmbeddedTags(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val cover: ByteArray? = null,
) {
    val isEmpty get() = title == null && artist == null && album == null && year == null && genre == null && cover == null
}

/** Random access to a file: [read] returns up to `length` bytes from `offset`, fewer only at the end. */
class TagSource(val length: Long, val read: (Long, Int) -> ByteArray)

private const val MAX_TAG_BYTES = 24 * 1024 * 1024

private class Acc {
    var title: String? = null
    var artist: String? = null
    var albumArtist: String? = null
    var album: String? = null
    var year: Int? = null
    var genre: String? = null
    var cover: ByteArray? = null
    var coverRank = -1
    fun build() = EmbeddedTags(title, artist, albumArtist, album, year, genre, cover)
    fun offerCover(bytes: ByteArray, pictureType: Int) {
        if (bytes.size < 16) return
        val rank = if (pictureType == 3) 2 else 1
        if (rank > coverRank) { cover = bytes; coverRank = rank }
    }
    fun text(v: String?): String? = v?.replace("\u0000", " ")?.trim()?.takeIf { it.isNotEmpty() }
    fun yearOf(v: String?) { if (year == null) year = Regex("(1[0-9]{3}|20[0-9]{2})").find(v.orEmpty())?.value?.toInt() }
}

/** Reads ID3v2, FLAC, Ogg (Vorbis and Opus) and MP4 tags. Null when the format is unknown or holds nothing. */
fun readEmbeddedTags(src: TagSource): EmbeddedTags? = try {
    val acc = Acc()
    val head = src.read(0, 12)
    var start = 0L
    if (head.tag(0, "ID3") && head.size >= 10) {
        start = 10L + synchsafe(head, 6)
        parseId3(src, 0, acc)
    }
    val magic = if (start == 0L) head else src.read(start, 12)
    when {
        magic.tag(0, "fLaC") -> parseFlac(src, start + 4, acc)
        magic.tag(0, "OggS") -> parseOgg(src, start, acc)
        magic.tag(4, "ftyp") -> parseMp4(src, acc)
        else -> Unit
    }
    acc.build().takeUnless { it.isEmpty }
} catch (e: Exception) {
    null
}

private fun ByteArray.tag(at: Int, t: String): Boolean {
    if (at < 0 || at + t.length > size) return false
    for (i in t.indices) if (this[at + i] != t[i].code.toByte()) return false
    return true
}

private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF
private fun ByteArray.be32(at: Int) = (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()
private fun ByteArray.be24(at: Int) = (u8(at) shl 16) or (u8(at + 1) shl 8) or u8(at + 2)
private fun ByteArray.le32(at: Int) = (u8(at + 3).toLong() shl 24) or (u8(at + 2).toLong() shl 16) or (u8(at + 1).toLong() shl 8) or u8(at).toLong()
private fun synchsafe(b: ByteArray, at: Int) = (b.u8(at) shl 21) or (b.u8(at + 1) shl 14) or (b.u8(at + 2) shl 7) or b.u8(at + 3)

private fun latin1(b: ByteArray, from: Int, to: Int): String {
    val sb = StringBuilder()
    for (i in from until to) sb.append((b[i].toInt() and 0xFF).toChar())
    return sb.toString()
}

private fun utf16(b: ByteArray, from: Int, to: Int, bigDefault: Boolean): String {
    var big = bigDefault
    var i = from
    if (to - from >= 2) {
        val a = b.u8(from); val c = b.u8(from + 1)
        if (a == 0xFF && c == 0xFE) { big = false; i += 2 } else if (a == 0xFE && c == 0xFF) { big = true; i += 2 }
    }
    val sb = StringBuilder()
    while (i + 1 < to) {
        val u = if (big) (b.u8(i) shl 8) or b.u8(i + 1) else (b.u8(i + 1) shl 8) or b.u8(i)
        sb.append(u.toChar())
        i += 2
    }
    return sb.toString()
}

private fun decodeText(enc: Int, b: ByteArray, from: Int, to: Int): String = when (enc) {
    0 -> latin1(b, from, to)
    1 -> utf16(b, from, to, false)
    2 -> utf16(b, from, to, true)
    else -> b.decodeToString(from, to)
}

/** Index of the end of a terminated string starting at [from]; returns (end, next). */
private fun terminator(enc: Int, b: ByteArray, from: Int, limit: Int): Pair<Int, Int> {
    var i = from
    if (enc == 1 || enc == 2) {
        while (i + 1 < limit) { if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i to i + 2; i += 2 }
        return limit to limit
    }
    while (i < limit) { if (b[i].toInt() == 0) return i to i + 1; i++ }
    return limit to limit
}

private fun deunsync(b: ByteArray): ByteArray {
    val out = ByteArray(b.size)
    var n = 0
    var i = 0
    while (i < b.size) {
        out[n++] = b[i]
        if (b[i].toInt() == -1 && i + 1 < b.size && b[i + 1].toInt() == 0) i++
        i++
    }
    return out.copyOf(n)
}

private fun parseId3(src: TagSource, at: Long, acc: Acc) {
    val h = src.read(at, 10)
    if (h.size < 10 || !h.tag(0, "ID3")) return
    val ver = h.u8(3)
    if (ver !in 2..4) return
    val flags = h.u8(5)
    val size = synchsafe(h, 6)
    if (size <= 0 || size > MAX_TAG_BYTES) return
    var body = src.read(at + 10, size)
    if (flags and 0x80 != 0) body = deunsync(body)
    var p = 0
    if (flags and 0x40 != 0 && ver >= 3 && body.size >= 4) {
        p = if (ver == 4) synchsafe(body, 0) else (body.be32(0) + 4).toInt()
    }
    val idLen = if (ver == 2) 3 else 4
    val hdr = if (ver == 2) 6 else 10
    while (p + hdr <= body.size) {
        if (body[p].toInt() == 0) break
        val id = latin1(body, p, p + idLen)
        val fsize = when (ver) {
            2 -> body.be24(p + 3)
            4 -> synchsafe(body, p + 4)
            else -> body.be32(p + 4).toInt()
        }
        val fflags = if (ver == 2) 0 else (body.u8(p + 8) shl 8) or body.u8(p + 9)
        var from = p + hdr
        val end = from + fsize
        if (fsize < 0 || end > body.size) break
        p = end
        var data = body
        var s = from
        var e = end
        if (ver == 4) {
            if (fflags and 0x01 != 0) s += 4
            if (fflags and 0x02 != 0) { data = deunsync(body.copyOfRange(s, e)); s = 0; e = data.size }
        } else if (ver == 3 && fflags and 0x20 != 0) s += 1
        if (s >= e) continue
        when (id) {
            "TIT2", "TT2" -> if (acc.title == null) acc.title = acc.text(id3Text(data, s, e))
            "TPE1", "TP1" -> if (acc.artist == null) acc.artist = acc.text(id3Text(data, s, e))
            "TPE2", "TP2" -> if (acc.albumArtist == null) acc.albumArtist = acc.text(id3Text(data, s, e))
            "TALB", "TAL" -> if (acc.album == null) acc.album = acc.text(id3Text(data, s, e))
            "TDRC", "TYER", "TYE", "TDRL" -> acc.yearOf(id3Text(data, s, e))
            "TCON", "TCO" -> if (acc.genre == null) acc.genre = genreOf(acc.text(id3Text(data, s, e)))
            "APIC", "PIC" -> id3Picture(data, s, e, ver == 2, acc)
        }
    }
}

private fun id3Text(b: ByteArray, s: Int, e: Int): String {
    val enc = b.u8(s)
    // Multiple values are NUL separated; the first one is enough.
    val (end, _) = terminator(enc, b, s + 1, e)
    return decodeText(enc, b, s + 1, end)
}

private fun genreOf(raw: String?): String? {
    val g = raw ?: return null
    val stripped = g.replace(Regex("^(\\(\\d+\\)|\\(RX\\)|\\(CR\\))+"), "").trim()
    if (stripped.isEmpty() || stripped.all { it.isDigit() }) return null
    return stripped
}

private fun id3Picture(b: ByteArray, s: Int, e: Int, v22: Boolean, acc: Acc) {
    val enc = b.u8(s)
    var p = s + 1
    if (v22) p += 3 else p = terminator(0, b, p, e).second
    if (p >= e) return
    val type = b.u8(p)
    p = terminator(enc, b, p + 1, e).second
    if (p >= e) return
    acc.offerCover(b.copyOfRange(p, e), type)
}

private fun parseFlac(src: TagSource, firstBlock: Long, acc: Acc) {
    var at = firstBlock
    while (at + 4 <= src.length) {
        val h = src.read(at, 4)
        if (h.size < 4) return
        val last = h.u8(0) and 0x80 != 0
        val type = h.u8(0) and 0x7F
        val len = h.be24(1)
        if (len > MAX_TAG_BYTES) return
        if (type == 4 || type == 6) {
            val body = src.read(at + 4, len)
            if (type == 4) parseVorbisComments(body, 0, acc) else parseFlacPicture(body, acc)
        }
        if (last) return
        at += 4L + len
    }
}

private fun parseFlacPicture(b: ByteArray, acc: Acc) {
    if (b.size < 32) return
    val type = b.be32(0).toInt()
    var p = 4
    p += 4 + b.be32(p).toInt()
    if (p + 4 > b.size) return
    p += 4 + b.be32(p).toInt()
    p += 16
    if (p + 4 > b.size) return
    val n = b.be32(p).toInt()
    p += 4
    if (n <= 0 || p + n > b.size) return
    acc.offerCover(b.copyOfRange(p, p + n), type)
}

@OptIn(ExperimentalEncodingApi::class)
private fun parseVorbisComments(b: ByteArray, from: Int, acc: Acc) {
    var p = from
    if (p + 4 > b.size) return
    p += 4 + b.le32(p).toInt()
    if (p + 4 > b.size) return
    val count = b.le32(p).toInt()
    p += 4
    repeat(count) {
        if (p + 4 > b.size) return
        val len = b.le32(p).toInt()
        p += 4
        if (len < 0 || p + len > b.size) return
        val s = b.decodeToString(p, p + len)
        p += len
        val eq = s.indexOf('=')
        if (eq <= 0) return@repeat
        val v = s.substring(eq + 1)
        when (s.substring(0, eq).uppercase()) {
            "TITLE" -> if (acc.title == null) acc.title = acc.text(v)
            "ARTIST" -> if (acc.artist == null) acc.artist = acc.text(v)
            "ALBUMARTIST", "ALBUM ARTIST" -> if (acc.albumArtist == null) acc.albumArtist = acc.text(v)
            "ALBUM" -> if (acc.album == null) acc.album = acc.text(v)
            "DATE", "YEAR", "ORIGINALDATE" -> acc.yearOf(v)
            "GENRE" -> if (acc.genre == null) acc.genre = acc.text(v)
            "METADATA_BLOCK_PICTURE" -> runCatching { parseFlacPicture(Base64.Mime.decode(v), acc) }
        }
    }
}

private fun parseOgg(src: TagSource, start: Long, acc: Acc) {
    // Page payloads are joined; the comment packet follows the identification header.
    val out = ArrayList<ByteArray>()
    var total = 0
    var at = start
    var pages = 0
    var joined = ByteArray(0)
    while (at + 27 <= src.length && pages < 4000 && total < MAX_TAG_BYTES) {
        val h = src.read(at, 27)
        if (h.size < 27 || !h.tag(0, "OggS")) break
        val segs = h.u8(26)
        val table = src.read(at + 27, segs)
        if (table.size < segs) break
        var payload = 0
        for (i in 0 until segs) payload += table.u8(i)
        out.add(src.read(at + 27 + segs, payload))
        total += payload
        at += 27L + segs + payload
        pages++
        joined = out.fold(ByteArray(0)) { a, c -> a + c }
        val vorbis = indexOf(joined, byteArrayOf(3, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte()))
        val opus = indexOf(joined, "OpusTags".encodeToByteArray())
        val from = if (vorbis >= 0) vorbis + 7 else if (opus >= 0) opus + 8 else -1
        // Comments end where the setup header or the next stream begins; two pages after the first hit suffice
        // unless a picture is embedded, so parse once the data stops growing a packet (a page under 255 segments).
        if (from >= 0 && segs < 255 && payload < 255 * 255) {
            parseVorbisComments(joined, from, acc)
            return
        }
        if (from < 0 && pages >= 2) return
    }
}

private fun indexOf(b: ByteArray, pat: ByteArray): Int {
    if (b.size < pat.size) return -1
    outer@ for (i in 0..b.size - pat.size) {
        for (j in pat.indices) if (b[i + j] != pat[j]) continue@outer
        return i
    }
    return -1
}

private fun parseMp4(src: TagSource, acc: Acc) {
    var at = 0L
    while (at + 8 <= src.length) {
        val h = src.read(at, 16)
        if (h.size < 8) return
        var size = h.be32(0)
        var hdr = 8
        if (size == 1L && h.size >= 16) { size = (h.be32(8) shl 32) or h.be32(12); hdr = 16 }
        if (size == 0L) size = src.length - at
        if (size < hdr) return
        if (h.tag(4, "moov")) {
            val n = size - hdr
            if (n in 1..MAX_TAG_BYTES) mp4Walk(src.read(at + hdr, n.toInt()), 0, n.toInt(), acc)
            return
        }
        at += size
    }
}

private fun mp4Walk(b: ByteArray, from: Int, to: Int, acc: Acc) {
    var p = from
    while (p + 8 <= to) {
        val size = b.be32(p).toInt()
        if (size < 8 || p + size > to) return
        val name = latin1(b, p + 4, p + 8)
        val inner = p + 8
        val end = p + size
        when (name) {
            "udta", "ilst" -> mp4Walk(b, inner, end, acc)
            "meta" -> mp4Walk(b, inner + 4, end, acc)
            "moov", "trak" -> mp4Walk(b, inner, end, acc)
            "©nam", "©ART", "aART", "©alb", "©day", "©gen", "covr" -> mp4Item(b, name, inner, end, acc)
        }
        p = end
    }
}

private fun mp4Item(b: ByteArray, name: String, from: Int, to: Int, acc: Acc) {
    var p = from
    while (p + 16 <= to) {
        val size = b.be32(p).toInt()
        if (size < 16 || p + size > to) return
        if (b.tag(p + 4, "data")) {
            val kind = b.be32(p + 8).toInt() and 0xFFFFFF
            val s = p + 16
            val e = p + size
            if (name == "covr") {
                if (kind == 13 || kind == 14 || kind == 0) acc.offerCover(b.copyOfRange(s, e), 3)
            } else {
                val v = acc.text(b.decodeToString(s, e))
                when (name) {
                    "©nam" -> if (acc.title == null) acc.title = v
                    "©ART" -> if (acc.artist == null) acc.artist = v
                    "aART" -> if (acc.albumArtist == null) acc.albumArtist = v
                    "©alb" -> if (acc.album == null) acc.album = v
                    "©day" -> acc.yearOf(v)
                    "©gen" -> if (acc.genre == null) acc.genre = v
                }
            }
        }
        p += size
    }
}
