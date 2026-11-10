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
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedCornerShape(cornerRadius) },
            effects = {
                vibrancy()
                blur(8.dp.toPx())
                lens(12.dp.toPx(), 24.dp.toPx())
            },
            onDrawSurface = { drawRect(Color.White.copy(alpha = if (glassEffectsSupported()) 0.25f else 0.6f)) },
        ),
    ) { content() }
}

/** True when blur/vibrancy RenderEffects work (Android 12+; always on desktop). Below that the surface is a plain translucent tint. */
internal expect fun glassEffectsSupported(): Boolean
