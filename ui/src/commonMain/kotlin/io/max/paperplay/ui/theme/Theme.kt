package io.max.paperplay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.glassEffectsSupported
import kotlin.math.abs

const val FallbackSeed: Int = 0xFF7C5CFF.toInt()

/**
 * [seed] is the cover accent as ARGB (null for none). Without a seed, Android 12+ uses dynamic colour
 * when [dynamic] is on; otherwise the fallback seed is used. [amoled] gives true-black dark surfaces.
 */
@Composable
fun PaperPlayTheme(
    dark: Boolean = isSystemInDarkTheme(),
    seed: Int? = null,
    dynamic: Boolean = true,
    amoled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val platform = if (seed == null && dynamic) platformDynamicScheme(dark) else null
    val base = platform ?: schemeFromSeed(seed ?: FallbackSeed, dark)
    val scheme = if (dark && amoled) base.amoled() else base
    CompositionLocalProvider(LocalReducedTransparency provides !glassEffectsSupported()) {
        MaterialTheme(colorScheme = scheme, typography = PaperTypography, shapes = PaperShapes, content = content)
    }
}

/** True below API 31, where glass blur is unavailable: bars should be near-opaque. */
val LocalReducedTransparency = staticCompositionLocalOf { false }

/** Dynamic colour scheme from the system wallpaper; null where unsupported. */
@Composable
internal expect fun platformDynamicScheme(dark: Boolean): ColorScheme?

object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

val PaperShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private fun TextStyle.w(weight: Int) = copy(fontWeight = FontWeight(weight))

private val base = Typography()

/** Same weights as the Flutter theme (800 display and headline, 700 titles and labels, 500 body). */
val PaperTypography = Typography(
    displayLarge = base.displayLarge.w(800),
    displayMedium = base.displayMedium.w(800),
    displaySmall = base.displaySmall.w(800),
    headlineLarge = base.headlineLarge.w(800),
    headlineMedium = base.headlineMedium.w(800),
    headlineSmall = base.headlineSmall.w(700),
    titleLarge = base.titleLarge.w(700),
    titleMedium = base.titleMedium.w(700),
    titleSmall = base.titleSmall.w(700),
    bodyLarge = base.bodyLarge.w(500),
    bodyMedium = base.bodyMedium.w(500),
    bodySmall = base.bodySmall.w(500),
    labelLarge = base.labelLarge.w(700),
    labelMedium = base.labelMedium.w(700),
    labelSmall = base.labelSmall.w(700),
)

/** True-black surfaces for OLED, with containers stepping up so cards stay visible. */
fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF101010),
    surfaceContainer = Color(0xFF1B1B1B),
    surfaceContainerHigh = Color(0xFF232323),
    surfaceContainerHighest = Color(0xFF2C2C2C),
    surfaceBright = Color(0xFF343434),
)

/** Material 3 scheme built from one ARGB accent by hue, with tones following the M3 light and dark roles. */
fun schemeFromSeed(seed: Int, dark: Boolean): ColorScheme {
    val (h, sRaw) = hueSat(seed)
    val s = if (sRaw < 0.08f) 0f else sRaw.coerceIn(0.35f, 0.9f)
    fun c(hue: Float, sat: Float, l: Float) = hsl(hue, sat, l)
    val h2 = (h + 30f) % 360f
    val h3 = (h + 60f) % 360f
    val n = if (s == 0f) 0f else 0.12f
    return if (dark) darkColorScheme(
        primary = c(h, s, 0.78f), onPrimary = c(h, s, 0.20f),
        primaryContainer = c(h, s, 0.30f), onPrimaryContainer = c(h, s, 0.90f),
        secondary = c(h2, s * 0.35f, 0.76f), onSecondary = c(h2, s * 0.35f, 0.20f),
        secondaryContainer = c(h2, s * 0.30f, 0.28f), onSecondaryContainer = c(h2, s * 0.35f, 0.90f),
        tertiary = c(h3, s * 0.5f, 0.78f), onTertiary = c(h3, s * 0.5f, 0.20f),
        tertiaryContainer = c(h3, s * 0.45f, 0.30f), onTertiaryContainer = c(h3, s * 0.5f, 0.90f),
        background = c(h, n, 0.08f), onBackground = c(h, n, 0.90f),
        surface = c(h, n, 0.08f), onSurface = c(h, n, 0.90f),
        surfaceVariant = c(h, n * 1.5f, 0.26f), onSurfaceVariant = c(h, n * 1.5f, 0.80f),
        outline = c(h, n * 1.2f, 0.60f), outlineVariant = c(h, n * 1.5f, 0.30f),
        surfaceContainerLowest = c(h, n, 0.05f), surfaceContainerLow = c(h, n, 0.10f),
        surfaceContainer = c(h, n, 0.12f), surfaceContainerHigh = c(h, n, 0.16f),
        surfaceContainerHighest = c(h, n, 0.20f),
    ) else lightColorScheme(
        primary = c(h, s, 0.40f), onPrimary = Color.White,
        primaryContainer = c(h, s, 0.90f), onPrimaryContainer = c(h, s, 0.12f),
        secondary = c(h2, s * 0.35f, 0.40f), onSecondary = Color.White,
        secondaryContainer = c(h2, s * 0.40f, 0.90f), onSecondaryContainer = c(h2, s * 0.35f, 0.12f),
        tertiary = c(h3, s * 0.5f, 0.40f), onTertiary = Color.White,
        tertiaryContainer = c(h3, s * 0.55f, 0.90f), onTertiaryContainer = c(h3, s * 0.5f, 0.12f),
        background = c(h, n, 0.98f), onBackground = c(h, n, 0.10f),
        surface = c(h, n, 0.98f), onSurface = c(h, n, 0.10f),
        surfaceVariant = c(h, n * 1.5f, 0.90f), onSurfaceVariant = c(h, n * 1.5f, 0.30f),
        outline = c(h, n * 1.2f, 0.50f), outlineVariant = c(h, n * 1.5f, 0.80f),
        surfaceContainerLowest = Color.White, surfaceContainerLow = c(h, n, 0.96f),
        surfaceContainer = c(h, n, 0.94f), surfaceContainerHigh = c(h, n, 0.92f),
        surfaceContainerHighest = c(h, n, 0.90f),
    )
}

/** Hue in degrees and HSL saturation of an ARGB colour. */
internal fun hueSat(argb: Int): Pair<Float, Float> {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    if (d == 0f) return 0f to 0f
    val l = (max + min) / 2
    val s = d / (1f - abs(2 * l - 1f))
    val h = when (max) {
        r -> ((g - b) / d).mod(6f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    return h to s.coerceIn(0f, 1f)
}

internal fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2 * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2
    val (r, g, b) = when ((h / 60f).toInt() % 6) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
}
