package io.max.paperplay.ui.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.LocalCoverLoader
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class BgMode(val label: String) {
    Plain("Plain"), Cover("Cover"), CoverBlur("Blurred cover"), Gradient("Gradient"), Wallpaper("Wallpaper"), Animated("Animated"),
}

enum class BgAnim(val label: String) { Stars("Stars"), Snow("Snow"), Aurora("Aurora"), Bubbles("Bubbles"), Waves("Waves") }

/**
 * The page background layer. It is the only thing drawn behind the content, so the glass layer backdrop can record it.
 * Every mode ends on the theme's background colour or a scrim of it, so text keeps its contrast in light and dark.
 */
@Composable
fun AppBackground(prefs: UiPrefs, coverUri: String?, base: Color, modifier: Modifier = Modifier) {
    val dark = base.luminance() < 0.5f
    Box(modifier.fillMaxSize().background(base)) {
        when (prefs.background) {
            BgMode.Plain -> {}
            BgMode.Cover -> CoverWash(coverUri, base, dark)
            BgMode.CoverBlur -> CoverBlurLayer(coverUri, base)
            BgMode.Gradient -> GradientLayer(prefs, coverUri, base, dark)
            BgMode.Wallpaper -> WallpaperLayer(prefs, base)
            BgMode.Animated -> AnimatedBackdrop(prefs.bgAnim, Color(prefs.bgAnimC1), Color(prefs.bgAnimC2), prefs.bgAnimSpeed, prefs.bgAnimDensity)
        }
    }
}

/** The colour at the top of the page for the chosen mode, used to tint the window and status areas. */
fun backgroundTopColor(prefs: UiPrefs, coverTint: Color, base: Color): Color = when (prefs.background) {
    BgMode.Gradient -> Color(prefs.bgGradC1)
    BgMode.Animated -> Color(prefs.bgAnimC2)
    else -> coverTint.copy(alpha = 0.85f).compositeOver(base)
}

@Composable
private fun CoverWash(coverUri: String?, base: Color, dark: Boolean) {
    val tint = coverScheme(coverUri, dark)?.primaryContainer ?: MaterialTheme.colorScheme.primaryContainer
    val g0 = animateColorAsState(tint.copy(alpha = 0.85f).compositeOver(base), tween(700))
    val g1 = animateColorAsState(tint.copy(alpha = 0.32f).compositeOver(base), tween(700))
    // The animated colours are read while drawing, so the 700 ms wash does not recompose.
    Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.verticalGradient(0f to g0.value, 0.45f to g1.value, 1f to base)) })
}

@Composable
private fun CoverBlurLayer(coverUri: String?, base: Color) {
    val loader = LocalCoverLoader.current
    // A small bitmap stretched to the screen is already soft, which also covers Android below 12 where blur does nothing.
    Crossfade(coverUri, Modifier.fillMaxSize(), animationSpec = tween(700), label = "coverBlur") { uri ->
        val painter = if (uri != null) loader?.painter(uri, 96) else null
        if (painter != null) {
            Image(
                painter, null, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }.blur(40.dp),
                contentScale = ContentScale.Crop,
            )
        }
    }
    Box(Modifier.fillMaxSize().background(base.copy(alpha = 0.55f)))
}

@Composable
private fun GradientLayer(prefs: UiPrefs, coverUri: String?, base: Color, dark: Boolean) {
    val fromCover = if (prefs.bgGradCover) coverScheme(coverUri, dark)?.primaryContainer else null
    val c1 = animateColorAsState(fromCover ?: Color(prefs.bgGradC1), tween(700))
    val c2 = Color(prefs.bgGradC2)
    val c3 = prefs.bgGradC3.takeIf { it != 0 }?.let { Color(it) }
    val angle = prefs.bgGradAngle
    Box(Modifier.fillMaxSize().drawBehind { drawRect(angledBrush(listOf(c1.value, c2) + listOfNotNull(c3), angle, size.width, size.height)) })
}

/** A linear gradient along [angle] degrees (0 left to right, 90 top to bottom) that spans the whole box. */
internal fun angledBrush(colors: List<Color>, angle: Float, w: Float, h: Float): Brush {
    val a = angle * (PI / 180.0).toFloat()
    val dx = cos(a); val dy = sin(a)
    val r = (abs(w * dx) + abs(h * dy)) / 2f
    val cx = w / 2f; val cy = h / 2f
    return Brush.linearGradient(colors, Offset(cx - dx * r, cy - dy * r), Offset(cx + dx * r, cy + dy * r))
}

@Composable
private fun WallpaperLayer(prefs: UiPrefs, base: Color) {
    val path = prefs.bgImage
    val painter = if (path != null) LocalCoverLoader.current?.painter(path, 1920) else null
    if (painter != null) {
        val blur = prefs.bgBlur
        Image(
            painter, null,
            Modifier.fillMaxSize().then(if (blur > 0.5f) Modifier.graphicsLayer { scaleX = 1.1f; scaleY = 1.1f }.blur(blur.dp) else Modifier),
            contentScale = ContentScale.Crop,
        )
    }
    Box(Modifier.fillMaxSize().background(base.copy(alpha = prefs.bgDim.coerceIn(0f, 0.95f))))
}

@Preview
@Composable
private fun AppBackgroundPreview() {
    val prefs = io.max.paperplay.ui.previewPrefs()
    io.max.paperplay.ui.PreviewFrame { AppBackground(prefs, null, MaterialTheme.colorScheme.background) }
}
