package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.max.paperplay.core.recs.WeatherKind
import io.max.paperplay.ui.StandbyWindowEffect
import io.max.paperplay.ui.clockNow
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.rememberBatteryPercent
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.theme.schemeFromSeed
import kotlinx.coroutines.delay
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Android standby: a near-black screen with a clock, the song and a slow procedural animation.
 * Tap or swipe down to leave. Callers show it on phones only.
 */
@Composable
fun StandbyScreen(state: PlaybackUiState, backend: PlayerControls, prefs: StandbyPrefs, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        StandbyWindowEffect(prefs.dim)
        StandbyContent(state, backend, prefs, onClose)
    }
}

@Composable
private fun StandbyContent(state: PlaybackUiState, backend: PlayerControls, p: StandbyPrefs, onClose: () -> Unit) {
    var clock by remember { mutableStateOf(clockNow()) }
    val showClock = p.shows(StandbyWidget.Clock)
    // Once a second also when only the drift needs it, so the shift below is smooth.
    LaunchedEffect(showClock, p.pixelShift) { while (true) { clock = clockNow(); delay(if (showClock || p.pixelShift) 1000L else 20_000L) } }
    var sheet by remember { mutableStateOf(false) }
    val tone = standbyTone(p, state)
    val song = state.current
    val web = backend as? CoreBackend

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total > 160f) onClose() }) { _, dy -> total += dy }
            },
    ) {
        val wide = maxWidth > maxHeight
        val boxHeight = maxHeight
        // AMOLED burn-in: everything, the settings button included, drifts slowly along a path that never repeats exactly
        // (periods of 7 and 5 minutes), by up to 6% of the shorter side, so no pixel stays lit.
        val seconds = clock.hour * 3600 + clock.minute * 60 + clock.second
        val amp = minOf(maxWidth, maxHeight) * 0.06f
        val shiftX = if (p.pixelShift) amp * kotlin.math.sin(2.0 * kotlin.math.PI * seconds / 420.0).toFloat() else 0.dp
        val shiftY = if (p.pixelShift) amp * kotlin.math.sin(2.0 * kotlin.math.PI * seconds / 300.0 + 1.0).toFloat() else 0.dp
        val scale = p.clockSize
        val body: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                if (showClock) {
                    val h = if (p.hour24) clock.hour else (clock.hour % 12).let { if (it == 0) 12 else it }
                    Text(
                        "${if (p.hour24) h.toString().padStart(2, '0') else h.toString()}:${clock.minute.toString().padStart(2, '0')}",
                        color = tone, fontSize = (92 * scale).sp, fontWeight = FontWeight.Light, maxLines = 1,
                    )
                    Text(clock.dateLabel + if (!p.hour24) (if (clock.hour < 12) "  AM" else "  PM") else "", color = tone.copy(alpha = 0.6f), style = MaterialTheme.typography.titleMedium)
                }
                if (p.shows(StandbyWidget.Song) && song != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (p.shows(StandbyWidget.Cover)) CoverImage(song.coverUri, song.id, Modifier.size(56.dp), size = 56.dp, radius = 10.dp)
                    Column(horizontalAlignment = if (p.shows(StandbyWidget.Cover)) Alignment.Start else Alignment.CenterHorizontally) {
                        Text(song.title, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                        Text(song.artist, color = tone.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (p.shows(StandbyWidget.Progress) && song != null) StandbyProgress(state, backend, tone)
                if (p.shows(StandbyWidget.Controls)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(backend::previous) { AppIcon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(32.dp), tint = tone) }
                    IconButton(backend::togglePlay, Modifier.size(64.dp)) {
                        AppIcon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(44.dp), tint = tone)
                    }
                    IconButton(backend::next) { AppIcon(Icons.Rounded.SkipNext, "Next", Modifier.size(32.dp), tint = tone) }
                }
                if (p.shows(StandbyWidget.Next)) state.queue.getOrNull(state.queueIndex + 1)?.let {
                    Text("Next: ${it.title} - ${it.artist}", color = tone.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                if (p.shows(StandbyWidget.Lyrics) && song != null) StandbyLyric(song.id, backend, tone)
                if (p.shows(StandbyWidget.Weather) && web != null) {
                    val place by web.weatherPlace.collectAsState()
                    val kind by web.weather.collectAsState()
                    place?.let { Text(it.name + (kind?.let { k -> ", " + weatherWord(k) } ?: ""), color = tone.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall) }
                }
                if (p.shows(StandbyWidget.Battery)) rememberBatteryPercent()?.let {
                    Text("Battery $it%", color = tone.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val visual: @Composable (Modifier) -> Unit = { m ->
            StandbyVisual(p.style, tone, state.playing, state.volume, song?.id.orEmpty(), m)
        }
        Box(Modifier.fillMaxSize().offset(shiftX, shiftY)) {
            if (wide) Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                body(Modifier.weight(1f).fillMaxHeight())
                visual(Modifier.weight(1f).fillMaxHeight().padding(vertical = 24.dp))
            } else Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                body(Modifier.weight(1f).fillMaxWidth())
                visual(Modifier.fillMaxWidth().height(boxHeight * 0.28f))
            }
        }
        IconButton({ sheet = true }, Modifier.align(Alignment.TopEnd).offset(shiftX, shiftY).padding(8.dp)) {
            AppIcon(Icons.Rounded.Settings, "Customise standby", tint = tone.copy(alpha = 0.35f))
        }
        if (sheet) StandbyCustomiseSheet(p) { sheet = false }
    }
}

@Composable
private fun standbyTone(p: StandbyPrefs, state: PlaybackUiState): Color {
    val seed = CoverSeeds.seed(state.current?.coverUri)
    val primary = MaterialTheme.colorScheme.primary
    val base = when (p.color) {
        "custom" -> Color(p.customColor)
        "accent" -> primary
        else -> if (seed != null) remember(seed) { schemeFromSeed(seed, dark = true).primary } else primary
    }
    // Pulled slightly toward white so it reads at low brightness.
    return lerp(base, Color.White, 0.2f).copy(alpha = 0.9f)
}

private fun weatherWord(k: WeatherKind) = when (k) {
    WeatherKind.CLEAR -> "clear"
    WeatherKind.CLOUDY -> "cloudy"
    WeatherKind.RAIN -> "rain"
    WeatherKind.STORM -> "storm"
    WeatherKind.SNOW -> "snow"
}

/** A thin bar; the position is read in its own scope so a tick never recomposes the screen. */
@Composable
private fun StandbyProgress(state: PlaybackUiState, backend: PlayerControls, tone: Color) {
    val pos by backend.position.collectAsState()
    val fraction = pos.progress(state.durationMs)
    Box(Modifier.fillMaxWidth(0.8f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(tone.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(tone.copy(alpha = 0.7f)))
    }
}

@Composable
private fun StandbyLyric(id: String, backend: PlayerControls, tone: Color) {
    var text by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { text = runCatching { backend.lyricsFor(id) }.getOrNull() }
    val lines = remember(text) { text?.let { io.max.paperplay.core.metadata.parseLrc(it) }.orEmpty() }
    if (lines.isEmpty()) return
    val pos by backend.position.collectAsState()
    val current by remember(lines) { derivedStateOf { lines.indexOfLast { it.timeMs <= pos.positionMs } } }
    val line = lines.getOrNull(current)?.text.orEmpty()
    Text(line.ifBlank { " " }, color = tone.copy(alpha = 0.75f), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
}

@Preview(name = "Standby", widthDp = 390, heightDp = 844)
@Composable
private fun StandbyPreview() = io.max.paperplay.ui.PreviewFrame(dark = true) {
    StandbyContent(io.max.paperplay.ui.previewPlayback(), io.max.paperplay.ui.FakePlayerControls(), StandbyPrefs({ null }, { _, _ -> }), onClose = {})
}
