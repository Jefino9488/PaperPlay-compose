package io.max.paperplay.core.telegram

import com.sun.jna.Library
import com.sun.jna.Native

@Suppress("FunctionName")
private interface TdJson : Library {
    fun td_create_client_id(): Int
    fun td_send(clientId: Int, request: String)
    fun td_receive(timeout: Double): String?
    fun td_execute(request: String): String?
}

actual object TdJsonNative : TdNative {
    // UTF-8 explicitly: JNA otherwise uses the platform charset (cp1252 on Windows) and mangles JSON.
    private val lib: TdJson by lazy {
        Native.load("tdjson", TdJson::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    }

    override fun createClientId() = lib.td_create_client_id()
    override fun send(clientId: Int, request: String) = lib.td_send(clientId, request)
    override fun receive(timeoutSeconds: Double) = lib.td_receive(timeoutSeconds)
    override fun execute(request: String) = lib.td_execute(request)
}
