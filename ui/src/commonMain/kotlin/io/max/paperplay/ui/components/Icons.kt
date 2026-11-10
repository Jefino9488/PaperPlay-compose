package io.max.paperplay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class TransportIcon { Play, Pause, Next, Previous }

/** Drawn transport glyphs, so no icon font is needed. */
@Composable
fun TransportGlyph(icon: TransportIcon, size: Dp = 24.dp, tint: Color = LocalContentColor.current) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        when (icon) {
            TransportIcon.Play -> drawPath(Path().apply {
                moveTo(w * 0.22f, h * 0.12f); lineTo(w * 0.88f, h * 0.5f); lineTo(w * 0.22f, h * 0.88f); close()
            }, tint)
            TransportIcon.Pause -> {
                drawRect(tint, Offset(w * 0.20f, h * 0.14f), Size(w * 0.22f, h * 0.72f))
                drawRect(tint, Offset(w * 0.58f, h * 0.14f), Size(w * 0.22f, h * 0.72f))
            }
            TransportIcon.Next -> {
                drawPath(Path().apply {
                    moveTo(w * 0.14f, h * 0.18f); lineTo(w * 0.68f, h * 0.5f); lineTo(w * 0.14f, h * 0.82f); close()
                }, tint)
                drawRect(tint, Offset(w * 0.74f, h * 0.18f), Size(w * 0.14f, h * 0.64f))
            }
            TransportIcon.Previous -> {
                drawPath(Path().apply {
                    moveTo(w * 0.86f, h * 0.18f); lineTo(w * 0.32f, h * 0.5f); lineTo(w * 0.86f, h * 0.82f); close()
                }, tint)
                drawRect(tint, Offset(w * 0.12f, h * 0.18f), Size(w * 0.14f, h * 0.64f))
            }
        }
    }
}

/** A round 48dp tap target around [content]. */
@Composable
fun IconTap(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.size(size).clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A tap target showing a text symbol. */
@Composable
fun GlyphButton(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    fontSize: Int = 20,
) {
    IconTap(description, onClick, modifier) {
        Text(glyph, color = tint, fontSize = fontSize.sp, style = MaterialTheme.typography.titleMedium)
    }
}
