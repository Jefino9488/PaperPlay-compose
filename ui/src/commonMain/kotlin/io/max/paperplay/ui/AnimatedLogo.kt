package io.max.paperplay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val LogoBlue = Color(0xFF2563EB)
private val LogoPurple = Color(0xFF5B3BB5)

/**
 * The launcher icon, alive: on desktop the scalloped badge turns slowly; the paper plane bobs in the air, then every few seconds swoops off to the right and glides back
 * in from the left, leaving a dashed trail. On the phone it takes the system (Monet) colours like the themed launcher icon.
 */
@Composable
internal fun AnimatedLogo(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val t = rememberInfiniteTransition(label = "logo")
    val cycle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "cycle")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(24000, easing = LinearEasing)), label = "spin")
    val ripple by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Restart), label = "ripple")
    val base = if (isPhone) MaterialTheme.colorScheme.primary else LogoBlue
    val ink = if (isPhone) MaterialTheme.colorScheme.onPrimary else Color.White
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow)) }

    Canvas(modifier.size(size).graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
        val s = this.size.minDimension
        drawRoundRect(base, size = Size(s, s), cornerRadius = CornerRadius(s * 0.34f))

        val c = Offset(s / 2, s / 2)
        // The desktop icon keeps its colourful scalloped badge; the phone takes system colours and stays flat.
        if (!isPhone) {
            val badge = Path()
            val steps = 120
            for (i in 0..steps) {
                val a = i * 2 * PI / steps
                val r = s * 0.36f * (1f + 0.055f * sin(10 * a + ripple).toFloat())
                val p = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
                if (i == 0) badge.moveTo(p.x, p.y) else badge.lineTo(p.x, p.y)
            }
            badge.close()
            rotate(spin, c) { drawPath(badge, LogoPurple) }
        }

        // Plane flight: idle bob, swoop out right, glide in from the left.
        val (dx, lift, tilt, trail) = when {
            cycle < 0.62f -> Flight(0f, sin(cycle / 0.62f * 2 * PI).toFloat() * -0.025f, sin(cycle / 0.62f * 2 * PI).toFloat() * 4f, 0f)
            cycle < 0.78f -> {
                val p = (cycle - 0.62f) / 0.16f
                val e = p * p * p
                Flight(e * 1.1f, -e * 0.12f, -e * 14f, p)
            }
            else -> {
                val p = (cycle - 0.78f) / 0.22f
                val e = 1f - (1f - p) * (1f - p) * (1f - p)
                Flight(-1.1f * (1f - e), 0.1f * (1f - e), 10f * (1f - e), 1f - e)
            }
        }
        clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, s, s, CornerRadius(s * 0.34f))) }) {
            translate(left = dx * s, top = lift * s) {
                rotate(tilt, c) {
                    if (trail > 0.02f) for (i in 0..2) {
                        val y = s * (0.43f + 0.07f * i)
                        drawLine(
                            ink.copy(alpha = 0.55f * trail), Offset(s * (0.06f + 0.05f * i), y), Offset(s * (0.24f + 0.03f * i), y),
                            strokeWidth = s * 0.028f, cap = StrokeCap.Round,
                        )
                    }
                    val plane = Path().apply {
                        // The launcher icon's plane, point for point.
                        moveTo(s * 0.37f, s * 0.31f)
                        lineTo(s * 0.72f, s * 0.50f)
                        lineTo(s * 0.37f, s * 0.69f)
                        lineTo(s * 0.556f, s * 0.50f)
                        close()
                    }
                    drawPath(plane, ink)
                }
            }
        }
    }
}

private data class Flight(val dx: Float, val lift: Float, val tilt: Float, val trail: Float)
