package io.max.paperplay.core.sync

import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.app.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock

/** A device that is linked right now. */
data class LanDevice(
    override val id: String,
    val name: String,
    /** A PC (takes the heavy background work). */
    override val desktop: Boolean,
    val host: String,
    val port: Int,
    /** True when the device is in the paired list (it can link without a code). False when it linked with the token mode. */
    val paired: Boolean,
    val connectedAt: Long,
) : DeviceInfo

/** A device found by "Find devices automatically" or "Scan this network". Not linked yet. */
data class DiscoveredDevice(
    val id: String,
    val name: String,
    val desktop: Boolean,
    val host: String,
    val port: Int,
    val lastSeen: Long,
    /** Already paired: [LanSync.connect] works without a code. Otherwise use [LanSync.connectWithCode]. */
    val paired: Boolean,
)

/** A paired device: the long-term public key it was paired with. Shown in a "paired devices" list. */
data class PairedDevice(val id: String, val name: String, val publicKey: String, val pairedAt: Long)

/** A 6 digit code to show on this device. Valid until [expiresAt] (epoch ms) or its first use. */
data class PairingPin(val code: String, val expiresAt: Long)

/** Why a connection or sync failed. Show a short text per value. */
enum class LanFailure {
    /** Nothing answered at the address (or no saved address for the device). */
    Unreachable,
    /** Something answered but is not a PaperPlay device or the link broke. */
    Protocol,
    /** The code or password was wrong. */
    WrongCode,
    /** Too many wrong codes; wait and try again. */
    LockedOut,
    /** The other device only links with paired devices, or does not know this one. */
    NotPaired,
    /** The other device failed to prove it knows the code, key or token. Nothing was stored. */
    BadProof,
    /** This device is not listening, or has no such device. */
    NotReady,
}

/** Result of a connect. [device] is set when [ok]. */
data class ConnectResult(val ok: Boolean, val failure: LanFailure? = null, val device: LanDevice? = null)

/** What a sync changed on this device. */
data class SyncResult(val ok: Boolean, val failure: LanFailure? = null, val songsChanged: Int = 0, val playlistsChanged: Int = 0)

/** A message from a linked device that is not part of the sync (queue and now playing share, stubs for now). */
data class LanMessage(val deviceId: String, val type: String, val body: String)

/**
 * Local network sync and connect, no UI. One instance per app, from [createLanSync] (see `AppServices.enableLanSync`).
 * It is OFF until [start] is called.
 *
 * What the Connection page maps to:
 * - This device name: [deviceName] and [setDeviceName].
 * - Listening port: [port] (47872 unless changed with [setPort]); [listening] says whether it accepts links.
 * - Saved addresses list: [saved]; [addAddress] and [removeAddress].
 * - Find devices automatically: [discoveryOn] with [setDiscovery]; found devices appear in [discovered].
 * - Scan this network: [scanNetwork] (state in [scanning]).
 * - Encryption is always on: every link does an X25519 key exchange with fresh keys and uses AES-256-GCM. There is no switch.
 * - Connected devices list: [connected] with [disconnect], [syncNow], [unpair].
 * - This device's own addresses to read out to another device: [localAddresses].
 *
 * Pairing: a new device links with a code, once. Either the 6 digit PIN from [showPin] (short lived, single use), or the
 * password from [setPassword], or, only when [tokenMode] is on, by holding the same bot token (never sent; proved by
 * a keyed hash of the handshake). Both sides then store each other's public key and later links ([connect]) need no code.
 * While [onlyPaired] is on, NO new device can link (codes and the token are refused); paired devices still can.
 * Wrong codes are counted per address and overall; after five the address is locked out for five minutes.
 * Entering a code only pairs and links; the link carries song data only (favorites, plays, tags, pins, playlists) and never
 * tokens, the session or other secrets.
 */
