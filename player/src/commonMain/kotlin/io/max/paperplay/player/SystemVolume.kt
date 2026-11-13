package io.max.paperplay.player

/**
 * The device's own media volume, 0 to 1, so the app's volume bar moves the same volume as the hardware keys and the system
 * mixer instead of a second, app-only level. [get] is null where the platform offers no such control (the caller then keeps
 * its own volume). Calls may block for a moment, so call them off the UI thread.
 */
expect object SystemVolume {
    fun get(): Float?
    /** True when the volume was changed. */
    fun set(volume: Float): Boolean
}
