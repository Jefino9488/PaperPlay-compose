package io.max.paperplay.core.sync.netplay

/**
 * The join link shown as a QR code: `paperplay://netplay?h=ip:port,ip:port&p=123456&n=Name`.
 * [hosts] are `host:port` texts, [pin] is the 6 digit code, [name] the host's name.
 */
data class NetplayLink(val hosts: List<String>, val pin: String, val name: String) {
    /** The link text. Keeps at most [maxHosts] addresses and [maxName] characters of the name so it fits a small QR code. */
    fun encode(maxHosts: Int = 3, maxName: Int = 20): String = buildString {
        append(PREFIX)
        append("h=").append(hosts.take(maxHosts).joinToString(",") { pct(it) })
        append("&p=").append(pct(pin))
        append("&n=").append(pct(name.take(maxName)))
    }

    /** The addresses as host and port, bad ones left out. */
    fun endpoints(): List<Pair<String, Int>> = hosts.mapNotNull { splitEndpoint(it) }

    companion object {
        private const val PREFIX = "paperplay://netplay?"

        /** Reads a link; null when it is not one or has no usable address or PIN. */
        fun parse(text: String): NetplayLink? {
            val t = text.trim()
            if (!t.startsWith(PREFIX, ignoreCase = true)) return null
            var hosts = emptyList<String>()
            var pin = ""
            var name = ""
            for (pair in t.substring(PREFIX.length).split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val v = unpct(pair.substring(eq + 1))
                when (pair.substring(0, eq)) {
                    "h" -> hosts = v.split(',').map { it.trim() }.filter { splitEndpoint(it) != null }
                    "p" -> pin = v.trim()
                    "n" -> name = v.take(64)
                }
            }
            if (hosts.isEmpty() || !isPin(pin)) return null
            return NetplayLink(hosts, pin, name)
        }

        fun isPin(text: String) = text.length == 6 && text.all { it in '0'..'9' }

        /** `host` or `host:port` to a host and port (default [netplayPort]); null when it is not an address. */
        fun splitEndpoint(text: String): Pair<String, Int>? {
            val t = text.trim()
            if (t.isEmpty() || t.any { it.isWhitespace() || it == '/' || it == ',' || it == '&' }) return null
            val colon = t.lastIndexOf(':')
            val host = if (colon == -1) t else t.substring(0, colon)
            val port = if (colon == -1) netplayPort else t.substring(colon + 1).toIntOrNull()
            if (host.isEmpty() || port == null || port !in 1..65535) return null
            return host to port
        }

        private fun pct(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xff
                val plain = (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) ||
                    c == '.'.code || c == ':'.code || c == '-'.code || c == '_'.code
                if (plain) append(c.toChar()) else append('%').append(HEX[c shr 4]).append(HEX[c and 15])
            }
        }

        private fun unpct(s: String): String {
            val out = ArrayList<Byte>(s.length)
            val bytes = s.encodeToByteArray()
            var i = 0
            while (i < bytes.size) {
                val c = bytes[i].toInt()
                if (c == '%'.code && i + 2 < bytes.size) {
                    val hi = hexVal(bytes[i + 1].toInt())
                    val lo = hexVal(bytes[i + 2].toInt())
                    if (hi >= 0 && lo >= 0) { out.add(((hi shl 4) or lo).toByte()); i += 3; continue }
                }
                out.add(if (c == '+'.code) ' '.code.toByte() else bytes[i])
                i++
            }
            return out.toByteArray().decodeToString()
        }

        private fun hexVal(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }

        private const val HEX = "0123456789ABCDEF"
    }
}
