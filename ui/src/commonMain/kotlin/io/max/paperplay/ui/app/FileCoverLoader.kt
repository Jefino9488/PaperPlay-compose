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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Covers are image files on disk (restored from a backup or saved by the scanner); decoded off the main thread and kept in a small cache. */
class FileCoverLoader(private val maxSide: Int = 384, private val limit: Int = 150) : CoverLoader {
    private val cache = LinkedHashMap<String, ImageBitmap>()

    @Composable
    override fun painter(uri: String): Painter? {
        val bitmap by produceState(cache[uri], uri) {
            if (value != null) return@produceState
            val decoded = withContext(Dispatchers.IO) { decodeImageFile(uri, maxSide) }
            if (decoded != null) {
                // Written on the main thread only, since compose runs producers there.
                if (cache.size >= limit) cache.remove(cache.keys.first())
                cache[uri] = decoded
            }
            value = decoded
        }
        return remember(bitmap) { bitmap?.let { BitmapPainter(it) } }
    }
}
