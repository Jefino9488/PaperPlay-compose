package io.max.paperplay.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import io.max.paperplay.ui.components.CoverLoader
import io.max.paperplay.ui.decodeImageFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Covers are image files on disk (restored from a backup or saved by the scanner). They are decoded off the main thread,
 * at most six at a time, once per (file, size), and kept in a least-recently-used cache capped by memory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileCoverLoader(private val budgetBytes: Long = 48L * 1024 * 1024) : CoverLoader {
    // Touched on the main thread only, since compose runs producers there. Bounded by pixel memory, not count: one 1280 px cover is 6 MB.
    private val cache = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)
    private var cached = 0L
    private fun keep(key: String, bmp: ImageBitmap) {
        cache.put(key, bmp)?.let { cached -= it.width.toLong() * it.height * 4 }
        cached += bmp.width.toLong() * bmp.height * 4
        val iter = cache.entries.iterator()
        while (cached > budgetBytes && cache.size > 1 && iter.hasNext()) {
            val e = iter.next()
            if (e.key == key) continue
            cached -= e.value.width.toLong() * e.value.height * 4
            iter.remove()
        }
    }
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()
    private val decoder = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(6))

    @Composable
    override fun painter(uri: String, maxSide: Int): Painter? {
        val key = "$uri@$maxSide"
        val bitmap by produceState(cache[key], key) {
            value = cache[key]
            if (value != null) return@produceState
            val job = inFlight.getOrPut(key) { decoder.async { decodeImageFile(uri, maxSide) } }
            val decoded = job.await()
            inFlight.remove(key)
            if (decoded != null) keep(key, decoded)
            value = decoded
        }
        // Medium filtering mipmaps the downscale; the default shimmers and shows jagged edges on small covers.
        return remember(bitmap) { bitmap?.let { BitmapPainter(it, filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium) } }
    }
}

/** One decoded-cover cache for every window (main, mini player, tray menu) so a cover is held in memory once. */
object SharedCovers {
    val loader: FileCoverLoader by lazy { FileCoverLoader(32L * 1024 * 1024) }
}
