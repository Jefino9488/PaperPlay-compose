package io.max.paperplay.ui

import androidx.compose.runtime.Composable

/** Local time and date for the standby clock. */
class ClockParts(val hour: Int, val minute: Int, val second: Int, val dateLabel: String)

expect fun clockNow(): ClockParts

/** While composed: keeps the screen on and sets the window brightness (0..1; 1 or more leaves the system level). No-op on desktop. */
@Composable
expect fun StandbyWindowEffect(brightness: Float)

/** Battery level 0..100, or null where unknown (desktop). */
@Composable
expect fun rememberBatteryPercent(): Int?

/** Where sound is going now: the device's name and whether it is headphones, earbuds or a speaker set apart from the phone. */
data class OutputDevice(val name: String, val external: Boolean)

@Composable
expect fun rememberOutputDevice(): OutputDevice
