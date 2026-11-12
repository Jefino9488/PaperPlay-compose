package io.max.paperplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/** The page background layer that cards and bars blur; null where no layer is provided (alpha fallback). */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 28.dp,
    blur: androidx.compose.ui.unit.Dp = 8.dp,
    tintAlpha: Float = 0.35f,
    content: @Composable () -> Unit,
) {
    val tint = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer
    val reduced = io.max.paperplay.ui.theme.LocalReducedTransparency.current
    val g = io.max.paperplay.ui.app.LocalUiPrefs.current?.glass ?: io.max.paperplay.ui.app.GlassStyle()
    val edge = when (g.edgeStyle) { 1 -> Highlight.Ambient; 2 -> Highlight.Plain; else -> Highlight.Default }
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedCornerShape(cornerRadius) },
            effects = {
                if (g.saturation != 1f) colorControls(saturation = g.saturation)
                blur(blur.toPx())
                if (glassLensSupported()) lens(g.lensHeight.dp.toPx(), g.lensAmount.dp.toPx(), g.depth, g.chromatic)
            },
            highlight = if (g.edgeWidth > 0f) ({ edge.copy(width = g.edgeWidth.dp, blurRadius = (g.edgeWidth / 2f).dp, alpha = g.edgeAlpha) }) else null,
            shadow = if (g.shadowRadius > 0f && g.shadowAlpha > 0f) ({ Shadow(radius = g.shadowRadius.dp, color = Color.Black.copy(alpha = g.shadowAlpha)) }) else null,
            innerShadow = if (g.innerAlpha > 0f) ({ InnerShadow(radius = g.innerRadius.dp, color = Color.White.copy(alpha = g.innerAlpha)) }) else null,
            onDrawSurface = { drawRect(tint.copy(alpha = if (reduced) 0.88f else tintAlpha)) },
        ),
    ) { content() }
}

/** True when blur/vibrancy RenderEffects work (Android 12+; always on desktop). Below that the surface is a plain translucent tint. */
internal expect fun glassEffectsSupported(): Boolean

/** Lens refraction needs Android shaders; desktop gets blur, tint and gradient only. */
internal expect fun glassLensSupported(): Boolean

@Preview
@Composable
private fun GlassSurfacePreview() {
    val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    GlassSurface(backdrop = backdrop, modifier = Modifier.size(200.dp, 100.dp)) {
        Text("Glass Surface", modifier = Modifier.padding(16.dp))
    }
}
