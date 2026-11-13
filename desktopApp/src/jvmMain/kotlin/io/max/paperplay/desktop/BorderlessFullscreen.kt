package io.max.paperplay.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/**
 * Full screen the way browsers do it on Windows: drop the caption and frame and cover the whole monitor. AWT's own exclusive full
 * screen left the caption and a black area behind, and minimised the window whenever it lost focus (a tray menu, the mini player).
 */
internal object BorderlessFullscreen {
    @Structure.FieldOrder("left", "top", "right", "bottom")
    class Rect : Structure() {
        @JvmField var left = 0
        @JvmField var top = 0
        @JvmField var right = 0
        @JvmField var bottom = 0
    }

    @Structure.FieldOrder("size", "monitor", "work", "flags")
    class MonitorInfo : Structure() {
        @JvmField var size = 40
        @JvmField var monitor = Rect()
        @JvmField var work = Rect()
        @JvmField var flags = 0
    }

    private interface User32 : StdCallLibrary {
        fun GetWindowLongW(hwnd: Pointer, index: Int): Int
        fun SetWindowLongW(hwnd: Pointer, index: Int, value: Int): Int
        fun GetWindowRect(hwnd: Pointer, rect: Rect): Boolean
        fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: MonitorInfo): Boolean
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
    }

    private val user32: User32? = runCatching {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) Native.load("user32", User32::class.java) else null
    }.getOrNull()

    val supported get() = user32 != null

    private var savedStyle = 0
    private var saved: Rect? = null

    private const val GWL_STYLE = -16
    private const val WS_CAPTION = 0x00C00000
    private const val WS_THICKFRAME = 0x00040000
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020
    private const val MONITOR_DEFAULTTONEAREST = 2

    /** Enters or leaves full screen. Returns false where it cannot (not Windows). Call on any thread. */
    fun set(window: Window, on: Boolean): Boolean {
        val api = user32 ?: return false
        val hwnd = runCatching { Native.getComponentPointer(window) }.getOrNull() ?: return false
        if (on) {
            if (saved != null) return true
            savedStyle = api.GetWindowLongW(hwnd, GWL_STYLE)
            saved = Rect().also { api.GetWindowRect(hwnd, it) }
            val info = MonitorInfo()
            val monitor = api.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) ?: return false
            if (!api.GetMonitorInfoW(monitor, info)) return false
            api.SetWindowLongW(hwnd, GWL_STYLE, savedStyle and (WS_CAPTION or WS_THICKFRAME).inv())
            val m = info.monitor
            api.SetWindowPos(hwnd, null, m.left, m.top, m.right - m.left, m.bottom - m.top, SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
        } else {
            val r = saved ?: return true
            api.SetWindowLongW(hwnd, GWL_STYLE, savedStyle)
            api.SetWindowPos(hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top, SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
            saved = null
        }
        return true
    }
}
