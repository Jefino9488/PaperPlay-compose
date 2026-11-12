package io.max.paperplay.core.sync

/** Result of one code attempt. */
enum class CodeAttempt { Ok, Wrong, Locked }

/**
 * The short lived PIN, the password, and the lockout after wrong codes. Pure (no sockets), so it is testable.
 * [randomDigits] gives n random decimal digits as a string; the real one uses a secure random.
 * Thread safe: all methods lock.
 */
class PairingGuard(
    private val clock: () -> Long,
    private val randomDigits: (Int) -> String,
    private val pinTtlMs: Long = 2 * 60_000L,
    private val maxFails: Int = 5,
    private val lockMs: Long = 5 * 60_000L,
    private val maxFailsAll: Int = 15,
) {
    private val lock = Any()
    private var pin: PairingPin? = null
    private var password: String? = null
    private val fails = HashMap<String, Int>()
    private val lockedUntil = HashMap<String, Long>()
    private var failsAll = 0
    private var lockedAllUntil = 0L

    fun newPin(): PairingPin = synchronized(lock) {
        PairingPin(randomDigits(6), clock() + pinTtlMs).also { pin = it }
    }

    fun clearPin() = synchronized(lock) { pin = null }

    /** The PIN if still valid. */
    fun currentPin(): PairingPin? = synchronized(lock) { pin?.takeIf { it.expiresAt > clock() } }

    fun setPassword(p: String?) = synchronized(lock) { password = p?.takeIf { it.isNotEmpty() } }

    fun hasPassword(): Boolean = synchronized(lock) { password != null }

    /** New code invalidates the old ones: drops the PIN and the password, clears lockouts. */
    fun reset() = synchronized(lock) {
        pin = null; password = null
        fails.clear(); lockedUntil.clear(); failsAll = 0; lockedAllUntil = 0
    }

    /** The codes that would be accepted now. */
    fun activeSecrets(): List<String> = synchronized(lock) {
        listOfNotNull(pin?.takeIf { it.expiresAt > clock() }?.code, password)
    }

    fun isLocked(source: String): Boolean = synchronized(lock) {
        val now = clock()
        lockedAllUntil > now || (lockedUntil[source] ?: 0L) > now
    }

    /**
     * Counts one try from [source] (an address). [matches] is asked about each active secret; the first that returns true
     * wins. A PIN is single use: it is dropped on success. Wrong tries count towards the lockout of [source] and overall.
     */
    fun attempt(source: String, matches: (String) -> Boolean): CodeAttempt = synchronized(lock) {
        val now = clock()
        if (lockedAllUntil > now || (lockedUntil[source] ?: 0L) > now) return CodeAttempt.Locked
        val p = pin?.takeIf { it.expiresAt > now }
        val hit = (if (p != null && matches(p.code)) 1 else 0) + (if (password.let { it != null && matches(it) }) 2 else 0)
        if (hit != 0) {
            if (hit and 1 != 0) pin = null
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
