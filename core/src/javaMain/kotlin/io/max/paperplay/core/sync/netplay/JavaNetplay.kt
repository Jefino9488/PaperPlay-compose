package io.max.paperplay.core.sync.netplay

import io.max.paperplay.core.library.newUuid
import io.max.paperplay.core.sync.CodeAttempt
import io.max.paperplay.core.sync.clientHandshake
import io.max.paperplay.core.sync.constantEq
import io.max.paperplay.core.sync.flag
import io.max.paperplay.core.sync.lng
import io.max.paperplay.core.sync.proof
import io.max.paperplay.core.sync.reqLng
import io.max.paperplay.core.sync.reqStr
import io.max.paperplay.core.sync.SecureChannel
import io.max.paperplay.core.sync.serverHandshake
import io.max.paperplay.core.sync.str
import io.max.paperplay.core.sync.arr
import io.max.paperplay.core.sync.wireJson
import io.max.paperplay.player.StreamSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

actual fun createNetplay(env: NetplayEnv): Netplay = JavaNetplay(env)

/** Thrown while admitting a guest or joining; the reason goes to the other side. */
private class NpFail(val why: NetFailure) : Exception(why.name)

private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
private fun b64d(s: String): ByteArray = Base64.getDecoder().decode(s)
private fun failureOf(name: String?): NetFailure = NetFailure.values().firstOrNull { it.name == name } ?: NetFailure.Protocol
private fun bytes(o: JsonObject): ByteArray = o.toString().encodeToByteArray()

private fun parseObj(b: ByteArray): JsonObject =
    wireJson.parseToJsonElement(b.decodeToString()) as? JsonObject ?: throw IOException("bad message")

private fun randomDigits(n: Int): String {
    val r = SecureRandom()
    return buildString { repeat(n) { append(r.nextInt(10)) } }
}

private fun localIPv4(): List<String> = try {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct().sorted()
} catch (e: Exception) {
    emptyList()
}

private fun broadcastAddresses(): List<InetAddress> = try {
    val list = ArrayList<InetAddress>()
    for (i in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
        if (!i.isUp || i.isLoopback) continue
        for (a in i.interfaceAddresses) a.broadcast?.let { list += it }
    }
    list.add(InetAddress.getByName("255.255.255.255"))
    list.distinct()
} catch (e: Exception) {
    emptyList()
}

/** A guest as the host sees it: one link with an outgoing queue, written by one coroutine so frames never interleave badly. */
private class Guest(val id: String, val name: String, val address: String, val socket: Socket, val ch: SecureChannel, val joinedAt: Long) {
    val out = Channel<JsonObject>(Channel.UNLIMITED)
    fun post(o: JsonObject) { out.trySend(o) }
    fun hardClose() { out.close(); runCatching { socket.close() } }
}

internal class JavaNetplay(private val env: NetplayEnv) : Netplay {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val myId = newUuid()
    private val player get() = env.player

    private val _session = MutableStateFlow<NetSession?>(null)
    private val _discovered = MutableStateFlow(emptyList<NetHost>())
    private val _notice = MutableStateFlow<String?>(null)

    override val session: StateFlow<NetSession?> get() = _session
    override val discovered: StateFlow<List<NetHost>> get() = _discovered
    override val notice: StateFlow<String?> get() = _notice

    private var hostRun: HostRun? = null
    private var guestRun: GuestRun? = null
    private var joining = false
    private var discoveryJob: Job? = null

    private inner class HostRun(val server: ServerSocket) {
        val guard = NetplayGuard(env.clock, ::randomDigits)
        val guests = ConcurrentHashMap<String, Guest>()
        val kicked: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val pending = AtomicInteger(0)
        val wake = Channel<Unit>(Channel.CONFLATED)
        val jobs = CopyOnWriteArrayList<Job>()
        val audioGate = Semaphore(2)
        @Volatile var guestsCanControl = false
        @Volatile var shareAudio = true
        @Volatile var closed = false
        @Volatile var queueIds: List<String> = emptyList()
        @Volatile var queueMsg: JsonObject? = null
        @Volatile var seq = 0L
        @Volatile var last: HostState? = null
        @Volatile var lastSentAt = 0L
        @Volatile var addrs: List<String> = emptyList()
        @Volatile var announce: DatagramSocket? = null
    }

