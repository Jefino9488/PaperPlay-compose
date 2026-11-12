package io.max.paperplay.core.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * X25519 (RFC 7748) with BigInteger, so it works on Android from API 26 where the JDK XDH is missing.
 * Not constant time; the ephemeral keys are fresh per link and the identity key is only used for a keyed hash proof.
 */
object X25519 {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val random = SecureRandom()

    fun newPrivateKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun publicKey(priv: ByteArray): ByteArray = scalarMult(priv, ByteArray(32).also { it[0] = 9 })

    /** The shared secret; all zero for a bad (low order) public key, which callers reject. */
    fun shared(priv: ByteArray, peerPublic: ByteArray): ByteArray = scalarMult(priv, peerPublic)

    private fun decode(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun encode(v: BigInteger): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(32)
        var i = 0
        var j = be.size - 1
        while (j >= 0 && i < 32) { out[i] = be[j]; i++; j-- }
        return out
    }

    private fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        require(k.size == 32 && u.size == 32) { "bad key size" }
        val kc = k.copyOf()
        kc[0] = (kc[0].toInt() and 248).toByte()
        kc[31] = ((kc[31].toInt() and 127) or 64).toByte()
        val scalar = decode(kc)
        val uc = u.copyOf()
        uc[31] = (uc[31].toInt() and 127).toByte()
        val x1 = decode(uc).mod(P)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val bit = scalar.testBit(t)
            if (swap != bit) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = bit
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val s1 = da.add(cb).mod(P)
            val s2 = da.subtract(cb).mod(P)
            x3 = s1.multiply(s1).mod(P)
            z3 = x1.multiply(s2.multiply(s2).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }
        if (swap) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        return encode(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
    }
}

internal fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
    val m = Mac.getInstance("HmacSHA256")
    m.init(SecretKeySpec(key, "HmacSHA256"))
    for (p in parts) m.update(p)
    return m.doFinal()
}

internal fun sha256(vararg parts: ByteArray): ByteArray {
    val d = MessageDigest.getInstance("SHA-256")
    for (p in parts) d.update(p)
    return d.digest()
}

/** HKDF (RFC 5869) with SHA-256. */
internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
    val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
    val out = ByteArray(length)
    var prev = ByteArray(0)
    var pos = 0
    var i = 1
    val infoBytes = info.encodeToByteArray()
    while (pos < length) {
        prev = hmac(prk, prev, infoBytes, byteArrayOf(i.toByte()))
        val n = minOf(prev.size, length - pos)
        System.arraycopy(prev, 0, out, pos, n)
        pos += n
        i++
    }
    return out
}

internal fun constantEq(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

internal const val MAX_FRAME = 32 * 1024 * 1024

internal fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
    out.writeInt(bytes.size)
    out.write(bytes)
    out.flush()
}

internal fun readFrame(input: DataInputStream, max: Int = MAX_FRAME): ByteArray {
    val n = input.readInt()
    if (n < 0 || n > max) throw IOException("bad frame size")
    val b = ByteArray(n)
    input.readFully(b)
    return b
}

/**
 * AES-256-GCM frames. The nonce is a counter per direction that is never sent: the receiver expects exactly the next
 * number, so a replayed, dropped, reordered or injected frame fails to decrypt and the link closes.
 */
internal class SecureChannel(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
) {
    private var sendCounter = 0L
    private var recvCounter = 0L

    private fun nonce(n: Long): ByteArray {
        val b = ByteArray(12)
        for (i in 0 until 8) b[11 - i] = (n ushr (8 * i)).toByte()
        return b
    }

    private fun cipher(mode: Int, key: ByteArray, n: Long): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(n)))
        return c
    }

    @Synchronized
    fun send(plain: ByteArray) {
        val ct = cipher(Cipher.ENCRYPT_MODE, sendKey, sendCounter).doFinal(plain)
        sendCounter++
        writeFrame(output, ct)
    }

    /** Blocks for the next frame. Throws on a bad frame; the caller closes the link. */
    fun receive(): ByteArray {
        val ct = readFrame(input)
        val plain = cipher(Cipher.DECRYPT_MODE, recvKey, recvCounter).doFinal(ct)
        recvCounter++
        return plain
    }
}

/** The outcome of the key exchange: the encrypted channel and the transcript hash both sides share. */
internal class Handshake(val channel: SecureChannel, val transcript: ByteArray)

private val magic = "PPL1".encodeToByteArray()
private const val HELLO_SIZE = 4 + 32 + 32

private fun hello(pub: ByteArray): ByteArray {
    val nonce = ByteArray(32)
    SecureRandom().nextBytes(nonce)
    return magic + pub + nonce
}

private fun checkHello(b: ByteArray): ByteArray {
    if (b.size != HELLO_SIZE || !b.copyOfRange(0, 4).contentEquals(magic)) throw IOException("not a PaperPlay link")
    return b.copyOfRange(4, 36)
}

private fun finish(
    input: DataInputStream, output: DataOutputStream, priv: ByteArray, peerPub: ByteArray,
    clientHello: ByteArray, serverHello: ByteArray, client: Boolean,
): Handshake {
    val secret = X25519.shared(priv, peerPub)
    if (secret.all { it == 0.toByte() }) throw IOException("bad key")
    val transcript = sha256(clientHello, serverHello)
    val c2s = hkdf(secret, transcript, "paperplay lan c2s")
    val s2c = hkdf(secret, transcript, "paperplay lan s2c")
    val ch = if (client) SecureChannel(input, output, c2s, s2c) else SecureChannel(input, output, s2c, c2s)
    return Handshake(ch, transcript)
}

/** Fresh keys per connection: both sides send an ephemeral X25519 key and a random nonce; keys come from HKDF over the secret. */
internal fun clientHandshake(input: DataInputStream, output: DataOutputStream): Handshake {
    val priv = X25519.newPrivateKey()
    val ch = hello(X25519.publicKey(priv))
    writeFrame(output, ch)
    val sh = readFrame(input, 256)
    val peer = checkHello(sh)
    return finish(input, output, priv, peer, ch, sh, true)
}

internal fun serverHandshake(input: DataInputStream, output: DataOutputStream): Handshake {
    val ch = readFrame(input, 256)
    val peer = checkHello(ch)
    val priv = X25519.newPrivateKey()
    val sh = hello(X25519.publicKey(priv))
    writeFrame(output, sh)
    return finish(input, output, priv, peer, ch, sh, false)
}

/** Proof that the sender knows [secret], bound to this handshake and to the sender's identity bytes [bind]. */
internal fun proof(secret: ByteArray, label: String, role: String, transcript: ByteArray, bind: ByteArray): ByteArray {
    val key = hkdf(secret, transcript, "paperplay lan proof $label")
    return hmac(key, role.encodeToByteArray(), transcript, bind)
}
