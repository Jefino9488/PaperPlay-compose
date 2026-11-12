package io.max.paperplay.core.sync.netplay

import io.max.paperplay.player.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Two network play instances in one process, talking over the loopback interface with the real encrypted transport. */
class NetplayLoopbackTest {
    private val data = ByteArray(1000) { (it * 7).toByte() }

    private fun song(id: String) = NetSong(id, "Song $id", "Artist", "Album", 200_000, 1000)

    private inner class FakePlayer(
        var song: NetSong?, var queue: List<NetSong>, var pos: Long, var playing: Boolean, val hasLocal: Boolean = true,
    ) : NetplayPlayer {
        val actions = CopyOnWriteArrayList<String>()
        @Volatile var loadedQueue: List<NetSong> = emptyList()
        @Volatile var audio: NetAudio? = null
        @Volatile var localId: String? = null

        override fun hostNow() = HostNow(song, queue, pos, playing)
        override fun localPlay() = LocalPlay(localId, pos, playing, false)
        override fun audioFor(songId: String): StreamSource? = if (songId == "s1") object : StreamSource {
            override val size: Long get() = data.size.toLong()
            override fun read(offset: Long, length: Int): ByteArray =
                data.copyOfRange(offset.toInt(), minOf(data.size, offset.toInt() + length))
        } else null

        override suspend fun load(queue: List<NetSong>, index: Int, audio: NetAudio?): SongSource {
            this.audio = audio
            loadedQueue = queue
            localId = queue[index].id
            return if (hasLocal) SongSource.Local else if (audio != null) SongSource.Streamed else SongSource.Missing
        }

        override fun play() { actions.add("play"); playing = true }
        override fun pause() { actions.add("pause"); playing = false }
        override fun seek(positionMs: Long) { actions.add("seek"); }
        override fun next() { actions.add("next") }
        override fun previous() { actions.add("prev") }
    }

    private fun create(p: FakePlayer, name: String) = createNetplay(NetplayEnv(p, CoroutineScope(Dispatchers.Default), { name }))

    private suspend fun waitFor(what: String, cond: () -> Boolean) {
        repeat(200) { if (cond()) return; delay(50) }
        throw AssertionError("timed out: $what")
    }

    private fun hostPlayer() = FakePlayer(song("s1"), listOf(song("s1"), song("s2")), 5_000, true)

    @Test fun guestJoinsWithPinAndFollowsTheHost() = runBlocking<Unit> {
        val hp = hostPlayer()
        val gp = FakePlayer(null, emptyList(), 0, false)
        val host = create(hp, "Host")
        val guest = create(gp, "Guest")
        try {
            val s = assertNotNull(host.startHost(false, true))
            val pin = assertNotNull(s.pin)
            assertEquals(6, pin.length)
            assertTrue(s.port > 0)
            assertNotNull(s.link)
            val j = guest.join("127.0.0.1", s.port, pin)
            assertTrue(j.ok, j.failure.toString())
            waitFor("guest loads the song") { gp.loadedQueue.isNotEmpty() }
            assertEquals(listOf("s1", "s2"), gp.loadedQueue.map { it.id })
            waitFor("guest source") { guest.session.value?.source == SongSource.Local }
            waitFor("guest starts playing") { gp.actions.contains("play") }
            waitFor("guest corrects drift") { gp.actions.contains("seek") }
            waitFor("host lists the guest") { host.session.value?.guests?.size == 1 }
            assertEquals("Song s1", guest.session.value?.now?.title)
            assertEquals("Host", guest.session.value?.hostName)
        } finally {
            guest.leave()
            host.endSession()
        }
    }

    @Test fun wrongPinIsRefusedAndRepeatedTriesLockOut() = runBlocking<Unit> {
        val host = create(hostPlayer(), "Host")
        val guest = create(FakePlayer(null, emptyList(), 0, false), "Guest")
        try {
            val s = assertNotNull(host.startHost())
            val pin = assertNotNull(s.pin)
            val bad = if (pin == "000000") "111111" else "000000"
            repeat(5) { assertEquals(NetFailure.WrongPin, guest.join("127.0.0.1", s.port, bad).failure) }
            assertEquals(NetFailure.LockedOut, guest.join("127.0.0.1", s.port, pin).failure)
            assertEquals(null, guest.session.value)
            assertEquals(0, host.session.value?.guests?.size)
        } finally {
            host.endSession()
        }
    }

