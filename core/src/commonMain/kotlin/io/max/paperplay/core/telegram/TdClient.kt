package io.max.paperplay.core.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** [code] 429 is flood control; 408 is a local timeout. */
class TdError(val code: Int, override val message: String) : Exception(message) {
    /** Seconds to wait, when this is a flood-control error. */
    val retryAfter: Int?
        get() = if (code == 429) RETRY_AFTER.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: 5 else null

    private companion object {
        val RETRY_AFTER = Regex("retry after (\\d+)")
    }
}

/**
 * Login and storage settings for one bot session. Not a data class and no toString: it holds
 * the bot token, API hash and database key, which must never reach a log.
 * An empty [botToken] makes the owner's own account session: TDLib's login steps are left to the caller.
 * [encryptionKey] is the app's own (keyOverride when restoring a backup, else the keystore key).
 */
class TdConfig(
    val apiId: Int,
    val apiHash: String,
    val botToken: String,
    val databaseDir: String,
    val filesDir: String,
    val encryptionKey: String,
    val languageCode: String = "en",
    val deviceModel: String = "Android",
    val systemVersion: String = "",
    val appVersion: String = "0.1.0",
    val useMessageDatabase: Boolean = true,
) {
    val isUser get() = botToken.isEmpty()
}

/** Updates the app reacts to; everything else is dropped. */
val DefaultTdUpdates = setOf(
    "updateAuthorizationState",
    "updateFile",
    "updateNewMessage",
    "updateMessageContent",
    "updateDeleteMessages",
    "updateMessageSendSucceeded",
    "updateMessageSendFailed",
    "updateForumTopicInfo",
)

private val JsonObject.type get() = this["@type"]?.jsonPrimitive?.contentOrNull

/**
 * Owns the one receive loop. td_receive is process-global and must run on a single thread, so
 * make one runtime per process and give it a dispatcher backed by a thread that may block.
 */
class TdRuntime(
    internal val native: TdNative,
    internal val scope: CoroutineScope,
    receiveDispatcher: CoroutineDispatcher,
    private val wanted: Set<String> = DefaultTdUpdates,
) {
    private val clients = MutableStateFlow<Map<Int, TdClient>>(emptyMap())

    init {
        native.execute("""{"@type":"setLogVerbosityLevel","new_verbosity_level":1}""")
        scope.launch(receiveDispatcher) {
            while (isActive) {
                val raw = native.receive(1.0) ?: continue
                val m = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
                val client = clients.value[m["@client_id"]?.jsonPrimitive?.intOrNull] ?: continue
                if ("@extra" in m || m.type in wanted) client.onMessage(m)
            }
        }
    }

    internal fun open(client: TdClient): Int {
        val id = native.createClientId()
        clients.update { it + (id to client) }
        return id
    }

    internal fun close(id: Int) = clients.update { it - id }
}

/**
 * Request/response and update stream for one bot session. Never logs request contents.
 * Call [start], then [ready]; the client answers TDLib's parameter and bot-token steps itself.
 */
class TdClient(private val runtime: TdRuntime, private val config: TdConfig) {
    private val _auth = MutableStateFlow("")
    private val _authError = MutableStateFlow<String?>(null)
    private val _authLink = MutableStateFlow<String?>(null)
    private val _updates = MutableSharedFlow<JsonObject>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val pending = MutableStateFlow<Map<Long, CompletableDeferred<JsonObject>>>(emptyMap())
    private val nextExtra = MutableStateFlow(1L)
    private var closed = CompletableDeferred<Unit>()

    @Volatile private var clientId = -1
    @Volatile private var stopping = false

    /** Current `authorizationState*` type; empty until TDLib reports one. */
    val auth: StateFlow<String> = _auth

    /** Last failure from setup or login. */
    val authError: StateFlow<String?> = _authError

    /** The `tg://login` link while the state is authorizationStateWaitOtherDeviceConfirmation; null otherwise. */
    val authLink: StateFlow<String?> = _authLink

    /**
     * Updates from [DefaultTdUpdates]. Lossy: a collector that falls 512 behind loses the oldest,
     * since blocking the receive loop would also stall every [call] answer.
     */
    val updates: SharedFlow<JsonObject> = _updates