    private inner class GuestRun(val socket: Socket, val ch: SecureChannel) {
        val clock = ClockSync()
        val waiting = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
        val rid = AtomicLong(0)
        val wake = Channel<Unit>(Channel.CONFLATED)
        val jobs = CopyOnWriteArrayList<Job>()
        @Volatile var hostName = ""
        @Volatile var canControl = false
        @Volatile var share = false
        @Volatile var state: HostState? = null
        @Volatile var queue: List<NetSong> = emptyList()
        @Volatile var source: SongSource? = null
        @Volatile var guests: List<NetGuest> = emptyList()
        @Volatile var attemptedId: String? = null
        @Volatile var attemptedAt = 0L
        @Volatile var lastSeekAt = 0L
        @Volatile var closed = false
    }

    // Host

    override suspend fun startHost(guestsCanControl: Boolean, shareAudio: Boolean): NetSession? = withContext(Dispatchers.IO) {
        val ss = try {
            ServerSocket().also { it.reuseAddress = true; it.bind(InetSocketAddress(netplayPort)) }
        } catch (e: IOException) {
            try { ServerSocket(0) } catch (e2: IOException) { return@withContext null }
        }
        val hr = HostRun(ss)
        hr.guestsCanControl = guestsCanControl
        hr.shareAudio = shareAudio
        val claimed = synchronized(lock) {
            if (hostRun != null || guestRun != null || joining) false else { hostRun = hr; true }
        }
        if (!claimed) {
            runCatching { ss.close() }
            return@withContext null
        }
        hr.guard.open()
        hr.addrs = localIPv4().map { "$it:${ss.localPort}" }
        _notice.value = null
        hr.jobs.add(io.launch { acceptLoop(hr) })
        hr.jobs.add(io.launch { pollLoop(hr) })
        hr.jobs.add(io.launch { announceLoop(hr) })
        publishHost(hr)
        _session.value
    }

    private fun acceptLoop(hr: HostRun) {
        while (!hr.server.isClosed) {
            val s = try { hr.server.accept() } catch (e: IOException) { break }
            io.launch { serveGuest(hr, s) }
        }
    }

