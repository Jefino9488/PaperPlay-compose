package io.max.paperplay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.max.paperplay.ui.app.IconSet
import io.max.paperplay.ui.app.LocalUiPrefs

enum class TransportIcon {
    Play,
    Pause,
    Next,
    Previous,
    Shuffle,
    Repeat,
    RepeatOne,
    Favorite,
    FavoriteBorder,
    Equalizer,
    Queue,
}

/** Expressive Material 3 transport icons. */
val TransportIcon.imageVector: ImageVector
    get() = when (this) {
        TransportIcon.Play -> Icons.Rounded.PlayArrow
        TransportIcon.Pause -> Icons.Rounded.Pause
        TransportIcon.Next -> Icons.Rounded.SkipNext
        TransportIcon.Previous -> Icons.Rounded.SkipPrevious
        TransportIcon.Shuffle -> Icons.Rounded.Shuffle
        TransportIcon.Repeat -> Icons.Rounded.Repeat
        TransportIcon.RepeatOne -> Icons.Rounded.RepeatOne
        TransportIcon.Favorite -> Icons.Rounded.Favorite
        TransportIcon.FavoriteBorder -> Icons.Rounded.FavoriteBorder
        TransportIcon.Equalizer -> Icons.Rounded.GraphicEq
        TransportIcon.Queue -> Icons.AutoMirrored.Rounded.QueueMusic
    }

/** Material 3 Expressive transport glyph icon component. */
@Composable
fun TransportGlyph(
    icon: TransportIcon,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
) {
    AppIcon(
        imageVector = icon.imageVector,
        contentDescription = icon.name,
        modifier = modifier.size(size),
        tint = tint,
    )
}

/** Renders the selected curated icon family while retaining Material vectors as the fallback. */
@Composable
fun AppIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val selected = if (LocalUiPrefs.current?.iconSet == IconSet.Tabler) {
        tablerIconForMaterial(imageVector.name) ?: imageVector
    } else {
        imageVector
    }
    Icon(selected, contentDescription, modifier, tint)
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
        modifier
            .size(size)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Expressive Material 3 Icon Button wrapper. */
@Composable
fun ExpressiveIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    buttonSize: Dp = 48.dp,
    tint: Color = LocalContentColor.current,
) {
    IconTap(
        description = description,
        onClick = onClick,
        modifier = modifier,
        size = buttonSize,
    ) {
        AppIcon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}

/** A tap target showing a text symbol or vector icon. */
@Composable
fun GlyphButton(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    fontSize: Int = 20,
) {
    val vector = when (glyph) {
        "▶", "play" -> Icons.Rounded.PlayArrow
        "âšâš", "pause" -> Icons.Rounded.Pause
        "▶▶", "next" -> Icons.Rounded.SkipNext
        "â—€â—€", "prev", "previous" -> Icons.Rounded.SkipPrevious
        "â˜…", "star" -> Icons.Rounded.Star
        else -> null
    }
    IconTap(description, onClick, modifier) {
        if (vector != null) {
            AppIcon(
                imageVector = vector,
                contentDescription = description,
                modifier = Modifier.size(24.dp),
                tint = tint,
            )
        } else {
            Text(
                text = glyph,
                color = tint,
                fontSize = fontSize.sp,
                lineHeight = fontSize.sp,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                ),
            )
        }
    }
}

@Preview
@Composable
private fun TransportGlyphPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TransportGlyph(TransportIcon.Play)
        TransportGlyph(TransportIcon.Pause)
        TransportGlyph(TransportIcon.Next)
        TransportGlyph(TransportIcon.Previous)
        TransportGlyph(TransportIcon.Shuffle)
        TransportGlyph(TransportIcon.Favorite)
    }
}

@Preview
@Composable
private fun IconTapPreview() {
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        IconTap(description = "Tap me", onClick = {}) {
            AppIcon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Preview
@Composable
private fun GlyphButtonPreview() {
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        GlyphButton(glyph = "▶", description = "Play", onClick = {})
    }
}

