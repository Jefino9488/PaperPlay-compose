package io.max.paperplay.ui.app

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import io.max.paperplay.ui.decodeImageFile
import io.max.paperplay.ui.theme.schemeFromSeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The main colour of each cover, worked out once off the main thread and kept for the process.
 * Reading [seed] inside a composition subscribes it, so the colour applies as soon as it is ready.
 */
object CoverSeeds {
    private val ready = mutableStateMapOf<String, Int>()
    private val pending = HashSet<String>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** The seed for [uri] as ARGB if known; null while unknown, missing or colourless. */
    fun seed(uri: String?): Int? {
        if (uri == null) return null
        val known = ready[uri]
        if (known != null) return known.takeIf { it != 0 }
        if (pending.add(uri)) scope.launch {
            val p = decodeImageFile(uri, 96)?.let { paletteOf(it, 3) }.orEmpty()
            palettes[uri] = p
            ready[uri] = p.firstOrNull() ?: 0
        }
        return null
    }

    private val palettes = mutableStateMapOf<String, List<Int>>()

    /** Up to three distinct main colours of the cover, most present first; empty while unknown. */
    fun palette(uri: String?): List<Int> {
        if (uri == null) return emptyList()
        seed(uri)
        return palettes[uri].orEmpty()
    }
}

/** A colour scheme in the cover's colours, or null until its seed is known. */
@Composable
fun coverScheme(uri: String?, dark: Boolean = true): ColorScheme? {
    val seed = CoverSeeds.seed(uri) ?: return null
    return remember(seed, dark) { schemeFromSeed(seed, dark) }
}

/**
 * The most present colourful tone: pixels are binned by hue, saturation and lightness, and the
 * bin with the most pixels, weighted by saturation, wins. Near-black and near-white bins only
 * count when nothing else is there.
 */
internal fun seedOf(bitmap: ImageBitmap): Int? = paletteOf(bitmap, 1).firstOrNull()

/** Up to [max] distinct main colours of the cover, most present first. Same method as [seedOf]. */
internal fun paletteOf(bitmap: ImageBitmap, max: Int): List<Int> {
    val map = runCatching { bitmap.toPixelMap() }.getOrNull() ?: return emptyList()
    val step = maxOf(1, maxOf(map.width, map.height) / 64)
    val bins = 12 * 3
    var all = 0
    var allR = 0f; var allG = 0f; var allB = 0f
    val count = IntArray(bins)
    val sumR = FloatArray(bins)
    val sumG = FloatArray(bins)
    val sumB = FloatArray(bins)
    val sumS = FloatArray(bins)
    var y = 0
    while (y < map.height) {
        var x = 0
        while (x < map.width) {
            val c = map[x, y]
            if (c.alpha >= 0.5f) {
                val r = c.red; val g = c.green; val b = c.blue
                val max = maxOf(r, g, b); val min = minOf(r, g, b)
                val l = (max + min) / 2
                val d = max - min
                val s = if (d == 0f) 0f else d / (1f - abs(2 * l - 1f))
                val h = when {
                    d == 0f -> 0f
                    max == r -> ((g - b) / d).mod(6f)
                    max == g -> (b - r) / d + 2f
                    else -> (r - g) / d + 4f
                } * 60f
                allR += r; allG += g; allB += b; all++
                // Near-black, near-white and grey pixels cannot give a colour; they only matter when nothing else is there.
                if (l >= 0.1f && l <= 0.92f && s >= 0.1f) {
                    // Wide hue sectors and no lightness split, so a blue-grey sky that varies in tone still counts as one colour.
                    val hi = ((h / 30f).toInt()).coerceIn(0, 11)
                    val si = if (s < 0.3f) 0 else if (s < 0.6f) 1 else 2
                    val i = hi * 3 + si
                    count[i]++; sumR[i] += r; sumG[i] += g; sumB[i] += b; sumS[i] += s
                }
            }
            x += step
        }
        y += step
    }
    // Presence first; saturation only breaks ties, so a few vivid pixels do not beat the cover's main colour.
    val ranked = (0 until bins).filter { count[it] > 0 }.sortedByDescending { count[it] * (0.6f + sumS[it] / count[it]) }
    val picked = ArrayList<Int>()
    for (i in ranked) {
        if (picked.size >= max) break
        val hue = i / 3
        // Colours in the same or the next hue sector would look like one colour in a gradient.
        if (picked.none { p -> minOf(abs(p / 3 - hue), 12 - abs(p / 3 - hue)) < 2 }) picked += i
    }
    if (picked.isEmpty()) {
        if (all == 0) return emptyList()
        fun av(v: Float) = (v / all * 255f).toInt().coerceIn(0, 255)
        return listOf((0xFF shl 24) or (av(allR) shl 16) or (av(allG) shl 8) or av(allB))
    }
    return picked.map { i ->
        val n = count[i]
        fun ch(v: Float) = (v / n * 255f).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (ch(sumR[i]) shl 16) or (ch(sumG[i]) shl 8) or ch(sumB[i])
    }
}
