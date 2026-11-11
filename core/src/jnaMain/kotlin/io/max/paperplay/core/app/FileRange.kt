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

internal actual fun fileLength(path: String): Long = java.io.File(path).let { if (it.isFile) it.length() else -1L }

internal actual fun deleteFile(path: String) { runCatching { java.io.File(path).delete() } }
