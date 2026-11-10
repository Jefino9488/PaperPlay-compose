package io.max.paperplay.core.telegram

/** TDLib's JSON interface (td_create_client_id, td_send, td_receive, td_execute). */
interface TdNative {
    fun createClientId(): Int
    fun send(clientId: Int, request: String)

    /** Blocks up to [timeoutSeconds]; null when nothing arrived. Call from one thread only. */
    fun receive(timeoutSeconds: Double): String?

    /** Synchronous requests only (setLogVerbosityLevel, getOption version, ...). */
    fun execute(request: String): String?
}

/** libtdjson through JNA. Desktop: set `jna.library.path` to the folder holding tdjson.dll first. */
expect object TdJsonNative : TdNative
