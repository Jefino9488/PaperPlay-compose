package io.max.paperplay.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

actual fun clockNow(): ClockParts {
    val t = java.time.LocalDateTime.now()
    return ClockParts(t.hour, t.minute, t.second, t.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM")))
}

@Composable
actual fun StandbyWindowEffect(brightness: Float) {
    val view = LocalView.current
    val ctx = LocalContext.current
    DisposableEffect(brightness) {
        val activity = generateSequence(ctx) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        val windows = listOfNotNull((view.parent as? DialogWindowProvider)?.window, activity?.window)
        val level = if (brightness >= 1f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness.coerceIn(0.01f, 1f)
        windows.forEach { w ->
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w.attributes = w.attributes.also { it.screenBrightness = level }
        }
        onDispose {
            windows.forEach { w ->
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                w.attributes = w.attributes.also { it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }
}

@Composable
actual fun rememberBatteryPercent(): Int? {
    val ctx = LocalContext.current
    var pct by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                if (level >= 0 && scale > 0) pct = level * 100 / scale
            }
        }
        val sticky = ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        receiver.onReceive(ctx, sticky)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    return pct
}
