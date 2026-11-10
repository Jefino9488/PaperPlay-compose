package io.max.paperplay.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

actual object Fs {
    actual fun mkdirs(path: String) { File(path).mkdirs() }
    actual fun exists(path: String) = File(path).exists()
    actual fun write(path: String, bytes: ByteArray) { File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes) }
    actual fun deleteRecursively(path: String) { File(path).deleteRecursively() }
    actual fun rename(from: String, to: String) {
        require(File(from).renameTo(File(to))) { "Could not move a file into place." }
    }
}

private const val KDF_ITERATIONS = 200_000

actual fun openSealed(sealed: String, password: String): ByteArray {
    val s = try {
        Json.parseToJsonElement(sealed).jsonObject
    } catch (_: Exception) {
        throw IllegalArgumentException("Sealed data is damaged.")
    }
    val b64 = Base64.getDecoder()
    val salt: ByteArray; val nonce: ByteArray; val mac: ByteArray; val data: ByteArray
    try {
        fun f(k: String) = b64.decode(s.getValue(k).jsonPrimitive.content)
        salt = f("salt"); nonce = f("nonce"); mac = f("mac"); data = f("data")
    } catch (_: Exception) {
        throw IllegalArgumentException("Sealed data is damaged.")
    }
    val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(password.toCharArray(), salt, KDF_ITERATIONS, 256)).encoded
    return try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(mac.size * 8, nonce))
            doFinal(data + mac) // the JDK wants the tag after the cipher text
        }
    } catch (_: AEADBadTagException) {
        throw WrongPassword()
    } catch (_: GeneralSecurityException) {
        throw IllegalArgumentException("Sealed data is damaged.")
    }
}

/** One pass over a zip read from [open] (closed afterwards). */
fun streamBackupSource(open: () -> InputStream) = BackupSource { visit ->
    ZipInputStream(open().buffered()).use { zip ->
        while (true) {
            val e = zip.nextEntry ?: break
            visit(object : BackupEntry {
                override val name = e.name
                override val isDirectory = e.isDirectory
                override fun readBytes(limit: Long): ByteArray? {
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) return out.toByteArray()
                        if (out.size() + n > limit) return null
                        out.write(buf, 0, n)
                    }
                }
                override fun copyTo(path: String): Long {
                    val file = File(path).apply { parentFile?.mkdirs() }
                    var total = 0L
                    file.outputStream().buffered(256 * 1024).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                    }
                    return total
                }
            })
        }
    }
}

fun fileBackupSource(path: String) = streamBackupSource { File(path).inputStream() }

/**
 * Secrets as one file per key under [dir], each run through [protect]/[unprotect].
 * Platform stores supply the protection.
 */
open class FileSecretStore(
    private val dir: File,
    private val protect: (ByteArray) -> ByteArray,
    private val unprotect: (ByteArray) -> ByteArray,
) : SecretStore {
    private fun file(key: String) = File(dir, "$key.sec")
    override fun put(key: String, value: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(protect(value))
        java.nio.file.Files.move(tmp.toPath(), file(key).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    override fun get(key: String) = file(key).takeIf { it.exists() }?.let { unprotect(it.readBytes()) }
    override fun remove(key: String) { file(key).delete() }
}

private val SESSION_DATA_KEY = "\"data\":\"".encodeToByteArray()

/** Reads up to and including the `"data":"` marker; returns what came before it (the small salt/nonce/mac part). */
private fun readSealedHead(input: InputStream): String {
    val head = java.io.ByteArrayOutputStream()
    var matched = 0
    while (true) {
        val b = input.read()
        if (b < 0 || head.size() > 4096) throw IllegalArgumentException("Sealed data is damaged.")
        head.write(b)
        matched = if (b.toByte() == SESSION_DATA_KEY[matched]) matched + 1 else if (b.toByte() == SESSION_DATA_KEY[0]) 1 else 0
        if (matched == SESSION_DATA_KEY.size) break
    }
    return head.toByteArray().decodeToString().removeSuffix("\"data\":\"") + "\"data\":\"\"}"
}

/** The base64 text of the data value as a byte stream, ending at its closing quote. */
private class QuotedBase64(private val input: InputStream) : InputStream() {
    private var done = false
    override fun read(): Int {
        if (done) return -1
        val b = input.read()
        if (b < 0 || b == '"'.code) { done = true; return -1 }
        return b
    }
    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (done) return -1
        val n = input.read(buf, off, len)
        if (n < 0) { done = true; return -1 }
        for (i in off until off + n) {
            if (buf[i] == '"'.code.toByte()) { done = true; return if (i == off) -1 else i - off }
        }
        return n
    }
}

actual fun openSealedFile(sealedPath: String, password: String, outPath: String) {
    File(sealedPath).inputStream().buffered(256 * 1024).use { input ->
        val head = try {
            Json.parseToJsonElement(readSealedHead(input)).jsonObject
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Sealed data is damaged.")
        } catch (_: Exception) {
            throw IllegalArgumentException("Sealed data is damaged.")
        }
        val b64 = Base64.getDecoder()
        val salt: ByteArray; val nonce: ByteArray
        try {
            salt = b64.decode(head.getValue("salt").jsonPrimitive.content)
            nonce = b64.decode(head.getValue("nonce").jsonPrimitive.content)
        } catch (_: Exception) {
            throw IllegalArgumentException("Sealed data is damaged.")
        }
        if (nonce.size != 12) throw IllegalArgumentException("Sealed data is damaged.")
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, KDF_ITERATIONS, 256)).encoded
        // GCM encrypts with AES in counter mode, the counter starting at nonce || 2 (block 1 is the tag mask).
        val counter = nonce + byteArrayOf(0, 0, 0, 2)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(counter))
        }
        File(outPath).apply { parentFile?.mkdirs() }.outputStream().buffered(256 * 1024).use { out ->
            b64.wrap(QuotedBase64(input)).use { data ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = data.read(buf)
                    if (n < 0) break
                    out.write(cipher.update(buf, 0, n))
                }
            }
        }
    }
}

actual fun extractSessionZip(zipPath: String, targetDir: String): ByteArray? {
    var key: ByteArray? = null
    val root = File(targetDir).apply { mkdirs() }.canonicalFile
    ZipInputStream(File(zipPath).inputStream().buffered(256 * 1024)).use { zip ->
        while (true) {
            val e = zip.nextEntry ?: break
            if (e.isDirectory) continue
            if (e.name == "__key") { key = zip.readBytes(); continue }
            val file = File(root, e.name).canonicalFile
            if (!file.path.startsWith(root.path + File.separator)) continue // never write outside the folder
            file.parentFile?.mkdirs()
            file.outputStream().buffered(256 * 1024).use { zip.copyTo(it) }
        }
    }
    return key
}
