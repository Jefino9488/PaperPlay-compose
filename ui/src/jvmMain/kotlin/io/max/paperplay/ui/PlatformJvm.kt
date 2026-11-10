package io.max.paperplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

actual fun decodeImageFile(path: String, maxSide: Int): ImageBitmap? = runCatching {
    org.jetbrains.skia.Image.makeFromEncoded(File(path).readBytes()).toComposeImageBitmap()
}.getOrNull()

actual fun currentHour(): Int = java.time.LocalTime.now().hour

actual val isPhone: Boolean = false
