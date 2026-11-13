package io.max.paperplay.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/** Tints the native Windows title bar to match the app (dark mode, caption and text colours). Windows 10 20H1+; caption colours need Windows 11. */
internal object WindowsTitleBar {
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: Pointer, attr: Int, value: IntByReference, size: Int): Int
    }

    /** Windows 11 rounds the corners of an undecorated window itself, cleanly anti-aliased; a transparent window clipped by Compose left dark fringes. Older Windows ignores it. */
    fun roundCorners(window: Window?) {
        val api = dwm ?: return
        val w = window ?: return
        runCatching { Native.getComponentPointer(w)?.let { api.DwmSetWindowAttribute(it, 33, IntByReference(2), 4) } } // DWMWA_WINDOW_CORNER_PREFERENCE = DWMWCP_ROUND
    }

    private val dwm: Dwm? = runCatching {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) Native.load("dwmapi", Dwm::class.java) else null
    }.getOrNull()

    /** [background] and [text] are ARGB ints from Compose. */
    fun apply(window: Window?, dark: Boolean, background: Int, text: Int) {
        val api = dwm ?: return
        val w = window ?: return
        runCatching {
            val ptr: Pointer = Native.getComponentPointer(w) ?: return
            val hwnd = ptr
            api.DwmSetWindowAttribute(hwnd, 20, IntByReference(if (dark) 1 else 0), 4) // DWMWA_USE_IMMERSIVE_DARK_MODE
            api.DwmSetWindowAttribute(hwnd, 35, IntByReference(bgr(background)), 4) // DWMWA_CAPTION_COLOR
            api.DwmSetWindowAttribute(hwnd, 36, IntByReference(bgr(text)), 4) // DWMWA_TEXT_COLOR
        }
    }

    /** Windows wants 0x00BBGGRR. */
    private fun bgr(argb: Int): Int = ((argb and 0xFF) shl 16) or (argb and 0xFF00) or ((argb shr 16) and 0xFF)
}