interface LanSync {
    /** True while the server socket is open. */
    val listening: StateFlow<Boolean>
    /** The TCP port this device listens on and offers to others. */
    val port: StateFlow<Int>
    val deviceName: StateFlow<String>
    /** This device's own IPv4 addresses on the local network. */
    val localAddresses: StateFlow<List<String>>
    /** Linked devices now. */
    val connected: StateFlow<List<LanDevice>>
    /** The saved addresses, newest seen first. */
    val saved: StateFlow<List<KnownAddress>>
    /** Devices paired with this one, linked or not. */
    val paired: StateFlow<List<PairedDevice>>
    /** Devices heard from by discovery or found by a scan, newest first. */
    val discovered: StateFlow<List<DiscoveredDevice>>
    /** The PIN being shown, or null when none (or it expired). */
    val pin: StateFlow<PairingPin?>
    /** True when a password is set (the password itself is never exposed). */
    val passwordSet: StateFlow<Boolean>
    val onlyPaired: StateFlow<Boolean>
    val tokenMode: StateFlow<Boolean>
    val discoveryOn: StateFlow<Boolean>
    val scanning: StateFlow<Boolean>
    /** Short text for the last problem (for example "port in use"), or null. */
    val lastError: StateFlow<String?>
    /** Queue and now playing messages from linked devices (stubs). */
    val messages: SharedFlow<LanMessage>

    /** Opens the server socket and, if [discoveryOn], starts announcing. Safe to call twice. Saved as "on" for the next launch. */
    suspend fun start()
    /** Closes every link and the socket. */
    suspend fun stop()

    /** Shows a new 6 digit PIN, valid two minutes, replacing any shown before. */
    suspend fun showPin(): PairingPin
    suspend fun hidePin()
    /** Sets the password (at least 6 characters) or clears it with null. */
    suspend fun setPassword(password: String?)
    /** Invalidates the PIN and the password at once, and clears the lockouts. Paired devices stay paired. */
    suspend fun resetCode()

    /** Links to a new device with the PIN or password shown on it, and pairs both ways. */
    suspend fun connectWithCode(host: String, port: Int, code: String): ConnectResult
    /** Links to a device with the same bot token (needs [tokenMode] here and there). */
    suspend fun connectWithToken(host: String, port: Int): ConnectResult
    /** Links to a paired device, trying every saved address of it, newest first (then a discovered one). */
    suspend fun connect(deviceId: String): ConnectResult
    suspend fun disconnect(deviceId: String)

    /** Merges song data with one linked device, or with all when [deviceId] is null; both sides end equal. */
    suspend fun syncNow(deviceId: String? = null): SyncResult

    /** Forgets one paired device (and disconnects it). It needs a new code to link again. */
    suspend fun unpair(deviceId: String)
    suspend fun unpairAll()

    /** Adds an address like `192.168.1.5` or `192.168.1.5:47872`. False when it is not an address. */
    suspend fun addAddress(text: String, label: String? = null): Boolean
    /** Removes a saved address by [KnownAddress.key]. */
    suspend fun removeAddress(key: String)
    /** Tries every address of this network's /24 once on the port, asks who answers, and returns them. */
    suspend fun scanNetwork(): List<DiscoveredDevice>

    suspend fun setDeviceName(name: String)
    suspend fun setPort(port: Int)
    suspend fun setOnlyPaired(on: Boolean)
    suspend fun setTokenMode(on: Boolean)
    suspend fun setDiscovery(on: Boolean)

    /** Stub: sends this device's queue (song unique ids and the playing index) to a linked device. */
    suspend fun sendQueue(deviceId: String, uniqueIds: List<String>, index: Int): Boolean
    /** Stub: sends the playing song and position to a linked device. */
    suspend fun sendNowPlaying(deviceId: String, uniqueId: String?, positionMs: Long, playing: Boolean, title: String? = null, artist: String? = null): Boolean

    /** Names of the paired devices this one is dialling right now, for a short "Connecting to ..." notice. */
    val connecting: StateFlow<List<String>> get() = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    /** What each linked device says it is playing, by device id. */
    val remoteNow: StateFlow<Map<String, RemoteNow>> get() = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    /** Asks a linked device to "play", "pause", "next" or "previous". False when it is not linked. */
    suspend fun sendControl(deviceId: String, action: String): Boolean = false
}

/** The song a linked device is playing. */
data class RemoteNow(val uniqueId: String?, val title: String?, val artist: String?, val playing: Boolean)

/** What [createLanSync] needs. */
class LanEnv(
    val library: LibraryRepository,
    /** Holds this device's private identity key and the password. Never sent anywhere. */
    val secrets: SecretStore,
    val scope: CoroutineScope,
    val isDesktop: Boolean,
    /** Name used until the user sets one. */
    val defaultName: String,
    /** The bot token, only to prove it is the same one on both devices. Never sent. */
    val botToken: () -> String?,
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
)

/** Builds the sockets, crypto and discovery (Android and desktop share one implementation). Does not start it. */
expect fun createLanSync(env: LanEnv): LanSync
