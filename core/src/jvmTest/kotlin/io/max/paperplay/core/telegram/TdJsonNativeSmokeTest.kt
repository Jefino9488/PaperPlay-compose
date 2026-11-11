package io.max.paperplay.core.telegram

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Loads the real tdjson.dll; no network, login or tokens. Skipped when the DLL folder is absent. */
class TdJsonNativeSmokeTest {
    @Test fun executeGetOptionVersion() {
        val dir = File(System.getProperty("tdlib.dir") ?: "D:/Git forks/Teleplayer/windows/third_party/tdlib")
        if (!File(dir, "tdjson.dll").exists()) return
        System.setProperty("jna.library.path", dir.absolutePath)
        val out = TdJsonNative.execute("""{"@type":"getOption","name":"version"}""")
        assertTrue(out != null && "1.8." in out, out)
    }
}
