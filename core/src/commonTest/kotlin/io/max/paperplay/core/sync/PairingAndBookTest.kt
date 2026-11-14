package io.max.paperplay.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingAndBookTest {
    private var now = 1_000L
    private var n = 0
    private fun guard() = PairingGuard({ now }, { (100_000 + n++).toString() })

    @Test fun pinWorksOnceAndExpires() {
        val g = guard()
        val pin = g.newPin()
        assertEquals(6, pin.code.length)
        assertEquals(CodeAttempt.Ok, g.attempt("a") { it == pin.code })
        assertEquals(CodeAttempt.Wrong, g.attempt("a") { it == pin.code }) // single use: gone
        val p2 = g.newPin()
        now += 3 * 60_000
        assertNull(g.currentPin())
        assertEquals(CodeAttempt.Wrong, g.attempt("a") { it == p2.code })
    }

    @Test fun newCodeInvalidatesOld() {
        val g = guard()
        val old = g.newPin()
        g.setPassword("hunter22")
        g.reset()
        assertEquals(CodeAttempt.Wrong, g.attempt("a") { it == old.code || it == "hunter22" })
        assertTrue(g.activeSecrets().isEmpty())
    }

    @Test fun passwordIsNotConsumed() {
        val g = guard()
        g.setPassword("hunter22")
        assertEquals(CodeAttempt.Ok, g.attempt("a") { it == "hunter22" })
        assertEquals(CodeAttempt.Ok, g.attempt("a") { it == "hunter22" })
    }

    @Test fun lockoutAfterFiveWrongThenRecovers() {
        val g = guard()
        val pin = g.newPin()
        repeat(5) { assertEquals(CodeAttempt.Wrong, g.attempt("1.2.3.4") { false }) }
        assertTrue(g.isLocked("1.2.3.4"))
        assertEquals(CodeAttempt.Locked, g.attempt("1.2.3.4") { it == pin.code }) // even the right code
        assertEquals(CodeAttempt.Ok, g.attempt("5.6.7.8") { it == pin.code }) // other source is fine
        now += 6 * 60_000
        assertFalse(g.isLocked("1.2.3.4"))
    }

    @Test fun overallLockoutAcrossSources() {
        val g = guard()
        repeat(15) { i -> g.attempt("s$i") { false } }
        assertEquals(CodeAttempt.Locked, g.attempt("new") { true })
    }

    @Test fun oldWrongTriesDoNotAddUpToALockout() {
        val g = guard()
        repeat(4) { g.attempt("a") { false } }
        now += 6 * 60_000 // past the lock window: the four typos are forgotten
        assertEquals(CodeAttempt.Wrong, g.attempt("a") { false })
        assertFalse(g.isLocked("a"))
    }

    @Test fun aSuccessClearsTheOverallCount() {
        val g = guard()
        repeat(14) { i -> g.attempt("s$i") { false } }
        val pin = g.newPin()
        assertEquals(CodeAttempt.Ok, g.attempt("friend") { it == pin.code })
        repeat(14) { i -> assertEquals(CodeAttempt.Wrong, g.attempt("t$i") { false }) } // 28 wrong in all, never locked
    }

    private fun book(store: Array<String?> = arrayOf(null)) = AddressBook({ store[0] }, { store[0] = it }, { now })

    @Test fun addressBookOrdersNewestSeenFirstAndPersists() {
        val store = arrayOf<String?>(null)
        val b = book(store)
        assertTrue(b.add("10.0.0.5"))
        assertTrue(b.add("10.0.0.6:5000", "Desk"))
        assertFalse(b.add("not an address!"))
        now = 2_000; b.learn("10.0.0.7", lanPort, "dev1", "Phone")
        now = 3_000; b.learn("10.0.0.8", lanPort, "dev1", "Phone")
        assertEquals(listOf("10.0.0.8:47872", "10.0.0.7:47872"), b.forDevice("dev1").map { it.key })
        now = 4_000; b.learn("10.0.0.7", lanPort, "dev1", null) // seen again: now first
        assertEquals("10.0.0.7:47872", b.forDevice("dev1").first().key)
        assertEquals("Phone", b.forDevice("dev1").first().name)
        assertEquals(4, b.entries.value.size)
        // reload from the stored text
        val again = book(store)
        assertEquals(b.entries.value.map { it.key }, again.entries.value.map { it.key })
        again.remove("10.0.0.5:47872")
        assertEquals(3, again.entries.value.size)
        again.record("10.0.0.8:47872", AddressState.Unreachable)
        assertEquals(AddressState.Unreachable, again.entries.value.first { it.host == "10.0.0.8" }.lastResult)
    }
}
