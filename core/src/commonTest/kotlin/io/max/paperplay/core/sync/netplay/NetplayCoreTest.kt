package io.max.paperplay.core.sync.netplay

import io.max.paperplay.core.sync.CodeAttempt
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetplayCoreTest {
    private var now = 1_000L
    private var n = 0
    private fun guard() = NetplayGuard({ now }, { (100_000 + n++).toString() })

    private fun song(id: String, title: String = "Song $id", artist: String = "Artist", durMs: Long = 200_000, size: Long = 1000) =
        NetSong(id, title, artist, "Album", durMs, size)

    // Session PIN and wrong PIN limits

    @Test fun pinIsNotSingleUseAndDoesNotExpire() {
        val g = guard()
        val pin = g.open()
        assertEquals(6, pin.length)
        assertEquals(CodeAttempt.Ok, g.attempt("a") { it == pin })
        assertEquals(CodeAttempt.Ok, g.attempt("b") { it == pin })
        now += 24 * 60 * 60_000L
        assertEquals(CodeAttempt.Ok, g.attempt("c") { it == pin })
    }

    @Test fun wrongPinLocksTheAddressAfterFiveTries() {
        val g = guard()
        val pin = g.open()
        repeat(4) { assertEquals(CodeAttempt.Wrong, g.attempt("a") { false }) }
        assertEquals(CodeAttempt.Wrong, g.attempt("a") { false })
        // locked now, even with the right PIN
        assertEquals(CodeAttempt.Locked, g.attempt("a") { it == pin })
        assertTrue(g.isLocked("a"))
        // another address is not locked
        assertEquals(CodeAttempt.Ok, g.attempt("b") { it == pin })
        now += 5 * 60_000L + 1
        assertEquals(CodeAttempt.Ok, g.attempt("a") { it == pin })
    }

    @Test fun manyAddressesTogetherLockEveryone() {
        val g = guard()
        val pin = g.open()
        for (i in 0 until 15) g.attempt("src$i") { false }
        assertEquals(CodeAttempt.Locked, g.attempt("fresh") { it == pin })
    }

    @Test fun regenerateReplacesThePinAndClearsLocks() {
        val g = guard()
        val old = g.open()
        repeat(5) { g.attempt("a") { false } }
        assertTrue(g.isLocked("a"))
        val fresh = g.regenerate()
        assertTrue(old != fresh)
        assertFalse(g.isLocked("a"))
        assertEquals(CodeAttempt.Wrong, g.attempt("a") { it == old })
        assertEquals(CodeAttempt.Ok, g.attempt("a") { it == fresh })
    }

    @Test fun closedSessionAcceptsNothing() {
        val g = guard()
        g.open()
        g.close()
        assertNull(g.currentPin())
        assertEquals(CodeAttempt.Wrong, g.attempt("a") { true })
    }

    // What a guest may send

    @Test fun guestPolicyAllowsOnlyPlayControlAndAudio() {
        assertTrue(GuestPolicy.allows("ping", false, false))
        assertTrue(GuestPolicy.allows("bye", false, false))
        assertFalse(GuestPolicy.allows("ctl", false, true))
        assertTrue(GuestPolicy.allows("ctl", true, false))
        assertFalse(GuestPolicy.allows("range", true, false))
        assertTrue(GuestPolicy.allows("range", false, true))
        for (t in listOf("sync", "auth", "snapshot", "settings", "secrets", "queue", "state", "", "anything")) {
            assertFalse(GuestPolicy.allows(t, true, true), t)
        }
        assertFalse(GuestPolicy.allows(null, true, true))
    }

    // Link

    @Test fun linkRoundTrips() {
        val link = NetplayLink(listOf("192.168.1.5:47873", "10.0.0.7:50000"), "123456", "Max's phone & co")
        val text = link.encode()
        assertTrue(text.startsWith("paperplay://netplay?h="))
        val back = NetplayLink.parse(text)
        assertNotNull(back)
        assertEquals(link, back)
        assertEquals(listOf("192.168.1.5" to 47873, "10.0.0.7" to 50000), back.endpoints())
    }

    @Test fun linkRejectsBadInput() {
        assertNull(NetplayLink.parse("https://example.com"))
        assertNull(NetplayLink.parse("paperplay://netplay?h=192.168.1.5&p=12345&n=x"))
        assertNull(NetplayLink.parse("paperplay://netplay?p=123456&n=x"))
        assertNull(NetplayLink.parse("paperplay://netplay?h=bad host&p=123456"))
        assertEquals(netplayPort, NetplayLink.parse("paperplay://netplay?h=192.168.1.5&p=123456")!!.endpoints().single().second)
    }

    @Test fun linkKeepsThreeAddressesAtMost() {
        val link = NetplayLink((1..6).map { "192.168.1.$it:47873" }, "000111", "n")
        assertEquals(3, NetplayLink.parse(link.encode())!!.hosts.size)
    }

    // Clock offset

    @Test fun clockOffsetFromPingPong() {
        val c = ClockSync()
        // host clock is 5000 ms ahead; one way delay 20 ms each way
        c.add(sentLocal = 1_000, hostTime = 1_000 + 20 + 5_000, recvLocal = 1_040)
        assertEquals(5_000, c.offsetMs)
        assertEquals(40, c.rttMs)
        assertEquals(15_000, c.hostNow(10_000))
    }

    @Test fun clockUsesTheSampleWithTheSmallestRoundTrip() {
        val c = ClockSync()
        // a slow, lopsided sample (reply delayed) is wrong by 200 ms
        c.add(0, 5_020, 440)
        c.add(1_000, 6_005, 1_010) // rtt 10, offset exactly 5000
        val best = c.offsetMs
        assertTrue(kotlin.math.abs(best - 5_000) <= 5, "offset $best")
        assertEquals(10, c.rttMs)
    }

    @Test fun clockIgnoresNegativeRoundTrip() {
        val c = ClockSync()
        c.add(1_000, 2_000, 900)
        assertFalse(c.ready)
        assertEquals(0, c.offsetMs)
    }

    // Drift

    private fun hostState(playing: Boolean = true, pos: Long = 10_000, at: Long = 50_000, id: String = "a") =
        HostState(1, song(id), pos, playing, at)

    @Test fun expectedPositionMovesOnlyWhilePlaying() {
        assertEquals(12_500, Drift.expectedPosition(hostState(), 52_500))
        assertEquals(10_000, Drift.expectedPosition(hostState(playing = false), 52_500))
        assertEquals(10_000, Drift.expectedPosition(hostState(), 40_000)) // never goes backwards
    }

    @Test fun smallDriftIsLeftAlone() {
        val step = Drift.plan(hostState(), 52_000, LocalPlay("a", 12_250, true, false))
        assertTrue(step.idle, step.toString())
    }

    @Test fun driftBeyondThresholdSeeksToTheHostPosition() {
        val ahead = Drift.plan(hostState(), 52_000, LocalPlay("a", 12_400, true, false))
        assertEquals(12_000L, ahead.seekTo)
        val behind = Drift.plan(hostState(), 52_000, LocalPlay("a", 11_000, true, false))
        assertEquals(12_000L, behind.seekTo)
        val edge = Drift.plan(hostState(), 52_000, LocalPlay("a", 12_300, true, false))
        assertNull(edge.seekTo) // exactly 300 ms is within tolerance
    }

    @Test fun guestFollowsPlayAndPause() {
        val pause = Drift.plan(hostState(playing = false), 60_000, LocalPlay("a", 10_000, true, false))
        assertEquals(false, pause.setPlaying)
        assertNull(pause.seekTo)
        val play = Drift.plan(hostState(), 52_000, LocalPlay("a", 12_000, false, false))
        assertEquals(true, play.setPlaying)
    }

    @Test fun differentSongMeansLoadAndNothingElse() {
        val step = Drift.plan(hostState(id = "b"), 52_000, LocalPlay("a", 12_000, true, false))
        assertTrue(step.load)
        assertNull(step.seekTo)
        assertTrue(Drift.plan(hostState(), 52_000, LocalPlay(null, 0, false, false)).load)
    }

    @Test fun loadingSongIsLeftAlone() {
        assertTrue(Drift.plan(hostState(), 52_000, LocalPlay("a", 0, false, true)).idle)
        assertTrue(Drift.plan(null, 52_000, LocalPlay("a", 0, false, false)).idle)
        assertTrue(Drift.plan(HostState(1, null, 0, false, 0), 52_000, LocalPlay("a", 0, true, false)).idle)
    }

    @Test fun seekIsKeptInsideTheSong() {
        val s = HostState(1, song("a", durMs = 20_000), 19_900, true, 50_000)
        assertEquals(20_000L, Drift.plan(s, 55_000, LocalPlay("a", 5_000, true, false)).seekTo)
    }

    // Song matching

    private fun ref(id: String, title: String, artist: String, sec: Long) = LocalSongRef(id, title, artist, sec)

    @Test fun matchesByIdFirst() {
        val lib = listOf(ref("x", "Other", "Someone", 1), ref("a", "Whatever", "Else", 999))
        assertEquals("a", SongMatch.find(song("a"), lib)?.uniqueId)
    }

    @Test fun matchesByTitleArtistAndDurationWithinThreeSeconds() {
        val target = song("host1", title = "Hello, World!", artist = "The Band", durMs = 200_000)
        val lib = listOf(
            ref("l1", "hello world", "the band", 203), // 3.0 s away: inside
            ref("l2", "Hello World", "THE BAND", 201), // closer
            ref("l3", "Hello World", "The Band", 210), // too long
            ref("l4", "Hello World", "Another", 200),
        )
        assertEquals("l2", SongMatch.find(target, lib)?.uniqueId)
        assertEquals("l1", SongMatch.find(target, lib.filter { it.uniqueId == "l1" })?.uniqueId)
        assertNull(SongMatch.find(target, lib.filter { it.uniqueId in setOf("l3", "l4") }))
        assertNull(SongMatch.find(target, listOf(ref("l5", "Hello World", "The Band", 204)))) // 4 s away
    }

    @Test fun songsWithoutDurationDoNotMatchByName() {
        assertNull(SongMatch.find(song("h", durMs = 0), listOf(ref("l", "Song h", "Artist", 0))))
        assertNull(SongMatch.find(song("h"), listOf(ref("l", "Song h", "Artist", 0))))
    }

    // Wire format

    @Test fun stateAndQueueRoundTrip() {
        val s = HostState(7, song("a"), 1234, true, 99_000)
        val back = NetCodec.stateOf(NetCodec.state(s))
        assertEquals(s, back)
        val none = HostState(8, null, 0, false, 5)
        assertEquals(none, NetCodec.stateOf(NetCodec.state(none)))
        val q = listOf(song("a"), song("b"))
        assertEquals(q, NetCodec.queueOf(NetCodec.queue(q)))
        assertTrue(NetCodec.queueOf(NetCodec.queue((1..80).map { song("s$it") })).size <= NetCodec.MAX_QUEUE)
    }

    @Test fun codecRejectsGarbage() {
        val bad = JsonObject(emptyMap())
        var failed = false
        try { NetCodec.stateOf(bad) } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