    private fun serveGuest(hr: HostRun, s: Socket) {
        if (hr.pending.incrementAndGet() > 8) { hr.pending.decrementAndGet(); runCatching { s.close() }; return }
        var admitted: Guest? = null
        try {
            s.soTimeout = 10_000
            s.tcpNoDelay = true
            val source = (s.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"
            val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            val hs = serverHandshake(inp, out)
            val first = parseObj(hs.channel.receive())
            if (first.str("t") != "np-hello") throw IOException("bad message")
            try {
                admitted = admit(hr, first, hs.transcript, hs.channel, source, s)
            } catch (f: NpFail) {
                hs.channel.send(bytes(buildJsonObject { put("t", "np-fail"); put("why", f.why.name) }))
            }
        } catch (e: Exception) {
            // A broken or hostile link: dropped without detail.
        } finally {
            hr.pending.decrementAndGet()
        }
        val g = admitted
        if (g == null) { runCatching { s.close() }; return }
        io.launch {
            try { for (o in g.out) g.ch.send(bytes(o)) } catch (e: Exception) { } finally { g.hardClose() }
        }
        hr.queueMsg?.let { g.post(it) }
        hr.last?.let { g.post(NetCodec.state(it)) }
        broadcast(hr, roster(hr))
        publishHost(hr)
        hr.wake.trySend(Unit)
        try {
            s.soTimeout = 20_000
            s.keepAlive = true
            while (true) handleGuest(hr, g, parseObj(g.ch.receive()))
        } catch (e: Exception) {
            // Link closed, timed out or damaged.
        } finally {
            hr.guests.remove(g.id, g)
            g.hardClose()
            if (!hr.closed) { broadcast(hr, roster(hr)); publishHost(hr) }
        }
    }

    /** Checks a guest's proof of the PIN. Throws [NpFail] to refuse. On success the guest is sent "np-ok" and registered. */
    private fun admit(hr: HostRun, a: JsonObject, t: ByteArray, ch: SecureChannel, source: String, s: Socket): Guest {
        val id = a.reqStr("id").take(64)
        val name = a.reqStr("name").trim().take(40).ifEmpty { "Guest" }
        val theirProof = b64d(a.reqStr("proof"))
        if (id.isEmpty() || id == myId) throw NpFail(NetFailure.Protocol)
        if (hr.closed || source in hr.kicked) throw NpFail(NetFailure.Refused)
        if (hr.guests.size >= NETPLAY_MAX_GUESTS && !hr.guests.containsKey(id)) throw NpFail(NetFailure.Full)
        var matched: String? = null
        val r = hr.guard.attempt(source) { pin ->
            val ok = constantEq(proof(pin.encodeToByteArray(), "netplay", "client", t, id.encodeToByteArray()), theirProof)
            if (ok) matched = pin
            ok
        }
        if (r == CodeAttempt.Locked) throw NpFail(NetFailure.LockedOut)
        if (r == CodeAttempt.Wrong) throw NpFail(NetFailure.WrongPin)
        val pin = matched ?: throw NpFail(NetFailure.Protocol)
        ch.send(
            bytes(
                buildJsonObject {
                    put("t", "np-ok"); put("id", myId); put("name", env.deviceName().take(64))
                    put("proof", b64(proof(pin.encodeToByteArray(), "netplay", "server", t, myId.encodeToByteArray())))
                    put("ctl", hr.guestsCanControl); put("share", hr.shareAudio)
                },
            ),
        )
        val g = Guest(id, name, source, s, ch, env.clock())
        hr.guests.put(id, g)?.hardClose()
        return g
    }

    /** One message from a guest. Anything the policy does not allow is dropped. */
    private fun handleGuest(hr: HostRun, g: Guest, m: JsonObject) {
        val type = m.str("t")
        if (type == "range" && !hr.shareAudio) {
            // Answer at once, so the guest's player does not wait for audio that will not come.
            m.lng("rid")?.let { rid -> g.post(buildJsonObject { put("t", "audio"); put("rid", rid); put("err", "denied") }) }
            return
        }
        if (!GuestPolicy.allows(type, hr.guestsCanControl, hr.shareAudio)) return
        when (type) {
            "ping" -> {
                val c = m.lng("c") ?: return
                g.post(buildJsonObject { put("t", "pong"); put("c", c); put("h", env.clock()) })
            }
            "ctl" -> {
                val k = m.str("k") ?: return
                if (k !in GuestPolicy.controlKinds) return
                when (k) {
                    "play" -> player.play()
                    "pause" -> player.pause()
                    "next" -> player.next()
                    "prev" -> player.previous()
                    "seek" -> player.seek((m.lng("pos") ?: return).coerceAtLeast(0))
                }
                hr.wake.trySend(Unit)
            }
            "range" -> io.launch { hr.audioGate.withPermit { serveRange(hr, g, m) } }
            "bye" -> throw IOException("bye")
        }
    }

    private fun serveRange(hr: HostRun, g: Guest, m: JsonObject) {
        val rid = m.lng("rid") ?: return
        fun refuse(why: String) { g.post(buildJsonObject { put("t", "audio"); put("rid", rid); put("err", why) }) }
        try {
            val id = m.reqStr("id")
            val off = m.reqLng("off")
            val len = m.reqLng("len").coerceIn(1L, 256L * 1024).toInt()
            // Only songs of the host's current queue window can be asked for.
            if (!hr.shareAudio || id !in hr.queueIds) { refuse("denied"); return }
            val src = player.audioFor(id)
            if (src == null) { refuse("unknown"); return }
            val size = src.size
            val data = if (off < 0 || off >= size) ByteArray(0) else src.read(off, minOf(len.toLong(), size - off).toInt())
            g.post(buildJsonObject { put("t", "audio"); put("rid", rid); put("off", off); put("data", b64(data)) })
        } catch (e: Exception) {
            refuse("failed")
        }
    }

    private fun broadcast(hr: HostRun, o: JsonObject) {
        for (g in hr.guests.values) g.post(o)
    }

    private fun roster(hr: HostRun): JsonObject = buildJsonObject {
        put("t", "roster"); put("host", env.deviceName().take(64)); put("ctl", hr.guestsCanControl); put("share", hr.shareAudio)
        put("guests", buildJsonArray { for (g in hr.guests.values) add(buildJsonObject { put("id", g.id); put("name", g.name) }) })
    }

    private fun publishHost(hr: HostRun) {
        synchronized(lock) {
            if (hr.closed || hostRun !== hr) return
            val pin = hr.guard.currentPin()
            val name = env.deviceName().take(64)
            _session.value = NetSession(
                host = true, hostName = name, pin = pin, addresses = hr.addrs, port = hr.server.localPort,
                link = pin?.let { NetplayLink(hr.addrs, it, name).encode() },
                guests = hr.guests.values.map { NetGuest(it.id, it.name, it.address, it.joinedAt) }.sortedBy { it.joinedAt },
                guestsCanControl = hr.guestsCanControl, shareAudio = hr.shareAudio,
                now = hr.last?.song, playing = hr.last?.playing ?: false,
            )
        }
    }

    private suspend fun pollLoop(hr: HostRun) {
        while (!hr.closed) {
            try { tick(hr) } catch (e: Exception) { }
            withTimeoutOrNull(400) { hr.wake.receive() }
        }
    }

    /** Reads the host player; sends the queue when it changed and the state when something other than time passing changed. */
    private fun tick(hr: HostRun) {
        val now = player.hostNow()
        val t = env.clock()
        val window = now.queue.take(NetCodec.MAX_QUEUE)
        val ids = window.map { it.id }
        if (ids != hr.queueIds) {
            val msg = NetCodec.queue(window)
            hr.queueIds = ids
            hr.queueMsg = msg
            broadcast(hr, msg)
        }
        val cur = HostState(hr.seq + 1, now.song, now.positionMs, now.playing, t)
        val last = hr.last
        val moved = last == null || last.song?.id != cur.song?.id || last.playing != cur.playing ||
            kotlin.math.abs(cur.positionMs - Drift.expectedPosition(last, t)) > 1_000
        if (moved || t - hr.lastSentAt >= 3_000) {
            hr.seq = cur.seq
            hr.last = cur
            hr.lastSentAt = t
            broadcast(hr, NetCodec.state(cur))
            publishHost(hr)
        }
    }

    private suspend fun announceLoop(hr: HostRun) {
        val sock = try { DatagramSocket().also { it.broadcast = true } } catch (e: Exception) { return }
        hr.announce = sock
        while (!hr.closed) {
            val addrs = localIPv4().map { "$it:${hr.server.localPort}" }
            if (addrs != hr.addrs) { hr.addrs = addrs; publishHost(hr) }
            val msg = bytes(
                buildJsonObject {
                    put("app", "paperplay-netplay"); put("v", 1); put("name", env.deviceName().take(64)); put("port", hr.server.localPort)
                },
            )
            for (a in broadcastAddresses()) runCatching { sock.send(DatagramPacket(msg, msg.size, a, netplayDiscoveryPort)) }
            delay(3_000)
        }
    }

    override suspend fun setGuestsCanControl(on: Boolean) {
        val hr = hostRun ?: return
        hr.guestsCanControl = on
        broadcast(hr, roster(hr))
        publishHost(hr)
    }

    override suspend fun setShareAudio(on: Boolean) {
        val hr = hostRun ?: return
        hr.shareAudio = on
        broadcast(hr, roster(hr))
        publishHost(hr)
    }

    override suspend fun regeneratePin() {
        val hr = hostRun ?: return
        hr.guard.regenerate()
        hr.kicked.clear()
        publishHost(hr)
    }

    override suspend fun kick(guestId: String) {
        val hr = hostRun ?: return
        val g = hr.guests.remove(guestId) ?: return
        hr.kicked.add(g.address)
        g.post(buildJsonObject { put("t", "end"); put("why", "kicked") })
        g.out.close()
        io.launch { delay(2_000); g.hardClose() }
        broadcast(hr, roster(hr))
        publishHost(hr)
    }

    override suspend fun endSession() {
        withContext(Dispatchers.IO) {
            val hr = hostRun ?: return@withContext
            synchronized(lock) {
                if (hr.closed) return@withContext
                hr.closed = true
                hostRun = null
                _session.value = null
            }
            hr.guard.close()
            hr.jobs.forEach { it.cancel() }
            runCatching { hr.server.close() }
            runCatching { hr.announce?.close() }
            val end = buildJsonObject { put("t", "end"); put("why", "ended") }
            val all = hr.guests.values.toList()
            hr.guests.clear()
            for (g in all) {
                g.post(end)
                g.out.close()
            }
            io.launch { delay(2_000); all.forEach { it.hardClose() } }
        }
    }

    // Guest

    override suspend fun join(host: String, port: Int, pin: String): NetJoin = withContext(Dispatchers.IO) {
        val busy = synchronized(lock) {
            if (hostRun != null || guestRun != null || joining) true else { joining = true; false }
        }
        if (busy) return@withContext NetJoin(false, NetFailure.Busy)
        try {
            dial(host, port, pin.trim())
        } finally {
            synchronized(lock) { joining = false }
        }
    }

    override suspend fun join(link: NetplayLink): NetJoin {
        var last = NetJoin(false, NetFailure.Unreachable)
        for ((h, p) in link.endpoints()) {
            val r = join(h, p, link.pin)
            if (r.ok) return r
            if (last.failure == NetFailure.Unreachable || r.failure != NetFailure.Unreachable) last = r
            if (r.failure == NetFailure.Busy || r.failure == NetFailure.WrongPin || r.failure == NetFailure.LockedOut) break
        }
        return last
    }

    private fun dial(host: String, port: Int, pin: String): NetJoin {
        if (!NetplayLink.isPin(pin)) return NetJoin(false, NetFailure.WrongPin)
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 3_000)
        } catch (e: Exception) {
            runCatching { s.close() }
            return NetJoin(false, NetFailure.Unreachable)
        }
        var gr: GuestRun? = null
        var failure = NetFailure.Protocol
        try {
            s.soTimeout = 10_000
            s.tcpNoDelay = true
            val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            val hs = clientHandshake(inp, out)
            val t = hs.transcript
            val secret = pin.encodeToByteArray()
            hs.channel.send(
                bytes(
                    buildJsonObject {
                        put("t", "np-hello"); put("id", myId); put("name", env.deviceName().take(40))
                        put("proof", b64(proof(secret, "netplay", "client", t, myId.encodeToByteArray())))
                    },
                ),
            )
            val reply = parseObj(hs.channel.receive())
            if (reply.str("t") != "np-ok") {
                failure = failureOf(reply.str("why"))
                throw NpFail(failure)
            }
            val hostId = reply.reqStr("id").take(64)
            failure = NetFailure.BadProof
            if (!constantEq(proof(secret, "netplay", "server", t, hostId.encodeToByteArray()), b64d(reply.reqStr("proof")))) throw NpFail(failure)
            s.soTimeout = 20_000
            val created = GuestRun(s, hs.channel)
            created.hostName = reply.str("name").orEmpty().take(64)
            created.canControl = reply.flag("ctl") ?: false
            created.share = reply.flag("share") ?: false
            gr = created
        } catch (e: NpFail) {
            return NetJoin(false, e.why)
        } catch (e: Exception) {
            return NetJoin(false, failure)
        } finally {
            if (gr == null) runCatching { s.close() }
        }
        val r = gr ?: return NetJoin(false, failure)
        synchronized(lock) { guestRun = r }
        publishGuest(r)
        r.jobs.add(io.launch { guestReadLoop(r) })
        r.jobs.add(io.launch { pingLoop(r) })
        r.jobs.add(io.launch { syncLoop(r) })
        return NetJoin(true)
    }

    private fun publishGuest(r: GuestRun) {
        synchronized(lock) {
            if (r.closed || guestRun !== r) return
            _session.value = NetSession(
                host = false, hostName = r.hostName, guests = r.guests, guestsCanControl = r.canControl, shareAudio = r.share,
                now = r.state?.song, playing = r.state?.playing ?: false, source = r.source,
                offsetMs = r.clock.offsetMs, rttMs = r.clock.rttMs,
            )
        }
    }

    private fun guestReadLoop(r: GuestRun) {
        var reason: String? = "The connection to the host was lost"
        try {
            while (true) {
                val m = parseObj(r.ch.receive())
                when (m.str("t")) {
                    "state" -> {
                        r.state = NetCodec.stateOf(m)
                        r.wake.trySend(Unit)
                        publishGuest(r)
                    }
                    "queue" -> r.queue = NetCodec.queueOf(m)
                    "roster" -> {
                        r.hostName = m.str("host").orEmpty().take(64)
                        r.canControl = m.flag("ctl") ?: false
                        r.share = m.flag("share") ?: false
                        r.guests = m.arr("guests").orEmpty().take(NETPLAY_MAX_GUESTS + 1).mapNotNull { e ->
                            val o = e as? JsonObject ?: return@mapNotNull null
                            NetGuest(o.str("id").orEmpty(), o.str("name").orEmpty().take(40), "", 0)
                        }
                        publishGuest(r)
                    }
                    "pong" -> {
                        val c = m.lng("c")
                        val h = m.lng("h")
                        if (c != null && h != null) {
                            r.clock.add(c, h, env.clock())
                            publishGuest(r)
                        }
                    }
                    "audio" -> m.lng("rid")?.let { r.waiting.remove(it)?.complete(m) }
                    "end" -> {
                        reason = if (m.str("why") == "kicked") "The host removed you from the session" else "The host ended the session"
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (r.closed) reason = null
        }
        endGuest(r, reason)
    }

    private suspend fun pingLoop(r: GuestRun) {
        var n = 0
        while (!r.closed) {
            try {
                r.ch.send(bytes(buildJsonObject { put("t", "ping"); put("c", env.clock()) }))
            } catch (e: Exception) {
                return
            }
            delay(if (n++ < 6) 250L else 2_000L)
        }
    }

    private suspend fun syncLoop(r: GuestRun) {
        while (!r.closed) {
            try {
                syncOnce(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Try again at the next tick.
            }
            withTimeoutOrNull(500) { r.wake.receive() }
        }
    }

    /** One step of following the host: load the song when it changed, otherwise correct drift and play state. */
    private suspend fun syncOnce(r: GuestRun) {
        val hs = r.state ?: return
        val song = hs.song ?: return
        val now = env.clock()
        val step = Drift.plan(hs, r.clock.hostNow(now), player.localPlay())
        if (step.load) {
            if (r.attemptedId == song.id && now - r.attemptedAt < 10_000) return
            r.attemptedId = song.id
            r.attemptedAt = now
            val queue = r.queue
            val at = queue.indexOfFirst { it.id == song.id }
            val audio = if (r.share) object : NetAudio {
                override fun open(song: NetSong): StreamSource = RemoteSource(r, song)
            } else null
            r.source = if (at >= 0) player.load(queue, at, audio) else player.load(listOf(song), 0, audio)
            publishGuest(r)
            return
        }
        if (r.source == SongSource.Missing) return
        step.seekTo?.let {
            if (now - r.lastSeekAt >= 1_500) { r.lastSeekAt = now; player.seek(it) }
        }
        step.setPlaying?.let { if (it) player.play() else player.pause() }
    }

    /** The audio of a host song, read over the session link in ranges. Blocks the reader's thread while a range is on its way. */
    private inner class RemoteSource(private val r: GuestRun, private val song: NetSong) : StreamSource {
        override val size: Long get() = song.size

        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0 || offset >= song.size || length <= 0) return ByteArray(0)
            val want = minOf(length.toLong(), song.size - offset).toInt()
            val buf = ByteArrayOutputStream(want)
            while (buf.size() < want) {
                val chunk = minOf(256 * 1024, want - buf.size())
                val id = r.rid.incrementAndGet()
                val waiter = CompletableDeferred<JsonObject>()
                r.waiting[id] = waiter
                try {
                    r.ch.send(
                        bytes(
                            buildJsonObject {
                                put("t", "range"); put("rid", id); put("id", song.id); put("off", offset + buf.size()); put("len", chunk)
                            },
                        ),
                    )
                    val reply = runBlocking { withTimeoutOrNull(20_000) { waiter.await() } } ?: throw IOException("audio timeout")
                    if (reply.str("err") != null) throw IOException("the host did not send the audio")
                    val data = b64d(reply.reqStr("data"))
                    if (data.isEmpty()) break
                    buf.write(data)
                } finally {
                    r.waiting.remove(id)
                }
            }
            return buf.toByteArray()
        }
    }

    private fun endGuest(r: GuestRun, reason: String?) {
        synchronized(lock) {
            if (r.closed) return
            r.closed = true
            if (guestRun === r) { guestRun = null; _session.value = null }
        }
        r.jobs.forEach { it.cancel() }
        runCatching { r.socket.close() }
        for (w in r.waiting.values) w.completeExceptionally(IOException("closed"))
        if (reason != null) _notice.value = reason
    }

    override suspend fun leave() {
        withContext(Dispatchers.IO) {
            val r = guestRun ?: return@withContext
            try { r.ch.send(bytes(buildJsonObject { put("t", "bye") })) } catch (e: Exception) { }
            endGuest(r, null)
        }
    }

    override suspend fun control(kind: NetControl, positionMs: Long): Boolean = withContext(Dispatchers.IO) {
        val r = guestRun ?: return@withContext false
        if (!r.canControl) return@withContext false
        val k = when (kind) {
            NetControl.Play -> "play"
            NetControl.Pause -> "pause"
            NetControl.Next -> "next"
            NetControl.Previous -> "prev"
            NetControl.Seek -> "seek"
        }
        try {
            r.ch.send(bytes(buildJsonObject { put("t", "ctl"); put("k", k); put("pos", positionMs) }))
            true
        } catch (e: Exception) {
            false
        }
    }

    // Discovery of hosts

    override fun startDiscovery() {
        synchronized(lock) {
            if (discoveryJob != null) return
            discoveryJob = io.launch { runDiscovery() }
        }
    }

    override fun stopDiscovery() {
        synchronized(lock) {
            discoveryJob?.cancel()
            discoveryJob = null
        }
        _discovered.value = emptyList()
    }

    override fun clearNotice() {
        _notice.value = null
    }

    private suspend fun runDiscovery() {
        val sock = try {
            DatagramSocket(null as java.net.SocketAddress?).also {
                it.reuseAddress = true; it.broadcast = true; it.bind(InetSocketAddress(netplayDiscoveryPort))
            }
        } catch (e: Exception) {
            synchronized(lock) { discoveryJob = null }
            return
        }
        val reader = io.launch {
            val buf = ByteArray(1024)
            while (!sock.isClosed) {
                val pk = DatagramPacket(buf, buf.size)
                try { sock.receive(pk) } catch (e: Exception) { break }
                runCatching { onAnnounce(pk) }
            }
        }
        try {
            while (true) {
                delay(3_000)
                val cutoff = env.clock() - 12_000
                _discovered.update { list -> list.filter { it.lastSeen >= cutoff } }
            }
        } finally {
            runCatching { sock.close() }
            reader.cancel()
        }
    }

    private fun onAnnounce(pk: DatagramPacket) {
        val o = parseObj(pk.data.copyOfRange(pk.offset, pk.offset + pk.length))
        if (o.str("app") != "paperplay-netplay") return
        val name = o.str("name")?.take(64) ?: return
        val port = o.lng("port")?.toInt()?.takeIf { it in 1..65535 } ?: return
        val host = pk.address?.hostAddress ?: return
        val h = NetHost(name, host, port, env.clock())
        _discovered.update { list -> (listOf(h) + list.filter { !(it.host == host && it.port == port) }).sortedBy { it.name } }
    }
}
