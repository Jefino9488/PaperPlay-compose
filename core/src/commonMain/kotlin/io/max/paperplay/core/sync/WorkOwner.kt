package io.max.paperplay.core.sync

/** A linked device as far as work splitting cares. */
interface DeviceInfo {
    val id: String

    /** A PC: it takes the heavy background work when connected. */
    val desktop: Boolean
}

/** Decides which device does the heavy background work (song info, covers, lyrics, mood scans) on a song. */
interface WorkOwner {
    /** Whether a PC is connected, which then does the heavy background work for a phone. */
    val pcConnected: Boolean

    /** Whether this device is the one to do the work on the song with [uniqueId]. */
    fun ownsSong(uniqueId: String): Boolean
}

/**
 * Linked devices split the library between them by a hash of the song, so each does its share and none
 * repeats another's; results reach the others through the sync. Alone, a device owns everything.
 * Heavy work belongs to PCs when one is connected: a phone then only plays and receives results. With no
 * PC connected, or between PCs, devices split the work equally.
 *
 * [deviceId] is null until the id has been read; until then the device owns everything.
 */
class RingWorkOwner(
    private val isDesktop: Boolean,
    private val deviceId: () -> String?,
    private val devices: () -> List<DeviceInfo>,
) : WorkOwner {
    override val pcConnected: Boolean get() = devices().any { it.desktop }

    /** This device and every connected one, in a fixed order, for splitting the work. */
    private fun ring(mine: String): List<String> {
        val peers = devices()
        val ids = if (isDesktop || peers.any { it.desktop }) {
            (if (isDesktop) listOf(mine) else emptyList()) + peers.filter { it.desktop }.map { it.id }
        } else {
            listOf(mine) + peers.map { it.id }
        }
        return ids.sorted()
    }

    override fun ownsSong(uniqueId: String): Boolean {
        val mine = deviceId() ?: return true
        // A phone with a PC connected leaves the work to it. It takes it back if the PC leaves.
        if (!isDesktop && pcConnected) return false
        val ring = ring(mine)
        // Alone in the ring until the other device has connected: the first check is early.
        return ring.size < 2 || ringHash(uniqueId) % ring.size == ring.indexOf(mine).toLong()
    }
}

/** FNV-1a over UTF-16 code units, 31 bits; the same number on every device (Dart `stableHash`). */
internal fun ringHash(text: String): Long {
    var h = 0x811c9dc5L
    for (c in text) h = ((h xor c.code.toLong()) * 0x01000193L) and 0x7fffffffL
    return h
}
