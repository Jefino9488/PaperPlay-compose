package io.max.paperplay.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** Timings and curves shared by every transition, so the app moves with one rhythm. */
object Motion {
    const val FAST = 150
    const val STANDARD = 300
    const val EMPHASIZED = 450

    val Standard: Easing = FastOutSlowInEasing

    /** A quick start and a long, soft landing, for things that arrive on screen. */
    val Emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}

/** True when the user turned motion off in Settings: every transition built from these helpers becomes instant. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun <T> motionTween(durationMs: Int = Motion.STANDARD, easing: Easing = Motion.Standard): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else tween(durationMs, easing = easing)

/** The spring the player sheet settles with. */
@Composable
fun <T> sheetSpring(): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

/** A lightly bouncy spring for things that settle, such as the nav bar pill. */
@Composable
fun <T> motionSpring(): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

/** For items of a lazy list with stable keys: they glide to a new place and fade in and out. Nothing when motion is reduced. */
@Composable
fun LazyItemScope.motionItem(): Modifier = if (LocalReduceMotion.current) Modifier else Modifier.animateItem()

/**
 * A fixed width that glides to [target] when it changes, such as a sidebar opening and closing. The glide is read in the
 * layout phase, so the composable that sets it does not recompose while it runs.
 */
@Composable
fun Modifier.animatedWidth(target: Dp): Modifier {
    val width = remember { Animatable(target.value) }
    val spec = motionTween<Float>(Motion.STANDARD, Motion.Emphasized)
    LaunchedEffect(target) { width.animateTo(target.value, spec) }
    return layout { measurable, constraints ->
        val w = width.value.dp.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/** A side panel opening from the right edge. */
@Composable
fun panelEnter(): EnterTransition =
    if (LocalReduceMotion.current) EnterTransition.None
    else expandHorizontally(tween(Motion.STANDARD, easing = Motion.Emphasized), expandFrom = Alignment.End) + fadeIn(tween(Motion.STANDARD))

/** A side panel closing towards the right edge. */
@Composable
fun panelExit(): ExitTransition =
    if (LocalReduceMotion.current) ExitTransition.None
    else shrinkHorizontally(tween(Motion.STANDARD, easing = Motion.Emphasized), shrinkTowards = Alignment.End) + fadeOut(tween(Motion.FAST))
