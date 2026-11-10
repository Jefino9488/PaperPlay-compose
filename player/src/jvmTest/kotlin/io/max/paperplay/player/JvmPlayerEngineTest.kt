package io.max.paperplay.player

import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Needs a system VLC; skipped (with a printed note) when libvlc cannot be found. */
class JvmPlayerEngineTest {
    private fun wav(seconds: Double, hz: Double): ByteArray {
        val rate = 22050
        val n = (rate * seconds).toInt()
        val b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(n * 2)
        repeat(n) { b.putShort((sin(2 * PI * hz * it / rate) * 8000).toInt().toShort()) }
        return b.array()
    }

    private fun <T> StateFlow<T>.await(timeoutMs: Long = 15_000, cond: (T) -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond(value)) {
            check(System.currentTimeMillis() < end) { "timeout, value=$value" }
            Thread.sleep(20)
        }
    }

    @Test
    fun queuePlaysAdvancesSeeksAndStreamsViaRanges() {
        val engine = try {
            JvmPlayerEngine("--aout=dummy")
        } catch (e: Throwable) {
            println("SKIPPED: VLC not available (${e.message})")
            return
        }
        try {
            val a = ByteArraySource(wav(2.0, 440.0))
            val b = ByteArraySource(wav(1.0, 880.0))
            engine.setQueue(listOf(QueueItem("a.wav", source = a), QueueItem("b.wav", source = b)))
            engine.state.await { it == PlayerState.Playing }
            engine.durationMs.await { it > 0 }
            assertTrue(engine.durationMs.value in 1800..2200, "duration ${engine.durationMs.value}")
            engine.positionMs.await { it > 300 }

            engine.seek(1000)
            engine.positionMs.await { it in 900..1900 && it > 900 }
            assertTrue(a.reads.any { it > 0 }, "seek should issue a ranged read, reads=${a.reads}")

            engine.currentIndex.await { it == 1 }
            engine.durationMs.await { it in 800..1200 }
            engine.state.await { it == PlayerState.Ended }
            assertEquals(1, engine.currentIndex.value)

            engine.previous()
            engine.currentIndex.await { it == 0 }
            engine.state.await { it == PlayerState.Playing }
            engine.pause()
            engine.state.await { it == PlayerState.Paused }
        } finally {
            engine.release()
        }
    }
}
