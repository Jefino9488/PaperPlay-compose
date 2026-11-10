package io.max.paperplay.core.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** UDP port every device listens on for the announcements of the others. */
const val lanDiscoveryPort = 47871

/** Fixed, so an address saved on another device keeps working. */
const val lanPort = 47872

/** Why a connection attempt failed. */
enum class ConnectFailure { Unreachable, Refused }

/** How the last try at a saved address went. */
enum class AddressState { Connected, Trying, Unreachable, Refused }

/** An address to try for another device. */
data class SavedAddress(
    val host: String,
    val port: Int = lanPort,
    /** A name for it: the owner's, or the device's once it has connected. */
    val label: String? = null,
    /** The device last reached here, to avoid connecting to it twice. */
    val deviceId: String? = null,
) {
    val key: String get() = "$host:$port"

    fun toJson() = JsonObject(
        mapOf("host" to JsonPrimitive(host), "port" to JsonPrimitive(port), "label" to label.orNull(), "deviceId" to deviceId.orNull()),
    )

    companion object {
        fun fromJson(j: JsonObject) = SavedAddress(
            host = j.reqStr("host"),
            port = j.lng("port")?.toInt() ?: lanPort,
            label = j.str("label"),
            deviceId = j.str("deviceId"),
        )
    }
}

private val hostPattern = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")

/** Reads `192.168.1.5` or `192.168.1.5:47872`. Null if it isn't an address. */
fun parseAddress(text: String, label: String? = null): SavedAddress? {
    val t = text.trim()
    val colon = t.lastIndexOf(':')
    val host = if (colon == -1) t else t.substring(0, colon)
    val port = if (colon == -1) lanPort else t.substring(colon + 1).toIntOrNull()
    if (port == null || port < 1 || port > 65535) return null
    if (!hostPattern.matches(host)) return null
    return SavedAddress(host, port, label?.trim()?.ifEmpty { null })
}

/**
 * Puts [incoming] first and removes anything it replaces, including any older address of the same
 * device: a restarted router gives a device a new address, and the old one is never worth showing as
 * a second device.
 */
fun rememberAddress(saved: List<SavedAddress>, incoming: SavedAddress): List<SavedAddress> =
    listOf(incoming) + saved.filter { it.key != incoming.key && (incoming.deviceId == null || it.deviceId != incoming.deviceId) }

/** One address per device, keeping the first (newest). Addresses saved by older versions can repeat a device. */
fun onePerDevice(saved: List<SavedAddress>): List<SavedAddress> {
    val seen = HashSet<String>()
    return saved.filter { it.deviceId == null || seen.add(it.deviceId) }
}
