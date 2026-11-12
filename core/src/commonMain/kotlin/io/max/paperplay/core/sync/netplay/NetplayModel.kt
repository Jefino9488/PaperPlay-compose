package io.max.paperplay.core.sync.netplay

import io.max.paperplay.player.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock

/** TCP port a network play host listens on (the own-device sync uses another one). */
const val netplayPort = 47873

/** UDP port hosts announce themselves on. */
const val netplayDiscoveryPort = 47874

const val NETPLAY_MAX_GUESTS = 8

/** A song as the session describes it. [id] is the host's unique id; matching on other devices uses the other fields. */
data class NetSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** Bytes of the file; 0 when unknown (then it cannot be streamed). */
    val size: Long,
)

/** What the host is playing, stamped with the host clock so a guest can work out where the host is now. */
data class HostState(val seq: Long, val song: NetSong?, val positionMs: Long, val playing: Boolean, val hostTimeMs: Long)

/** The host's player as the session reads it. [queue] starts at the current song. */
data class HostNow(val song: NetSong?, val queue: List<NetSong>, val positionMs: Long, val playing: Boolean)

/** The guest's player as the session reads it. [songId] is the host id of the song loaded from the session, or null. */
data class LocalPlay(val songId: String?, val positionMs: Long, val playing: Boolean, val loading: Boolean)

/** Where a guest gets the audio of the current song. */
enum class SongSource { Local, Streamed, Missing }

enum class NetFailure {
    /** Nothing answered at the address. */
    Unreachable,
    /** Something answered but it is not a network play host, or the link broke. */
    Protocol,
    WrongPin,
    /** Too many wrong PINs; wait and try again. */
    LockedOut,
    Full,
    /** The host removed this device, or the session is closed. */
    Refused,
    /** The host did not prove it knows the PIN. */
    BadProof,
    /** This device is already in a session. */
    Busy,
}

enum class NetControl { Play, Pause, Next, Previous, Seek }

data class NetJoin(val ok: Boolean, val failure: NetFailure? = null)

data class NetGuest(val id: String, val name: String, val address: String, val joinedAt: Long)

/** A host heard on the network. */
data class NetHost(val name: String, val host: String, val port: Int, val lastSeen: Long)

/**
 * The session this device is in. [pin], [addresses] and [link] are set on the host only.
 * [source] is set on a guest: where the current song comes from.
 */
data class NetSession(
    val host: Boolean,
    val hostName: String,
    val pin: String? = null,
    val addresses: List<String> = emptyList(),
    /** Host: the TCP port the session listens on. */
    val port: Int = 0,
    val link: String? = null,
    val guests: List<NetGuest> = emptyList(),
    val guestsCanControl: Boolean = false,
    val shareAudio: Boolean = true,
    val now: NetSong? = null,
    val playing: Boolean = false,
    val source: SongSource? = null,
    /** Guest: estimated host clock minus this clock, and the round trip, both in ms. */
    val offsetMs: Long = 0,
    val rttMs: Long = 0,
)

/** Audio of the host's songs over the session link, for songs the guest does not own. */
interface NetAudio {
    fun open(song: NetSong): StreamSource
}

/** The device's player as network play needs it. Methods may be called from any thread. */
interface NetplayPlayer {
    /** Host: the player now. */
    fun hostNow(): HostNow
    /** Guest: the player now. */
    fun localPlay(): LocalPlay
    /** Host: the bytes of a library song, or null when it is not known. */
    fun audioFor(songId: String): StreamSource?
    /**
     * Guest: replaces the queue with [queue] and starts [index]. Songs found in the own library play from it; the others
     * stream through [audio] (null when the host does not share). Returns where the song at [index] comes from. When it is
     * [SongSource.Missing] the player is paused.
     */
    suspend fun load(queue: List<NetSong>, index: Int, audio: NetAudio?): SongSource
    fun play()
    fun pause()
    fun seek(positionMs: Long)
    fun next()
    fun previous()
}

class NetplayEnv(
    val player: NetplayPlayer,
    val scope: CoroutineScope,
    /** This device's name shown to the others. */
    val deviceName: () -> String,
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
)

/**
 * Network play: one device hosts a listening session, others on the same network join with a PIN (typed, or in the QR
 * link) and the songs play in sync. Guests are not paired devices: a guest link carries play state, the queue and, when
 * the host shares audio, audio ranges of the songs in the host's queue. It never carries library data, settings, tokens
 * or other secrets, and a guest cannot ask for anything else. It is separate from [io.max.paperplay.core.sync.LanSync]
 * (own port, own PIN) and uses the same encrypted transport (X25519 and AES-256-GCM).
 */
interface Netplay {
    /** The session this device is in, or null. */
    val session: StateFlow<NetSession?>
    /** Hosts heard while [startDiscovery] is on. */
    val discovered: StateFlow<List<NetHost>>
    /** Why the last session ended (for example kicked or ended by the host); cleared with [clearNotice]. */
    val notice: StateFlow<String?>

    /** Starts hosting. Null when this device is already in a session or no port could be opened. The PIN lasts as long as the session. */
    suspend fun startHost(guestsCanControl: Boolean = false, shareAudio: Boolean = true): NetSession?
    suspend fun setGuestsCanControl(on: Boolean)
    suspend fun setShareAudio(on: Boolean)
    /** New PIN; guests already in stay, anyone removed earlier can try again with the new PIN. */
    suspend fun regeneratePin()
    suspend fun kick(guestId: String)
    suspend fun endSession()

    suspend fun join(host: String, port: Int, pin: String): NetJoin
    /** Tries each address of the link in turn. */
    suspend fun join(link: NetplayLink): NetJoin
    suspend fun leave()
    /** Guest with control permission: asks the host to apply [kind]. False when not allowed or not in a session. */
    suspend fun control(kind: NetControl, positionMs: Long = 0): Boolean

    fun startDiscovery()
    fun stopDiscovery()
    fun clearNotice()
}

expect fun createNetplay(env: NetplayEnv): Netplay
