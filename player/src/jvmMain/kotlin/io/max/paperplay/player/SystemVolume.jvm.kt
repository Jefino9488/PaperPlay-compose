package io.max.paperplay.player

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Windows master volume of the default output, through the Core Audio API (IMMDeviceEnumerator, then the device's
 * IAudioEndpointVolume), called straight through the COM method tables with JNA. Every call runs on one thread that has
 * initialised COM; other systems report no system volume.
 */
actual object SystemVolume {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "system-volume").apply { isDaemon = true } }

    actual fun get(): Float? = if (windows) onThread { Win.get() } else null

    actual fun set(volume: Float): Boolean = windows && onThread { Win.set(volume.coerceIn(0f, 1f)) } == true

    private fun <T> onThread(block: () -> T?): T? =
        runCatching { thread.submit(Callable { runCatching(block).getOrNull() }).get(2, TimeUnit.SECONDS) }.getOrNull()

    private object Win {
        private interface Ole32 : StdCallLibrary {
            fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
            fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, out: PointerByReference): Int
        }

        private val ole32: Ole32 by lazy { Native.load("ole32", Ole32::class.java) }
        private var initialised = false

        private val clsidEnumerator = guid("BCDE0395-E52F-467C-8E3D-C4579291692E")
        private val iidEnumerator = guid("A95664D2-9614-4F35-A746-DE8DB63617E6")
        private val iidEndpointVolume = guid("5CDF2C82-841E-4546-9722-0CF74078229A")

        /** A GUID in COM's memory layout: three little-endian numbers, then eight bytes. */
        private fun guid(text: String): Memory {
            val h = text.replace("-", "")
            return Memory(16).also { m ->
                m.setInt(0, h.substring(0, 8).toLong(16).toInt())
                m.setShort(4, h.substring(8, 12).toInt(16).toShort())
                m.setShort(6, h.substring(12, 16).toInt(16).toShort())
                for (i in 0 until 8) m.setByte(8L + i, h.substring(16 + 2 * i, 18 + 2 * i).toInt(16).toByte())
            }
        }

        /** Calls method [index] of the COM object [obj] (slot 0 to 2 are QueryInterface, AddRef, Release). */
        private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
            val table = obj.getPointer(0)
            val method = Function.getFunction(table.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
            return method.invokeInt(arrayOf<Any?>(obj, *args))
        }

        private fun release(p: Pointer?) { if (p != null) call(p, 2) }

        private fun <T> withEndpoint(block: (Pointer) -> T?): T? {
            if (!initialised) { ole32.CoInitializeEx(null, 2); initialised = true } // apartment threaded; an "already set" answer is fine
            val enumerator = PointerByReference()
            if (ole32.CoCreateInstance(clsidEnumerator, null, 23, iidEnumerator, enumerator) != 0) return null
            val en = enumerator.value ?: return null
            try {
                val device = PointerByReference()
                if (call(en, 4, 0, 1, device) != 0) return null // GetDefaultAudioEndpoint(eRender, eMultimedia)
                val dev = device.value ?: return null
                try {
                    val volume = PointerByReference()
                    if (call(dev, 3, iidEndpointVolume, 23, null, volume) != 0) return null // Activate
                    val ep = volume.value ?: return null
                    try { return block(ep) } finally { release(ep) }
                } finally { release(dev) }
            } finally { release(en) }
        }

        fun get(): Float? = withEndpoint { ep ->
            val level = Memory(4)
            if (call(ep, 9, level) == 0) level.getFloat(0) else null // GetMasterVolumeLevelScalar
        }

        fun set(volume: Float): Boolean = withEndpoint { ep -> call(ep, 7, volume, null) == 0 } == true // SetMasterVolumeLevelScalar
    }
}