    fun start() {
        check(clientId < 0) { "already started" }
        _authError.value = null
        closed = CompletableDeferred()
        clientId = runtime.open(this)
        // TDLib only starts emitting updates after the first request.
        runtime.scope.launch { runCatching { call(request("getOption") { put("name", "version") }) } }
    }

    /** Sends [request] and returns TDLib's answer; throws [TdError] on an error answer or after [timeout]. */
    suspend fun call(request: JsonObject, timeout: Duration = 60.seconds): JsonObject {
        check(clientId >= 0) { "not started" }
        val extra = nextExtra.getAndUpdate { it + 1 }
        val reply = CompletableDeferred<JsonObject>()
        pending.update { it + (extra to reply) }
        try {
            runtime.native.send(clientId, JsonObject(request + ("@extra" to JsonPrimitive(extra))).toString())
            val r = withTimeoutOrNull(timeout) { reply.await() } ?: throw TdError(408, "Request timed out")
            if (r.type == "error") {
                throw TdError(r["code"]!!.jsonPrimitive.int, r["message"]!!.jsonPrimitive.content)
            }
            return r
        } finally {
            pending.update { it - extra }
        }
    }

    /** Waits for login to finish; throws with the reason if it fails or takes longer than [timeout]. */
    suspend fun ready(timeout: Duration = 30.seconds) {
        val outcome = withTimeoutOrNull(timeout) {
            combine(auth, authError) { a, e -> a to e }.first { (a, e) -> a == "authorizationStateReady" || e != null }
        } ?: throw TdError(408, "Login timed out")
        outcome.second?.let { throw TdError(0, it) }
    }

    /** Disconnects until [start] is called again; the session stays on disk. */
    suspend fun shutdown() {
        if (clientId < 0) return
        stopping = true
        try {
            call(request("close"), 10.seconds)
            withTimeoutOrNull(10.seconds) { closed.await() }
        } catch (_: TdError) {
            // Already closed.
        }
        runtime.close(clientId)
        clientId = -1
    }

    suspend fun version(): String =
        call(request("getOption") { put("name", "version") })["value"]?.jsonPrimitive?.contentOrNull ?: "?"

    internal fun onMessage(m: JsonObject) {
        val extra = m["@extra"]?.jsonPrimitive?.longOrNull
        if (extra != null) {
            pending.value[extra]?.complete(m) // late answers to timed-out calls have no taker
            return
        }
        if (m.type == "updateAuthorizationState") onAuth(m["authorization_state"]!!.jsonObject)
        _updates.tryEmit(m)
    }

    private fun onAuth(info: JsonObject) {
        val state = info.type!!
        _authLink.value = if (state == "authorizationStateWaitOtherDeviceConfirmation") info["link"]?.jsonPrimitive?.contentOrNull else null
        _auth.value = state
        when (state) {
            "authorizationStateWaitTdlibParameters" -> runtime.scope.launch { attempt { sendParameters() } }
            "authorizationStateWaitPhoneNumber" -> { // TDLib's name for "not logged in"
                if (config.isUser) return
                _authError.value = null
                runtime.scope.launch { attempt { call(request("checkAuthenticationBotToken") { put("token", config.botToken) }) } }
            }
            "authorizationStateClosed" -> {
                if (!stopping) _authError.value = if (config.isUser) "Telegram ended the account session." else "Telegram ended the bot session."
                stopping = false
                closed.complete(Unit)
            }
        }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try { block() } catch (e: TdError) { _authError.value = e.message }
    }

    private suspend fun sendParameters() {
        call(request("setTdlibParameters") {
            put("use_test_dc", false)
            put("database_directory", config.databaseDir)
            put("files_directory", config.filesDir)
            put("database_encryption_key", config.encryptionKey)
            put("use_file_database", true)
            put("use_chat_info_database", true)
            put("use_message_database", config.useMessageDatabase)
            put("use_secret_chats", false)
            put("api_id", config.apiId)
            put("api_hash", config.apiHash)
            put("system_language_code", config.languageCode)
            put("device_model", config.deviceModel)
            put("system_version", config.systemVersion)
            put("application_version", config.appVersion)
        })
    }
}

/** `{"@type": type, ...fields}`. */
fun request(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
    buildJsonObject {
        put("@type", type)
        fields()
    }
