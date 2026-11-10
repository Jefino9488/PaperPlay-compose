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
