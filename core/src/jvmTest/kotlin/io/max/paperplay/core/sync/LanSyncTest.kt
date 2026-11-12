package io.max.paperplay.core.sync

import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.app.SecretStore
import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanSyncTest {
    private class Secrets : SecretStore {
        private val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }

    private var now = 1_000_000L

    private fun song(chat: Long, msg: Long, uid: String, title: String = "t$msg") = Song(
        chat, msg, uid, "r$msg", title, "artist", "", 0, 100, "audio/mpeg", "$title.mp3", msg,
        null, null, null, null, null, null, false, false, false, 0, null, 0, null, false, 0, 0, 0, false, 0, 0, 0,
        null, null, null, null,
    )

    private fun library(vararg songs: Song): LibraryRepository {
        val db = LibraryDatabase(libraryDriver())
        songs.forEach { db.enrichQueries.replaceSong(it) }
        return LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private class Node(val lib: LibraryRepository, val sync: LanSync, val port: Int)

    private suspend fun node(name: String, token: String = "tok", vararg songs: Song): Node {
        val lib = library(*songs)
        val port = freePort()
        val s = createLanSync(LanEnv(lib, Secrets(), CoroutineScope(Dispatchers.Default), true, name, { token }))
        s.setDiscovery(false)
        s.setPort(port)
        s.start()
        assertTrue(s.listening.value)
        return Node(lib, s, port)
    }

    private suspend fun waitFor(what: String, cond: () -> Boolean) {
        repeat(60) { if (cond()) return; delay(50) }
        throw AssertionError("timed out: $what")
    }

    private fun wrongCode(pin: String) = if (pin == "000000") "111111" else "000000"

    @Test fun pairWithPinThenReconnectWithoutCodeAndSync() = runBlocking {
        val s1 = song(1, 1, "u1"); val s2 = song(1, 2, "u2")
        val a = node("A", "tok", s1, s2)
        val b = node("B", "tok", s1, s2)
        val c = node("C", "tok")
        try {
            val pin = b.sync.showPin().code
            assertEquals(LanFailure.WrongCode, a.sync.connectWithCode("127.0.0.1", b.port, wrongCode(pin)).failure)
            val ok = a.sync.connectWithCode("127.0.0.1", b.port, pin)
            assertTrue(ok.ok)
            val bId = ok.device!!.id
            waitFor("b sees a") { b.sync.connected.value.size == 1 }
            assertEquals(1, a.sync.paired.value.size)
            assertEquals(1, b.sync.paired.value.size)
            assertEquals(bId, a.sync.saved.value.first().deviceId)

            // the PIN was single use
            assertEquals(LanFailure.WrongCode, c.sync.connectWithCode("127.0.0.1", b.port, pin).failure)

            // reconnect with the stored identity, no code
            a.sync.disconnect(bId)
            waitFor("b lost a") { b.sync.connected.value.isEmpty() }
            waitFor("a lost b") { a.sync.connected.value.isEmpty() }
            assertTrue(a.sync.connect(bId).ok)

            // sync: a favorite and plays on A reach B
            val u1 = a.lib.repo.db.songsQueries.allSongs().executeAsList().first { it.unique_id == "u1" }
            a.lib.replaceSongs(listOf(u1.copy(favorite = true, favorite_at = 5_000_000, plays = 4)))
            val r = a.sync.syncNow()
            assertTrue(r.ok)
            val onB = b.lib.repo.db.songsQueries.allSongs().executeAsList().first { it.unique_id == "u1" }
            assertTrue(onB.favorite)
            assertEquals(4L, onB.plays)

            // unpaired: needs a code again
            a.sync.unpair(bId)
            waitFor("a lost b after unpair") { a.sync.connected.value.isEmpty() }
            assertEquals(LanFailure.NotPaired, a.sync.connect(bId).failure)
        } finally {
            a.sync.stop(); b.sync.stop(); c.sync.stop()
        }
    }

    @Test fun lockoutAfterRepeatedWrongCodes() = runBlocking {
        val a = node("A"); val b = node("B")
        try {
            val pin = b.sync.showPin().code
            repeat(5) { assertEquals(LanFailure.WrongCode, a.sync.connectWithCode("127.0.0.1", b.port, wrongCode(pin)).failure) }
            assertEquals(LanFailure.LockedOut, a.sync.connectWithCode("127.0.0.1", b.port, pin).failure)
            assertEquals(0, b.sync.paired.value.size)
        } finally {
            a.sync.stop(); b.sync.stop()
        }
    }

    @Test fun resetCodeAndOnlyPairedRefuseNewDevices() = runBlocking {
        val a = node("A"); val b = node("B")
        try {
            val pin = b.sync.showPin().code
            b.sync.resetCode()
            assertEquals(LanFailure.WrongCode, a.sync.connectWithCode("127.0.0.1", b.port, pin).failure)
            val pin2 = b.sync.showPin().code
            b.sync.setOnlyPaired(true)
            assertEquals(LanFailure.NotPaired, a.sync.connectWithCode("127.0.0.1", b.port, pin2).failure)
            b.sync.setOnlyPaired(false)
            assertTrue(a.sync.connectWithCode("127.0.0.1", b.port, pin2).ok)
        } finally {
            a.sync.stop(); b.sync.stop()
        }
    }

    @Test fun tokenModeNeedsTheSameToken() = runBlocking {
        val a = node("A", "same"); val b = node("B", "same"); val c = node("C", "other")
        try {
            assertEquals(LanFailure.NotPaired, a.sync.connectWithToken("127.0.0.1", b.port).failure) // mode off on B
            b.sync.setTokenMode(true)
            val ok = a.sync.connectWithToken("127.0.0.1", b.port)
            assertTrue(ok.ok)
            assertFalse(ok.device!!.paired)
            assertEquals(LanFailure.BadProof, c.sync.connectWithToken("127.0.0.1", b.port).failure)
        } finally {
            a.sync.stop(); b.sync.stop(); c.sync.stop()
        }
    }

    @Test fun nothingListeningIsUnreachable() = runBlocking {
        val a = node("A")
        try {
            assertEquals(LanFailure.Unreachable, a.sync.connectWithCode("127.0.0.1", freePort(), "123456").failure)
        } finally {
            a.sync.stop()
        }
    }

    @Test fun snapshotApplyMergesSongsAndPlaylistOrder() {
        val src = library(song(1, 1, "u1"), song(1, 2, "u2"), song(1, 3, "u3"))
        val dst = library(song(7, 1, "u1"), song(7, 2, "u2"), song(7, 3, "u3"))
        val u2 = src.repo.db.songsQueries.allSongs().executeAsList().first { it.unique_id == "u2" }
        src.replaceSongs(
            listOf(u2.copy(favorite = true, favorite_at = 9_000_000, plays = 2, skips = 1, user_edited = true, title = "Mine", tags_at = 9_000_000)),
        )
        val id = src.createPlaylist("Mix")
        val rows = src.repo.db.songsQueries.allSongs().executeAsList().associateBy { it.unique_id }
        src.addToPlaylist(id, rows.getValue("u1"))
        src.addToPlaylist(id, rows.getValue("u3"))
        src.repo.setPlaylistOrder(id, listOf(rows.getValue("u3"), rows.getValue("u1")))

        val wire = SnapshotCodec.encode(SnapshotStore(src).build()).toString()
        val decoded = SnapshotCodec.decode(wireJson.parseToJsonElement(wire) as JsonObject)
        val res = SnapshotStore(dst).apply(decoded)
        assertTrue(res.songsChanged >= 1)

        val d2 = dst.repo.db.songsQueries.allSongs().executeAsList().first { it.unique_id == "u2" }
        assertTrue(d2.favorite)
        assertEquals(2L, d2.plays)
        assertEquals("Mine", d2.title)
        assertTrue(d2.user_edited)
        val pl = dst.repo.db.lanQueries.allPlaylistsWithUuid().executeAsList().single()
        assertEquals("Mix", pl.name)
        val order = dst.repo.db.playlistsQueries.playlistSongs(pl.id).executeAsList().map { it.unique_id }
        assertEquals(listOf("u3", "u1"), order)
        // applying the same snapshot again changes nothing
        assertEquals(0, SnapshotStore(dst).apply(decoded).songsChanged)
    }
}
