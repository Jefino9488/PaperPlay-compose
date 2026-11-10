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
