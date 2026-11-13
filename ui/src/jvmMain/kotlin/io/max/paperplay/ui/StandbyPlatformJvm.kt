package io.max.paperplay.ui

import androidx.compose.runtime.Composable

actual fun clockNow(): ClockParts {
    val t = java.time.LocalDateTime.now()
    return ClockParts(t.hour, t.minute, t.second, t.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM")))
}

@Composable
actual fun StandbyWindowEffect(brightness: Float) {}

@Composable
actual fun rememberBatteryPercent(): Int? = null

@Composable
actual fun rememberOutputDevice(): OutputDevice = OutputDevice("This computer", external = false)
