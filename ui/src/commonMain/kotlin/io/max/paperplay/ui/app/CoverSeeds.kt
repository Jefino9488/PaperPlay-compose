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
            val s = decodeImageFile(uri, 96)?.let(::seedOf) ?: 0
            ready[uri] = s
        }
        return null
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
internal fun seedOf(bitmap: ImageBitmap): Int? {
    val map = runCatching { bitmap.toPixelMap() }.getOrNull() ?: return null
    val step = maxOf(1, maxOf(map.width, map.height) / 64)
    val bins = 24 * 3 * 3
    val count = IntArray(bins)
    val sumR = FloatArray(bins)
    val sumG = FloatArray(bins)
    val sumB = FloatArray(bins)
    val sumS = FloatArray(bins)
    val muted = BooleanArray(bins)
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
                val hi = ((h / 15f).toInt()).coerceIn(0, 23)
                val si = if (s < 0.2f) 0 else if (s < 0.55f) 1 else 2
                val li = if (l < 0.3f) 0 else if (l < 0.7f) 1 else 2
                val i = (hi * 3 + si) * 3 + li
                count[i]++; sumR[i] += r; sumG[i] += g; sumB[i] += b; sumS[i] += s
                if (l < 0.1f || l > 0.92f || s < 0.1f) muted[i] = true
            }
            x += step
        }
        y += step
    }
    var best = -1
    var bestScore = 0f
    for (pass in 0..1) {
        for (i in 0 until bins) {
            if (count[i] == 0 || (pass == 0 && muted[i])) continue
            val score = count[i] * (0.15f + sumS[i] / count[i])
            if (score > bestScore) { bestScore = score; best = i }
        }
        if (best >= 0) break
    }
    if (best < 0) return null
    val n = count[best]
    fun ch(v: Float) = (v / n * 255f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(sumR[best]) shl 16) or (ch(sumG[best]) shl 8) or ch(sumB[best])
}
