package io.max.paperplay.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary

/**
 * Makes Windows treat the app as a music player: its own app id (taskbar grouping, volume mixer name) and the
 * keyboard media keys (play/pause, next, previous, stop), which work whether or not the window has focus.
 */
internal object WindowsMedia {
    @Structure.FieldOrder("hwnd", "message", "wParam", "lParam", "time", "x", "y", "priv")
    class Msg : Structure() {
        @JvmField var hwnd: Pointer? = null
        @JvmField var message = 0
        @JvmField var wParam = 0L
        @JvmField var lParam = 0L
        @JvmField var time = 0
        @JvmField var x = 0
        @JvmField var y = 0
        @JvmField var priv = 0
    }

    private interface User32 : StdCallLibrary {
        fun RegisterHotKey(hwnd: Pointer?, id: Int, mods: Int, vk: Int): Boolean
        fun GetMessageW(msg: Msg, hwnd: Pointer?, min: Int, max: Int): Int
    }

    private interface Shell32 : StdCallLibrary {
        fun SetCurrentProcessExplicitAppUserModelID(id: com.sun.jna.WString): Int
    }

    /** [onKey] gets "playpause", "next", "previous" or "stop" on a background thread. */
    fun start(onKey: (String) -> Unit) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        runCatching { Native.load("shell32", Shell32::class.java).SetCurrentProcessExplicitAppUserModelID(com.sun.jna.WString("io.max.paperplay")) }
        val thread = Thread({
            runCatching {
                val u = Native.load("user32", User32::class.java)
                val keys = mapOf(0xB3 to "playpause", 0xB0 to "next", 0xB1 to "previous", 0xB2 to "stop")
                keys.keys.forEachIndexed { i, vk -> u.RegisterHotKey(null, i + 1, 0x4000, vk) } // MOD_NOREPEAT
                val ids = keys.values.toList()
                val msg = Msg()
                while (u.GetMessageW(msg, null, 0, 0) > 0) {
                    if (msg.message == 0x0312) ids.getOrNull(msg.wParam.toInt() - 1)?.let(onKey) // WM_HOTKEY
                }
            }
        }, "media-keys").apply { isDaemon = true }
        thread.start()
    }
}
