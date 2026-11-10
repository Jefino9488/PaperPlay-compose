package io.max.paperplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 28.dp,
    content: @Composable () -> Unit,
) {
    val tint = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer
    val reduced = io.max.paperplay.ui.theme.LocalReducedTransparency.current
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedCornerShape(cornerRadius) },
            effects = {
                vibrancy()
                blur(8.dp.toPx())
                lens(12.dp.toPx(), 24.dp.toPx())
            },
            onDrawSurface = { drawRect(tint.copy(alpha = if (reduced) 0.88f else 0.35f)) },
        ),
    ) { content() }
}

/** True when blur/vibrancy RenderEffects work (Android 12+; always on desktop). Below that the surface is a plain translucent tint. */
internal expect fun glassEffectsSupported(): Boolean
