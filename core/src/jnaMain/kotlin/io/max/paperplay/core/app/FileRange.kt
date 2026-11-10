package io.max.paperplay.core.app

import java.io.RandomAccessFile

internal actual fun readRange(path: String, offset: Long, length: Int): ByteArray =
    RandomAccessFile(path, "r").use { f ->
        f.seek(offset)
        val buf = ByteArray(length)
        var n = 0
        while (n < length) {
            val r = f.read(buf, n, length - n)
            if (r < 0) break
            n += r
        }
        if (n == length) buf else buf.copyOf(n)
    }
