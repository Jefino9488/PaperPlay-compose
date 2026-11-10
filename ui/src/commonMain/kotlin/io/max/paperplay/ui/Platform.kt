package io.max.paperplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** Runs [onBack] on the system back gesture or key while [enabled]. Desktop has no back gesture, so it does nothing there. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Decodes the image file at [path] to at most about [maxSide] pixels on its longer side; null if unreadable. */
expect fun decodeImageFile(path: String, maxSide: Int): ImageBitmap?

/** Local hour of day, 0 to 23. */
expect fun currentHour(): Int
