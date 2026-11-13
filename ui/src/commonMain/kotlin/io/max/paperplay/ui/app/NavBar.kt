package io.max.paperplay.ui.app

import io.max.paperplay.ui.components.AppIcon

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.IconButton
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import io.max.paperplay.ui.GlassSurface
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.state.PlaybackUiState

private class NavEntry(val icon: ImageVector, val selected: ImageVector, val label: String)

private val entries = listOf(
    NavEntry(Icons.Outlined.Home, Icons.Rounded.Home, "Home"),
    NavEntry(Icons.Outlined.Search, Icons.Rounded.Search, "Search"),
    NavEntry(Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic, "Library"),
    NavEntry(Icons.Outlined.Person, Icons.Rounded.Person, "Profile"),
)

/** The bottom bar: floating glass or docked, laid out by the nav style prefs. */
@Composable
fun NavBar(selected: Int, backdrop: Backdrop, prefs: UiPrefs, inset: Dp, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, taskRunning: Boolean = false) {
    val layout = prefs.navLayout
    val labels = layout == NavLayout.Labels || layout == NavLayout.Dock
    val glass = prefs.look(LookGroup.Nav) == Look.Glass
    val height = if (labels) 76.dp else 64.dp
    val corner = (height / 2) * (prefs.barRadius / 100f)
    val docked = !prefs.floatingNav
    val items: @Composable () -> Unit = {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            entries.forEachIndexed { i, e ->
                val w by animateFloatAsState(if (layout == NavLayout.Pill && i == selected) 1.9f else 1f)
                NavItem(e, i == selected, prefs, if (layout == NavLayout.Dock || layout == NavLayout.Pill) Modifier.weight(w) else Modifier, taskRunning = taskRunning && i == 3) { onSelect(i) }
            }
        }
    }
    val bar = modifier.fillMaxWidth().height(height)
    when {
        docked && glass -> GlassSurface(backdrop, modifier.fillMaxWidth().height(height + inset), cornerRadius = 0.dp, blur = prefs.glassBlur.dp, tintAlpha = prefs.glassTint) {
            Box(Modifier.fillMaxSize().padding(bottom = inset)) { items() }
        }
        docked -> {
            Box(modifier.fillMaxWidth().height(height + inset).background(MaterialTheme.colorScheme.surface)) { Box(Modifier.fillMaxSize().padding(bottom = inset)) { items() } }
        }
        glass -> GlassSurface(backdrop, bar, cornerRadius = corner, blur = prefs.glassBlur.dp, tintAlpha = prefs.glassTint) { items() }
        else -> {
            val shape = RoundedCornerShape(corner)
            Box(bar.shadow(8.dp, shape).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)), shape)) { items() }
        }
    }
}

