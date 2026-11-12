package io.max.paperplay.core.backup

/** File access, the only platform part of a restore besides zip and crypto. */
expect object Fs {
    fun mkdirs(path: String)
    fun exists(path: String): Boolean
    fun write(path: String, bytes: ByteArray)
    fun deleteRecursively(path: String)
    /** Renames [from] to [to]; [to] must not exist. */
    fun rename(from: String, to: String)
}

/**
 * Opens what Flutter's `sealBytes`/`sealCredentials` made: JSON {salt, nonce, mac, data} (base64),
 * key = PBKDF2-HMAC-SHA256(password, salt, 200000 iterations, 256 bits), cipher = AES-256-GCM.
 * Throws [WrongPassword] if the password does not open it, [IllegalArgumentException] if malformed.
 */
expect fun openSealed(sealed: String, password: String): ByteArray

/**
 * Like [openSealed], but for data too big for memory: reads the sealed JSON at [sealedPath] as a stream and writes the
 * plain bytes to [outPath]. The data part is opened with the cipher's counter stream, so the GCM tag is not checked
 * here; the caller has already opened the (small) credentials with the same password, and a wrong result is not a zip.
 */
expect fun openSealedFile(sealedPath: String, password: String, outPath: String)

/**
 * Unpacks the account session zip at [zipPath] into [targetDir] and returns the bytes of its `__key` entry, or null
 * if there is none. Unsafe entry names are never written.
 */
expect fun extractSessionZip(zipPath: String, targetDir: String): ByteArray?

/**
 * Like Flutter's `sealBytes`: JSON {salt, nonce, mac, data} (base64), key = PBKDF2-HMAC-SHA256(password, salt,
 * 200000 iterations, 256 bits), cipher = AES-256-GCM with a 12-byte nonce and 16-byte tag. Slow by design.
 */
expect fun sealBytes(plain: ByteArray, password: String): String

/**
 * [sealBytes] for data too big for memory: streams the file at [plainPath] into the sealed JSON file at [outPath].
 * The JSON is written as salt, nonce, data, mac, which [openSealed] and [openSealedFile] both read.
 */
expect fun sealFile(plainPath: String, password: String, outPath: String)

/**
 * Zips every file under [dir] (relative names) plus one entry [extraName] holding [extra], into [outPath]. Returns false,
 * leaving no file, if [dir] is missing or its files add up to more than [maxBytes].
 */
expect fun zipFolder(dir: String, extraName: String, extra: ByteArray, outPath: String, maxBytes: Long): Boolean

/** Creates the zip at [outPath], lets [block] fill it, and closes it. */
expect fun writeZip(outPath: String, block: (BackupSink) -> Unit)

/** Where a backup's entries go. */
interface BackupSink {
    fun add(name: String, bytes: ByteArray)

    /** Adds the file at [path] as [name]; false if there is no such file. */
    fun addFile(name: String, path: String): Boolean
}
