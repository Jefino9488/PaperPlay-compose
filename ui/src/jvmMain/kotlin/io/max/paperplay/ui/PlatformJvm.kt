package io.max.paperplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.net.URI

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

actual fun decodeImageFile(path: String, maxSide: Int): ImageBitmap? = runCatching {
    val bytes = if (path.startsWith("http://") || path.startsWith("https://")) {
        URI.create(path).toURL().openStream().use { it.readBytes() }
    } else {
        File(path).readBytes()
    }
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

actual fun currentHour(): Int = java.time.LocalTime.now().hour

actual val isPhone: Boolean = false

actual fun nowMoment(): io.max.paperplay.core.recs.Moment {
    val t = java.time.LocalDateTime.now()
    return io.max.paperplay.core.recs.Moment(System.currentTimeMillis(), t.year, t.monthValue, t.dayOfMonth, t.hour)
}
