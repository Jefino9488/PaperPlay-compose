package io.max.paperplay.ui

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onBack: () -> Unit) {
    PredictiveBackHandler(enabled = enabled) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { onProgress(it.progress) }
            onBack()
        } catch (_: CancellationException) {
            // Back gesture cancelled by user
            onProgress(0f)
        }
    }
}

@Composable
actual fun rememberAppForeground(): Boolean {
    val owner = generateSequence(LocalContext.current) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<LifecycleOwner>().firstOrNull()
    var foreground by remember { mutableStateOf(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> foreground = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    return foreground
}

actual fun androidx.compose.ui.Modifier.mouseBackButton(onBack: () -> Unit): androidx.compose.ui.Modifier = this

actual fun decodeImageFile(path: String, maxSide: Int): ImageBitmap? = runCatching {
    if (path.startsWith("http://") || path.startsWith("https://")) {
        val conn = (URI.create(path).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "PaperPlay/0.1.0")
        }
        val bytes = try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
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

actual fun localTimeLabel(epochMs: Long): String =
    java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm:ss"))

actual fun currentHour(): Int = java.time.LocalTime.now().hour

actual val isPhone: Boolean = true

actual fun nowMoment(): io.max.paperplay.core.recs.Moment {
    val t = java.time.LocalDateTime.now()
    return io.max.paperplay.core.recs.Moment(System.currentTimeMillis(), t.year, t.monthValue, t.dayOfMonth, t.hour)
}
