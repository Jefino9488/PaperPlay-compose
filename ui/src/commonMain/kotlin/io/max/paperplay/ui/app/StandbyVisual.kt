package io.max.paperplay.ui.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import io.max.paperplay.ui.rememberAppForeground
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

enum class StandbyStyle(val label: String) { Bars("Bars"), Wave("Wave"), Rings("Rings"), Orb("Orb") }

/**
 * A procedural music visual. It is not tied to the audio signal: the level follows the playing state and
 * volume, and the rhythm comes from a tempo and phases seeded by the song id. One ~30 fps frame loop that
 * runs only while [playing] and the app is in front; time is read in the draw phase so nothing recomposes.
 */
@Composable
fun StandbyVisual(style: StandbyStyle, color: Color, playing: Boolean, volume: Float, seed: String, modifier: Modifier = Modifier) {
    val foreground = rememberAppForeground()
    val running = playing && foreground
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (now - last >= 33_000_000L) { last = now; time = (now / 1_000_000L) / 1000f }
            }
        }
    }
    val rhythm = remember(seed) { Rhythm(seed) }
    val level = if (running) 0.35f + 0.65f * volume.coerceIn(0f, 1f) else 0.12f
    Canvas(modifier) {
        val t = if (running) time else 0f
        val kick = if (running) rhythm.kick(t) else 0f
        when (style) {
            StandbyStyle.Bars -> drawBars(rhythm, t, kick, level, color)
            StandbyStyle.Wave -> drawWave(rhythm, t, kick, level, color)
            StandbyStyle.Rings -> drawRings(rhythm, t, kick, level, color)
            StandbyStyle.Orb -> drawOrb(rhythm, t, kick, level, color)
        }
    }
}

/** Tempo and per-band phases and speeds derived from the song id, so each song moves in its own way. */
private class Rhythm(seed: String) {
    private val h = seed.fold(7L) { a, c -> a * 31 + c.code }.let { abs(it) }
    val bpm = 84f + (h % 56)
    private fun frac(n: Int): Float = (((h * (n + 3) * 2654435761L) ushr 7) % 1000L) / 1000f
    val phase = FloatArray(48) { frac(it) * 2f * PI.toFloat() }
    val speed = FloatArray(48) { 1.2f + frac(it + 60) * 3.2f }
    fun kick(t: Float): Float { val p = (t * bpm / 60f) % 1f; return exp(-p * 5f) }
    /** 0..1 for band [i] of [n]; low bands follow the kick more. */
    fun band(i: Int, n: Int, t: Float, kick: Float): Float {
        val k = i % 48
        val wob = 0.5f + 0.5f * sin(t * speed[k] + phase[k])
        val low = 1f - i.toFloat() / n
        return (0.12f + 0.88f * wob * (0.5f + 0.5f * (kick * low + (1f - low) * 0.6f))).coerceIn(0f, 1f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBars(r: Rhythm, t: Float, kick: Float, level: Float, color: Color) {
    val n = 32
    val slot = size.width / n
    val w = slot * 0.5f
    for (i in 0 until n) {
        val a = if (level <= 0.12f) 0.06f + 0.04f * sin(i * 0.6f + r.phase[i % 48]) else r.band(i, n, t, kick) * level
        val half = (size.height * 0.5f * a.coerceIn(0.03f, 1f))
        val x = slot * (i + 0.5f)
        drawLine(color.copy(alpha = 0.35f + 0.5f * a), Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half), w, StrokeCap.Round)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWave(r: Rhythm, t: Float, kick: Float, level: Float, color: Color) {
    val mid = size.height / 2
    val amp = size.height * 0.4f * level * (0.45f + 0.55f * (if (level > 0.12f) 0.5f + 0.5f * kick else 0.3f))
    for (layer in 0 until 3) {
        val path = Path()
        val steps = 48
        for (s in 0..steps) {
            val x = size.width * s / steps
            val u = s.toFloat() / steps
            val env = sin(u * PI.toFloat())
            val y = mid + amp * env * (sin(u * (5f + layer * 2f) * PI.toFloat() + t * r.speed[layer] + r.phase[layer]) * (1f - layer * 0.28f))
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color.copy(alpha = 0.8f - layer * 0.25f), style = Stroke(width = 4f + (2 - layer) * 2f, cap = StrokeCap.Round))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRings(r: Rhythm, t: Float, kick: Float, level: Float, color: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val max = min(size.width, size.height) / 2
    for (i in 0 until 4) {
        val p = if (level > 0.12f) ((t * r.bpm / 240f) + i / 4f) % 1f else (i + 1) / 5f
        val radius = max * (0.25f + 0.7f * p) * (1f + 0.05f * kick * level)
        drawCircle(color.copy(alpha = (1f - p) * 0.8f * level.coerceAtLeast(0.3f)), radius, c, style = Stroke(width = 3f + 5f * (1f - p)))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOrb(r: Rhythm, t: Float, kick: Float, level: Float, color: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val max = min(size.width, size.height) / 2
    val breathe = if (level > 0.12f) kick * level else 0.04f
    val core = max * (0.45f + 0.18f * breathe + 0.04f * sin(t * 1.3f + r.phase[0]))
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), c, max), max * (0.9f + 0.1f * breathe), c)
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.35f)), c, core), core, c)
}
