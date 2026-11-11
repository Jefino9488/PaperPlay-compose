package io.max.paperplay.core.account

/**
 * A small QR code encoder: byte mode, error correction level L, versions 1 to 9 (up to 230 bytes), mask 0.
 * Enough for a Telegram login link. [encode] returns rows of dark modules, or null when the text is too long.
 */
object QrCode {
    // Per version 1..9: error correction codewords per block, number of blocks, total data codewords.
    private val ecc = intArrayOf(7, 10, 15, 20, 26, 18, 20, 24, 30)
    private val blocks = intArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2)
    private val dataWords = intArrayOf(19, 34, 55, 80, 108, 136, 156, 194, 232)
    private val align = listOf(
        listOf(), listOf(6, 18), listOf(6, 22), listOf(6, 26), listOf(6, 30), listOf(6, 34), listOf(6, 22, 38), listOf(6, 24, 42), listOf(6, 26, 46),
    )

    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    private fun remainder(data: List<Int>, degree: Int): List<Int> {
        var gen = listOf(1)
        for (i in 0 until degree) {
            val next = MutableList(gen.size + 1) { 0 }
            for (j in gen.indices) {
                next[j] = next[j] xor gen[j]
                next[j + 1] = next[j + 1] xor mul(gen[j], exp[i])
            }
            gen = next
        }
        val result = MutableList(degree) { 0 }
        for (b in data) {
            val factor = b xor result.removeAt(0)
            result.add(0)
            for (i in 0 until degree) result[i] = result[i] xor mul(gen[i + 1], factor)
        }
        return result
    }

    fun encode(text: String): Array<BooleanArray>? {
        val bytes = text.encodeToByteArray()
        val v = (1..9).firstOrNull { 4 + 8 + 8 * bytes.size <= dataWords[it - 1] * 8 } ?: return null
        val total = dataWords[v - 1]
        val bits = ArrayList<Int>()
        fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) bits.add((value shr i) and 1) }
        put(4, 4)
        put(bytes.size, 8)
        for (b in bytes) put(b.toInt() and 0xFF, 8)
        for (i in 0 until minOf(4, total * 8 - bits.size)) bits.add(0)
        while (bits.size % 8 != 0) bits.add(0)
        val words = ArrayList<Int>()
        for (i in bits.indices step 8) { var w = 0; for (j in 0 until 8) w = (w shl 1) or bits[i + j]; words.add(w) }
        var pad = 0xEC
        while (words.size < total) { words.add(pad); pad = if (pad == 0xEC) 0x11 else 0xEC }

        val n = blocks[v - 1]
        val size = total / n
        val dataBlocks = (0 until n).map { words.subList(it * size, (it + 1) * size) }
        val eccBlocks = dataBlocks.map { remainder(it, ecc[v - 1]) }
        val all = ArrayList<Int>()
        for (i in 0 until size) for (b in dataBlocks) all.add(b[i])
        for (i in 0 until ecc[v - 1]) for (b in eccBlocks) all.add(b[i])

        val dim = 17 + 4 * v
        val dark = Array(dim) { BooleanArray(dim) }
        val fn = Array(dim) { BooleanArray(dim) }
        fun set(x: Int, y: Int, on: Boolean) { if (x in 0 until dim && y in 0 until dim) { dark[y][x] = on; fn[y][x] = true } }

        for (i in 0 until dim) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
        for ((cx, cy) in listOf(3 to 3, dim - 4 to 3, 3 to dim - 4)) {
            for (dy in -4..4) for (dx in -4..4) {
                val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                set(cx + dx, cy + dy, d != 2 && d != 4)
            }
        }
        val pos = align[v - 1]
        for (a in pos.indices) for (b in pos.indices) {
            if ((a == 0 && b == 0) || (a == 0 && b == pos.lastIndex) || (a == pos.lastIndex && b == 0)) continue
            for (dy in -2..2) for (dx in -2..2) set(pos[a] + dx, pos[b] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }
        // Format areas reserved now, written after masking.
        for (i in 0..8) { fn[8][i] = true; fn[i][8] = true }
        for (i in 0 until 8) { fn[8][dim - 1 - i] = true; fn[dim - 1 - i][8] = true }
        set(8, dim - 8, true)
        if (v >= 7) {
            var rem = v
            for (i in 0 until 12) rem = (rem shl 1) xor ((rem shr 11) * 0x1F25)
            val info = (v shl 12) or rem
            for (i in 0 until 18) {
                val bit = (info shr i) and 1 == 1
                val a = dim - 11 + i % 3
                val b = i / 3
                set(a, b, bit); set(b, a, bit)
            }
        }

        var i = 0
        var right = dim - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until dim) for (j in 0..1) {
                val x = right - j
                val upward = ((right + 1) and 2) == 0
                val y = if (upward) dim - 1 - vert else vert
                if (!fn[y][x] && i < all.size * 8) {
                    dark[y][x] = (all[i ushr 3] shr (7 - (i and 7))) and 1 == 1
                    i++
                }
            }
            right -= 2
        }
        for (y in 0 until dim) for (x in 0 until dim) if (!fn[y][x] && (x + y) % 2 == 0) dark[y][x] = !dark[y][x]

        // Format bits: level L (01), mask 0 (000).
        val data = 0b01000
        var rem = data
        for (k in 0 until 10) rem = (rem shl 1) xor ((rem shr 9) * 0x537)
        val format = ((data shl 10) or rem) xor 0x5412
        fun bit(k: Int) = (format shr k) and 1 == 1
        for (k in 0..5) dark[k][8] = bit(k)
        dark[7][8] = bit(6); dark[8][8] = bit(7); dark[8][7] = bit(8)
        for (k in 9..14) dark[8][14 - k] = bit(k)
        for (k in 0..7) dark[8][dim - 1 - k] = bit(k)
        for (k in 8..14) dark[dim - 15 + k][8] = bit(k)
        dark[dim - 8][8] = true
        return dark
    }
}