@Composable
private fun NavItem(e: NavEntry, on: Boolean, prefs: UiPrefs, modifier: Modifier, taskRunning: Boolean, onClick: () -> Unit) {
    val layout = prefs.navLayout
    val look = prefs.selectionLook
    val scheme = MaterialTheme.colorScheme
    val ink = if (on) (if (look == SelectionLook.Fill) scheme.onPrimaryContainer else scheme.primary) else scheme.onSurface
    val scale by animateFloatAsState(1f)
    val shape = RoundedCornerShape(28.dp * (prefs.selectionRadius / 100f))
    val bg = when {
        on && look == SelectionLook.Fill -> scheme.primaryContainer.copy(alpha = 0.9f)
        on && look == SelectionLook.Soft -> scheme.onSurface.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    val ring = if (on && look == SelectionLook.Ring) Modifier.border(BorderStroke(1.5.dp, scheme.primary), shape) else Modifier
    val icon: @Composable () -> Unit = {
        if (taskRunning) {
            BadgedBox(badge = { Badge() }) {
                AppIcon(if (on) e.selected else e.icon, e.label, Modifier.size(27.dp).scale(scale), tint = ink)
            }
        } else {
            AppIcon(if (on) e.selected else e.icon, e.label, Modifier.size(27.dp).scale(scale), tint = ink)
        }
    }
    val inner = Modifier.clip(shape).background(bg).then(ring).clickable(onClick = onClick).animateContentSize()
    when (layout) {
        NavLayout.Pill -> Row(modifier.padding(horizontal = 2.dp).then(inner).padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            icon()
            if (on) { Spacer(Modifier.width(8.dp)); Text(e.label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1) }
        }
        NavLayout.Icons -> Column(modifier.then(inner).padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            if (on && look == SelectionLook.Dot) Box(Modifier.padding(top = 2.dp).size(4.dp).clip(CircleShape).background(scheme.primary))
        }
        else -> Column(modifier.then(inner).padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Text(e.label, style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1, fontWeight = if (on) FontWeight.Bold else null)
            if (on && look == SelectionLook.Dot) Box(Modifier.padding(top = 2.dp).size(4.dp).clip(CircleShape).background(scheme.primary))
        }
    }
}

/** Solid card in the cover's tone (the theme's secondary container until cover colours exist). */
@Composable
fun MiniPlayer(
    state: PlaybackUiState, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    progress: () -> Float = { 0f },
    modifier: Modifier = Modifier,
) {
    val song = state.current ?: return
    val cover = coverScheme(song.coverUri)
    val bg by androidx.compose.animation.animateColorAsState(
        if (cover == null) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.lerp(cover.primaryContainer, Color.Black, 0.35f),
        androidx.compose.animation.core.tween(400),
    )
    val ink = cover?.onSurface ?: MaterialTheme.colorScheme.onSecondaryContainer
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).clickable(onClick = onOpen)) {
        Column {
            Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(song.coverUri, song.id, size = 44.dp, radius = 8.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.title, modifier = Modifier.basicMarquee(), maxLines = 1, style = MaterialTheme.typography.titleSmall, color = ink)
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.75f))
                }
                // Opens the system audio output chooser (speaker, Bluetooth, cast); only where the platform has one.
                val switchOutput = io.max.paperplay.ui.rememberOutputSwitcher()
                if (switchOutput != null) IconButton(switchOutput, Modifier.size(40.dp)) { AppIcon(Icons.Rounded.Devices, "Output devices", Modifier.size(22.dp), tint = ink) }
                IconButton(onPrevious) { AppIcon(Icons.Rounded.SkipPrevious, "Previous", tint = ink) }
                IconButton(onToggle) { AppIcon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(32.dp), tint = ink) }
                IconButton(onNext) { AppIcon(Icons.Rounded.SkipNext, "Next", tint = ink) }
            }
            Box(Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(ink.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth().height(2.dp).drawBehind { drawRect(ink, size = androidx.compose.ui.geometry.Size(size.width * progress().coerceIn(0f, 1f), size.height)) })
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Preview
@Composable
private fun NavBarPreview() {
    val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val prefs = UiPrefs({ null }, { _, _ -> })
    NavBar(selected = 0, backdrop = backdrop, prefs = prefs, inset = 0.dp, onSelect = {})
}

@Preview
@Composable
private fun MiniPlayerPreview() {
    val sampleSong = io.max.paperplay.ui.state.SongUi(id = "1", title = "Starlight", artist = "Muse", durationSec = 240)
    val state = PlaybackUiState(current = sampleSong, playing = true, durationMs = 240000)
    MiniPlayer(state = state, onOpen = {}, onToggle = {}, onNext = {}, onPrevious = {}, progress = { 0.25f })
}


@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Nav item", showBackground = true)
@Composable
private fun NavItemPreview() = io.max.paperplay.ui.PreviewFrame {
    NavItem(entries[0], on = true, prefs = io.max.paperplay.ui.previewPrefs(), modifier = Modifier, taskRunning = true, onClick = {})
}
