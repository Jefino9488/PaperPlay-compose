package io.max.paperplay.ui.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import io.max.paperplay.ui.rememberAppForeground
import kotlinx.coroutines.isActive
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val MAX_PARTS = 200
private const val TAU = 6.2831855f
private const val FRAME_NANOS = 33_000_000L

/**
 * Built-in animated backgrounds. One frame loop (about 30 fps) moves a single time value that is read only while drawing,
 * so nothing recomposes per frame. The loop stops while the app is in the background.
 */
@Composable
fun AnimatedBackdrop(anim: BgAnim, particle: Color, bg: Color, speed: Float, density: Float, modifier: Modifier = Modifier) {
    val foreground = rememberAppForeground()
    val time = remember { mutableFloatStateOf(0f) }
    // x, y, size, phase of every particle, in 0..1; made once.
    val seeds = remember { Random(7).let { r -> FloatArray(MAX_PARTS * 4) { r.nextFloat() } } }
    val path = remember { Path() }
    LaunchedEffect(foreground, speed) {
        if (!foreground) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last == 0L) last = now
                val dt = now - last
                if (dt >= FRAME_NANOS) {
                    time.floatValue = (time.floatValue + dt / 1_000_000_000f * speed) % 100_000f
                    last = now
                }
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        drawRect(bg)
        val t = time.floatValue
        when (anim) {
            BgAnim.Stars -> drawStars(seeds, t, particle, density)
            BgAnim.Snow -> drawSnow(seeds, t, particle, density)
            BgAnim.Bubbles -> drawBubbles(seeds, t, particle, density)
            BgAnim.Aurora -> drawAurora(t, particle, density)
            BgAnim.Waves -> drawWaves(path, t, particle, density)
        }
    }
}

private fun count(density: Float, min: Int, max: Int) = (min + (max - min) * density.coerceIn(0f, 1f)).toInt().coerceAtMost(MAX_PARTS)

private fun DrawScope.drawStars(s: FloatArray, t: Float, c: Color, density: Float) {
    val w = size.width; val h = size.height
    for (i in 0 until count(density, 30, 200)) {
        val b = i * 4
        val twinkle = 0.5f + 0.5f * sin(t * (0.6f + s[b + 2] * 1.6f) + s[b + 3] * TAU)
        drawCircle(c.copy(alpha = 0.15f + 0.85f * twinkle), (0.6f + s[b + 2] * 1.6f) * this.density, Offset(s[b] * w, s[b + 1] * h))
    }
}

private fun DrawScope.drawSnow(s: FloatArray, t: Float, c: Color, density: Float) {
    val w = size.width; val h = size.height; val d = this.density
    for (i in 0 until count(density, 30, 180)) {
        val b = i * 4
        val fall = (s[b + 1] * h + t * (18f + s[b + 2] * 40f) * d) % h
        val x = s[b] * w + sin(t * 0.5f + s[b + 3] * TAU) * 18f * d
        drawCircle(c.copy(alpha = 0.35f + 0.55f * s[b + 2]), (1.2f + s[b + 2] * 2.6f) * d, Offset(x, fall))
    }
}

private fun DrawScope.drawBubbles(s: FloatArray, t: Float, c: Color, density: Float) {
    val w = size.width; val h = size.height; val d = this.density
    for (i in 0 until count(density, 8, 60)) {
        val b = i * 4
        val r = (6f + s[b + 2] * 26f) * d
        val rise = (s[b + 1] * (h + 2 * r) + t * (14f + s[b + 2] * 30f) * d) % (h + 2 * r)
        val x = s[b] * w + sin(t * 0.7f + s[b + 3] * TAU) * 12f * d
        val center = Offset(x, h + r - rise)
        drawCircle(c.copy(alpha = 0.06f), r, center)
        drawCircle(c.copy(alpha = 0.35f), r, center, style = Stroke(1.2f * d))
    }
}

private fun DrawScope.drawAurora(t: Float, c: Color, density: Float) {
    val w = size.width; val h = size.height
    val second = lerp(c, Color.White, 0.4f)
    val blobs = count(density, 3, 7).coerceAtMost(7)
    for (i in 0 until blobs) {
        val p = i * 1.7f
        val cx = w * (0.5f + 0.45f * sin(t * 0.13f + p))
        val cy = h * (0.25f + 0.2f * cos(t * 0.17f + p * 1.3f))
        val r = maxOf(w, h) * (0.35f + 0.1f * sin(t * 0.21f + p))
        val col = if (i % 2 == 0) c else second
        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.38f), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
    }
}

private fun DrawScope.drawWaves(path: Path, t: Float, c: Color, density: Float) {
    val w = size.width; val h = size.height
    val layers = count(density, 2, 5).coerceIn(2, 5)
    val step = 16f * this.density
    for (l in 0 until layers) {
        val baseY = h * (0.55f + 0.38f * l / layers)
        val amp = h * (0.03f + 0.01f * l)
        val freq = TAU / (w * (0.7f - 0.08f * l))
        val dir = if (l % 2 == 0) 1f else -1f
        path.rewind()
        path.moveTo(0f, h)
        var x = 0f
        while (x < w + step) {
            path.lineTo(x, baseY + amp * sin(x * freq + t * 0.6f * dir + l))
            x += step
        }
        path.lineTo(w + step, h)
        path.close()
        drawPath(path, c.copy(alpha = 0.10f + 0.06f * l))
    }
}

@Preview
@Composable
private fun AnimatedBackdropPreview() {
    io.max.paperplay.ui.PreviewFrame(dark = true) {
        AnimatedBackdrop(BgAnim.Stars, Color.White, Color(0xFF0B1026), 1f, 0.5f)
    }
}
