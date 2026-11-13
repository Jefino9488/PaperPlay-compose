package io.max.paperplay.ui.app

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import io.max.paperplay.ui.theme.Motion

/** How the page changes: one level deeper, one level back, or a sideways switch such as another tab. */
internal enum class NavMove { Forward, Back, Switch }

internal fun navMove(fromLevel: Int, toLevel: Int) = when {
    toLevel > fromLevel -> NavMove.Forward
    toLevel < fromLevel -> NavMove.Back
    else -> NavMove.Switch
}

/**
 * The transition between two pages. Going deeper slides the new page in from the right over the old one, which drifts
 * left; going back reverses that with the leaving page on top; a switch fades through with a slight scale.
 * [reduced] (Settings > Reduce motion) makes it instant.
 */
internal fun pageTransition(move: NavMove, reduced: Boolean): ContentTransform {
    if (reduced) return EnterTransition.None togetherWith ExitTransition.None
    val slide = tween<IntOffset>(Motion.STANDARD, easing = Motion.Emphasized)
    val fade = tween<Float>(Motion.STANDARD, easing = Motion.Standard)
    val quick = tween<Float>(Motion.FAST)
    return when (move) {
        NavMove.Forward -> (slideInHorizontally(slide) { it / 4 } + fadeIn(fade)) togetherWith
            (slideOutHorizontally(slide) { -it / 8 } + fadeOut(quick))
        NavMove.Back -> ContentTransform(
            targetContentEnter = slideInHorizontally(slide) { -it / 8 } + fadeIn(fade),
            initialContentExit = slideOutHorizontally(slide) { it / 4 } + fadeOut(quick),
            targetContentZIndex = -1f,
        )
        NavMove.Switch -> (fadeIn(tween(220, delayMillis = 90, easing = Motion.Standard)) + scaleIn(tween(220, delayMillis = 90, easing = Motion.Standard), initialScale = 0.98f)) togetherWith
            fadeOut(tween(90))
    }
}

/** What the phone's page area shows. A different key is a page change; [level] is how deep in the navigation it is. */
internal data class PageKey(val settings: Boolean, val detail: Detail?, val tab: Int, val tasks: Boolean) {
    val level get() = if (settings || detail != null || tasks) 1 else 0
}

/** What the desktop's page card shows. */
internal data class DeskKey(val now: Boolean, val settings: Boolean, val detail: Detail?, val page: String) {
    val level get() = if (now || settings || detail != null) 1 else 0
}
