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
 * at most six at a time, once per (file, size), and kept in a small least-recently-used cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileCoverLoader(private val limit: Int = 200) : CoverLoader {
    // Touched on the main thread only, since compose runs producers there.
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > limit
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
            if (decoded != null) cache[key] = decoded
            value = decoded
        }
        // Medium filtering mipmaps the downscale; the default shimmers and shows jagged edges on small covers.
        return remember(bitmap) { bitmap?.let { BitmapPainter(it, filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium) } }
    }
}