    @Test fun kickedGuestIsOutUntilANewPin() = runBlocking<Unit> {
        val host = create(hostPlayer(), "Host")
        val guest = create(FakePlayer(null, emptyList(), 0, false), "Guest")
        try {
            val s = assertNotNull(host.startHost())
            val pin = assertNotNull(s.pin)
            assertTrue(guest.join("127.0.0.1", s.port, pin).ok)
            waitFor("host lists the guest") { host.session.value?.guests?.size == 1 }
            host.kick(host.session.value!!.guests.single().id)
            waitFor("guest is out") { guest.session.value == null }
            assertTrue(guest.notice.value?.contains("removed") == true, guest.notice.value)
            waitFor("host list empty") { host.session.value?.guests?.isEmpty() == true }
            assertEquals(NetFailure.Refused, guest.join("127.0.0.1", s.port, pin).failure)

            host.regeneratePin()
            val pin2 = assertNotNull(host.session.value?.pin)
            assertTrue(pin2 != pin)
            assertTrue(guest.join("127.0.0.1", s.port, pin2).ok)
            // the old PIN stopped working
            val other = create(FakePlayer(null, emptyList(), 0, false), "Other")
            assertEquals(NetFailure.WrongPin, other.join("127.0.0.1", s.port, pin).failure)
        } finally {
            guest.leave()
            host.endSession()
        }
    }

    @Test fun guestControlNeedsTheHostToggle() = runBlocking<Unit> {
        val hp = hostPlayer()
        val host = create(hp, "Host")
        val guest = create(FakePlayer(null, emptyList(), 0, false), "Guest")
        try {
            val s = assertNotNull(host.startHost(guestsCanControl = false))
            assertTrue(guest.join("127.0.0.1", s.port, assertNotNull(s.pin)).ok)
            waitFor("joined") { guest.session.value != null }
            assertFalse(guest.control(NetControl.Pause))
            host.setGuestsCanControl(true)
            waitFor("guest learns it may control") { guest.session.value?.guestsCanControl == true }
            assertTrue(guest.control(NetControl.Pause))
            waitFor("host applied pause") { hp.actions.contains("pause") }
            host.setGuestsCanControl(false)
            waitFor("guest learns it may not") { guest.session.value?.guestsCanControl == false }
            assertFalse(guest.control(NetControl.Next))
        } finally {
            guest.leave()
            host.endSession()
        }
    }

    @Test fun missingSongStreamsFromTheHostOnlyWhileSharing() = runBlocking<Unit> {
        val host = create(hostPlayer(), "Host")
        val gp = FakePlayer(null, emptyList(), 0, false, hasLocal = false)
        val guest = create(gp, "Guest")
        try {
            val s = assertNotNull(host.startHost(shareAudio = true))
            assertTrue(guest.join("127.0.0.1", s.port, assertNotNull(s.pin)).ok)
            waitFor("guest loads") { gp.audio != null }
            waitFor("guest source") { guest.session.value?.source == SongSource.Streamed }
            val src = assertNotNull(gp.audio).open(song("s1"))
            assertContentEquals(data.copyOfRange(10, 110), src.read(10, 100))
            assertContentEquals(data.copyOfRange(900, 1000), src.read(900, 500)) // stops at the end
            // a song that is not in the host's queue is never served
            assertFailsWith<IOException> { assertNotNull(gp.audio).open(NetSong("zzz", "x", "y", "", 1000, 100)).read(0, 10) }

            host.setShareAudio(false)
            waitFor("guest learns sharing is off") { guest.session.value?.shareAudio == false }
            assertFailsWith<IOException> { src.read(0, 10) }
        } finally {
            guest.leave()
            host.endSession()
        }
    }

    @Test fun missingSongWithoutSharingIsReportedMissing() = runBlocking<Unit> {
        val host = create(hostPlayer(), "Host")
        val gp = FakePlayer(null, emptyList(), 0, false, hasLocal = false)
        val guest = create(gp, "Guest")
        try {
            val s = assertNotNull(host.startHost(shareAudio = false))
            assertTrue(guest.join("127.0.0.1", s.port, assertNotNull(s.pin)).ok)
            waitFor("guest source") { guest.session.value?.source == SongSource.Missing }
            assertEquals(null, gp.audio)
        } finally {
            guest.leave()
            host.endSession()
        }
    }

    @Test fun endingTheSessionDisconnectsGuests() = runBlocking<Unit> {
        val host = create(hostPlayer(), "Host")
        val guest = create(FakePlayer(null, emptyList(), 0, false), "Guest")
        val s = assertNotNull(host.startHost())
        assertTrue(guest.join("127.0.0.1", s.port, assertNotNull(s.pin)).ok)
        waitFor("joined") { host.session.value?.guests?.size == 1 }
        host.endSession()
        waitFor("guest is out") { guest.session.value == null }
        assertTrue(guest.notice.value?.contains("ended") == true, guest.notice.value)
        assertEquals(null, host.session.value)
        // a new session can start right away
        assertNotNull(host.startHost())
        host.endSession()
    }
}
