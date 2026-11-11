package io.max.paperplay.ui

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    PredictiveBackHandler(enabled = enabled) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { }
            onBack()
        } catch (_: CancellationException) {
            // Back gesture cancelled by user
        }
    }
}

actual fun decodeImageFile(path: String, maxSide: Int): ImageBitmap? = runCatching {
    if (path.startsWith("http://") || path.startsWith("https://")) {
        val bytes = URI.create(path).toURL().openStream().use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    val file = File(path)
    if (file.exists()) {
        val ext = file.extension.lowercase()
        if (ext in setOf("mp3", "flac", "m4a", "aac", "ogg", "wav", "opus")) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val art = retriever.embeddedPicture
                if (art != null) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(art, 0, art.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                    return BitmapFactory.decodeByteArray(art, 0, art.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

actual fun currentHour(): Int = java.time.LocalTime.now().hour

actual val isPhone: Boolean = true

actual fun nowMoment(): io.max.paperplay.core.recs.Moment {
    val t = java.time.LocalDateTime.now()
    return io.max.paperplay.core.recs.Moment(System.currentTimeMillis(), t.year, t.monthValue, t.dayOfMonth, t.hour)
}
