package io.max.paperplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** Runs [onBack] on the system back gesture or key while [enabled]. Desktop has no back gesture, so it does nothing there. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** False while the app is in the background, so animations that run every frame can stop. Always true on desktop. */
@Composable
expect fun rememberAppForeground(): Boolean

/** Calls [onBack] when the mouse back button is pressed. Does nothing on a phone. */
expect fun androidx.compose.ui.Modifier.mouseBackButton(onBack: () -> Unit): androidx.compose.ui.Modifier

/** Decodes the image file at [path] to at most about [maxSide] pixels on its longer side; null if unreadable. */
expect fun decodeImageFile(path: String, maxSide: Int): ImageBitmap?

/** Local hour of day, 0 to 23. */
expect fun currentHour(): Int

/** True on a phone, where the floating bar and glass settings apply; the desktop has a sidebar instead. */
expect val isPhone: Boolean

/** Local "dd/MM HH:mm:ss" for an epoch time in milliseconds. */
expect fun localTimeLabel(epochMs: Long): String

/** The local date and hour now, for planning the Home page. */
expect fun nowMoment(): io.max.paperplay.core.recs.Moment
