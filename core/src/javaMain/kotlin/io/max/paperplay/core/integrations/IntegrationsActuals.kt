package io.max.paperplay.core.integrations

import java.security.MessageDigest
import java.security.SecureRandom

internal actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private val random = SecureRandom()

internal actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

actual fun defaultHttpFetcher(): HttpFetcher = JavaHttp()
