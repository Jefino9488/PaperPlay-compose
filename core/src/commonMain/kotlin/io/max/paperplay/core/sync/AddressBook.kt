package io.max.paperplay.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A saved address of a device. */
data class KnownAddress(
    val host: String,
    val port: Int = lanPort,
    /** The device's name once known, or what the owner typed. */
    val name: String? = null,
    /** The device that last answered here; null for an address added by hand and never reached. */
    val deviceId: String? = null,
    /** Epoch ms of the last time this address worked or was announced. 0 when never. */
    val lastSeen: Long = 0,
    /** How the last try went; null when never tried. */
    val lastResult: AddressState? = null,
    /** Epoch ms the entry was added; breaks ties in ordering. */
    val addedAt: Long = 0,
) {
    val key: String get() = "$host:$port"

    fun toJson() = JsonObject(
        mapOf(
            "host" to JsonPrimitive(host), "port" to JsonPrimitive(port), "name" to name.orNull(), "deviceId" to deviceId.orNull(),
            "lastSeen" to JsonPrimitive(lastSeen), "lastResult" to lastResult?.name.orNull(), "addedAt" to JsonPrimitive(addedAt),
        ),
    )

    companion object {
        fun fromJson(j: JsonObject) = KnownAddress(
            host = j.reqStr("host"),
            port = j.lng("port")?.toInt() ?: lanPort,
            name = j.str("name") ?: j.str("label"),
            deviceId = j.str("deviceId"),
            lastSeen = j.lng("lastSeen") ?: 0,
            lastResult = j.str("lastResult")?.let { n -> AddressState.values().firstOrNull { it.name == n } },
            addedAt = j.lng("addedAt") ?: 0,
        )
    }
}

/**
 * The persisted list of device addresses, so a new IP or network does not lose a device. [read] and [write] are the pref
 * `lan_addresses` (`library.pref` and `library.setPref`). Not thread safe by itself: callers use one thread at a time or lock.
 */
class AddressBook(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val clock: () -> Long,
    private val maxEntries: Int = 60,
) {
    private val state = MutableStateFlow(load())
    val entries: StateFlow<List<KnownAddress>> get() = state

    private fun load(): List<KnownAddress> {
        val raw = read() ?: return emptyList()
        return runCatching {
            (wireJson.parseToJsonElement(raw) as JsonArray).map { KnownAddress.fromJson(it as JsonObject) }
        }.getOrDefault(emptyList()).sortedForTrying()
    }

    private fun set(list: List<KnownAddress>) {
        val sorted = list.sortedForTrying().let { if (it.size > maxEntries) it.take(maxEntries) else it }
        state.value = sorted
        write(JsonArray(sorted.map { it.toJson() }).toString())
    }

    /** The addresses to try for [deviceId], newest first. */
    fun forDevice(deviceId: String): List<KnownAddress> = state.value.filter { it.deviceId == deviceId }

    /** Adds an address by hand (kept if already there). False when [text] is not an address. */
    fun add(text: String, label: String? = null): Boolean {
        val p = parseAddress(text, label) ?: return false
        if (state.value.any { it.key == p.key }) return true
        set(state.value + KnownAddress(p.host, p.port, p.label, null, 0, null, clock()))
        return true
    }

    fun remove(key: String) = set(state.value.filter { it.key != key })

    /** Records that [deviceId] answers at host:port now (from a connect, a discovery announce or the peer's own list). */
    fun learn(host: String, port: Int, deviceId: String, name: String?, result: AddressState = AddressState.Connected) {
        val now = clock()
        val key = "$host:$port"
        val old = state.value.firstOrNull { it.key == key }
        val entry = (old ?: KnownAddress(host, port, addedAt = now)).copy(
            deviceId = deviceId, name = name ?: old?.name, lastSeen = now, lastResult = result,
        )
        set(state.value.filter { it.key != key } + entry)
    }

    /** Records a failed or successful try of an address without changing when it was last seen, unless it worked. */
    fun record(key: String, result: AddressState) {
        val old = state.value.firstOrNull { it.key == key } ?: return
        val now = clock()
        val upd = if (result == AddressState.Connected) old.copy(lastResult = result, lastSeen = now) else old.copy(lastResult = result)
        set(state.value.map { if (it.key == key) upd else it })
    }

    /** Removes every address of a device (when it is unpaired). */
    fun forget(deviceId: String) = set(state.value.filter { it.deviceId != deviceId })
}

/** Newest seen first; addresses never reached keep the order they were added. */
internal fun List<KnownAddress>.sortedForTrying(): List<KnownAddress> =
    sortedWith(compareByDescending<KnownAddress> { it.lastSeen }.thenByDescending { it.addedAt }.thenBy { it.key })
