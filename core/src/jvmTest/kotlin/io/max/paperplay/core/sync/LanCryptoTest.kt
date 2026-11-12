package io.max.paperplay.core.sync

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class LanCryptoTest {
    private fun hexOf(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hexStr(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test fun x25519MatchesRfc7748() {
        val a = hexOf("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = hexOf("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hexStr(X25519.publicKey(a)))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", hexStr(X25519.publicKey(b)))
        val expected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(expected, hexStr(X25519.shared(a, X25519.publicKey(b))))
        assertEquals(expected, hexStr(X25519.shared(b, X25519.publicKey(a))))
    }

    /** Runs a server handshake on a thread; returns the pair of channels and the raw bytes the client wrote. */
    private class Link(val client: SecureChannel, val server: SecureChannel, val clientT: ByteArray, val serverT: ByteArray,
                       val wire: ByteArrayOutputStream, val rawOut: DataOutputStream, val sockets: List<Socket>)

    private fun link(): Link {
        val ss = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        var srv: Handshake? = null
        var sock: Socket? = null
        val th = thread {
            sock = ss.accept()
            srv = serverHandshake(DataInputStream(sock!!.getInputStream()), DataOutputStream(sock!!.getOutputStream()))
        }
        val c = Socket(java.net.InetAddress.getLoopbackAddress(), ss.localPort)
        val wire = ByteArrayOutputStream()
        val tee = object : OutputStream() {
            override fun write(b: Int) { wire.write(b); c.getOutputStream().write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { wire.write(b, off, len); c.getOutputStream().write(b, off, len) }
            override fun flush() { c.getOutputStream().flush() }
        }
        val out = DataOutputStream(tee)
        val ch = clientHandshake(DataInputStream(c.getInputStream()), out)
        th.join(5000)
        ss.close()
        return Link(ch.channel, srv!!.channel, ch.transcript, srv!!.transcript, wire, out, listOf(c, sock!!))
    }

    @Test fun handshakeRoundTripBothDirections() {
        val l = link()
        assertContentEquals(l.clientT, l.serverT)
        l.client.send("hello".encodeToByteArray())
        assertEquals("hello", l.server.receive().decodeToString())
        l.server.send("back".encodeToByteArray())
        l.server.send("again".encodeToByteArray())
        assertEquals("back", l.client.receive().decodeToString())
        assertEquals("again", l.client.receive().decodeToString())
        l.sockets.forEach { it.close() }
    }

    @Test fun freshKeysPerConnection() {
        val a = link(); val b = link()
        assertNotEquals(hexStr(a.clientT), hexStr(b.clientT))
        a.sockets.forEach { it.close() }; b.sockets.forEach { it.close() }
    }

    @Test fun replayedFrameIsRejected() {
        val l = link()
        val before = l.wire.size()
        l.client.send("pay".encodeToByteArray())
        val frame = l.wire.toByteArray().copyOfRange(before, l.wire.size())
        assertEquals("pay", l.server.receive().decodeToString())
        l.rawOut.write(frame); l.rawOut.flush() // the same bytes again
        assertFailsWith<Exception> { l.server.receive() }
        l.sockets.forEach { it.close() }
    }
}
