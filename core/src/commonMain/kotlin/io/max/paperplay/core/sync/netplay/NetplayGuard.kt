package io.max.paperplay.core.sync.netplay

import io.max.paperplay.core.sync.CodeAttempt

/**
 * The PIN of one network play session, and the lockout after wrong PINs. Pure (no sockets), so it is testable.
 * Unlike the pairing PIN it is NOT single use and does not expire: it lasts until [close] or [regenerate].
 * Wrong tries count per source address and overall; after [maxFails] from one address, or [maxFailsAll] in total, joining
 * is refused for [lockMs]. Thread safe.
 */
class NetplayGuard(
    private val clock: () -> Long,
    private val randomDigits: (Int) -> String,
    private val maxFails: Int = 5,
    private val lockMs: Long = 5 * 60_000L,
    private val maxFailsAll: Int = 15,
) {
    private val lock = Any()
    private var pin: String? = null
    private val fails = HashMap<String, Int>()
    private val lockedUntil = HashMap<String, Long>()
    private var failsAll = 0
    private var lockedAllUntil = 0L

    /** Starts the session PIN. */
    fun open(): String = synchronized(lock) { randomDigits(6).also { pin = it } }

    /** A new PIN; the old one stops working. Lockouts are cleared. */
    fun regenerate(): String = synchronized(lock) {
        fails.clear(); lockedUntil.clear(); failsAll = 0; lockedAllUntil = 0
        randomDigits(6).also { pin = it }
    }

    /** Ends the session: no PIN works any more. */
    fun close() = synchronized(lock) { pin = null }

    fun currentPin(): String? = synchronized(lock) { pin }

    fun isLocked(source: String): Boolean = synchronized(lock) {
        val now = clock()
        lockedAllUntil > now || (lockedUntil[source] ?: 0L) > now
    }

    /** Counts one try from [source]; [matches] is asked about the PIN. The PIN stays valid after a success. */
    fun attempt(source: String, matches: (String) -> Boolean): CodeAttempt = synchronized(lock) {
        val now = clock()
        if (lockedAllUntil > now || (lockedUntil[source] ?: 0L) > now) return CodeAttempt.Locked
        val p = pin ?: return CodeAttempt.Wrong
        if (matches(p)) {
            fails.remove(source)
            return CodeAttempt.Ok
        }
        val n = (fails[source] ?: 0) + 1
        fails[source] = n
        if (n >= maxFails) { lockedUntil[source] = now + lockMs; fails.remove(source) }
        failsAll += 1
        if (failsAll >= maxFailsAll) { lockedAllUntil = now + lockMs; failsAll = 0 }
        return CodeAttempt.Wrong
    }
}

/**
 * What a guest may send to the host. This is the whole list: library sync, snapshots, settings, tokens and anything else
 * are not message types of a guest link, so nothing outside it is ever acted on.
 */
object GuestPolicy {
    /** Message types a guest never needs permission for. */
    private val always = setOf("ping", "bye")

    /** True when the host may act on a message of [type] from a guest. */
    fun allows(type: String?, guestsCanControl: Boolean, shareAudio: Boolean): Boolean {
        val t = type ?: return false
        if (t in always) return true
        return when (t) {
            "ctl" -> guestsCanControl
            "range" -> shareAudio
            else -> false
        }
    }

    /** The control kinds a guest may ask for. */
    val controlKinds = setOf("play", "pause", "next", "prev", "seek")
}
