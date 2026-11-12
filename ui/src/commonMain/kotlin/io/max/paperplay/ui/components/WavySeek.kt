package io.max.paperplay.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Seek bar in the Material 3 Expressive style: the played part is a moving wave while audio plays
 * and flattens when paused. [progress] and [buffered] return 0 to 1 and are read while drawing; [onSeek] fires when the finger lifts.
 */
@Composable
fun WavySeek(
    progress: () -> Float,
    buffered: () -> Float,
    playing: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    longPressLabel: ((Float) -> String)? = null,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var longPressText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(longPressText) {
        if (longPressText != null) {
            delay(1500)
            longPressText = null
            dragging = null
        }
    }
    var phase by remember { mutableFloatStateOf(0f) }
    val moving = playing && dragging == null
    val wave by animateFloatAsState(if (moving) 1f else 0f, tween(350))
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) phase = (phase + (t - last) / 2_000_000_000f) % 1f
                last = t
            }
        }
    }
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val bufferedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f)

    BoxWithConstraints(modifier.fillMaxWidth().height(52.dp)) {
        Canvas(
            Modifier.fillMaxSize()
            .semantics {
                contentDescription = "Seek"
                if (label != null) stateDescription = label
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onSeek((it.x / size.width).coerceIn(0f, 1f)) },
                    onLongPress = {
                        val fraction = (it.x / size.width).coerceIn(0f, 1f)
                        dragging = fraction
                        longPressText = longPressLabel?.invoke(fraction) ?: label
                        onSeek(fraction)
                    },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let(onSeek); dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
        ) {
        val stroke = 5.dp.toPx()
        val amplitude = 3.5.dp.toPx() * wave
        val wavelength = 34.dp.toPx()
        val gap = 6.dp.toPx()
        val mid = 20.dp.toPx()
        val shown = (dragging ?: progress()).coerceIn(0f, 1f)
        val x = shown * size.width
        val line = Stroke(stroke, cap = StrokeCap.Round)

        val trackStart = min(x + gap, size.width)
        if (trackStart < size.width) {
            drawLine(track, Offset(trackStart, mid), Offset(size.width, mid), stroke, StrokeCap.Round)
            val bufferedEnd = buffered().coerceIn(0f, 1f) * size.width
            if (bufferedEnd > trackStart) {
                drawLine(bufferedColor, Offset(trackStart, mid), Offset(bufferedEnd, mid), stroke, StrokeCap.Round)
            }
        }
        val path = Path().apply {
            moveTo(0f, mid)
            val step = 4.dp.toPx()
            var px = step
            while (px <= x) {
                // Tapers to flat at the thumb, so the wave meets it cleanly.
                val taper = min(1f, (x - px) / 12.dp.toPx())
                lineTo(px, mid + sin((px / wavelength - phase) * 2 * PI.toFloat()) * amplitude * taper)
                px += step
            }
            lineTo(x, mid)
        }
        drawPath(path, active, style = line)
        val thumbH = (if (dragging != null) 30.dp else 22.dp).toPx()
        val thumbW = 6.dp.toPx()
        drawRoundRect(
            active, Offset(x - thumbW / 2, mid - thumbH / 2), Size(thumbW, thumbH), CornerRadius(thumbW / 2),
        )
        }
        longPressText?.let { text ->
            Text(
                text = text,
                color = active,
                style = TextStyle(fontSize = 12.sp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (maxWidth * (dragging ?: 0f) - 16.dp).coerceIn(0.dp, maxWidth - 32.dp), y = 32.dp),
            )
        }
    }
}

@Preview
@Composable
private fun WavySeekPlayingPreview() {
    WavySeek(progress = { 0.4f }, buffered = { 0.7f }, playing = true, onSeek = {}, label = "1:45")
}

@Preview
@Composable
private fun WavySeekPausedPreview() {
    WavySeek(progress = { 0.6f }, buffered = { 0.85f }, playing = false, onSeek = {}, label = "2:30")
}
