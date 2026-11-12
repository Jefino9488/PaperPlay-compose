package io.max.paperplay.core.account

import io.max.paperplay.core.app.Keys
import io.max.paperplay.core.app.SecretStore
import io.max.paperplay.core.app.Settings
import io.max.paperplay.core.app.long
import io.max.paperplay.core.app.obj
import io.max.paperplay.core.app.readRange
import io.max.paperplay.core.app.songFromMessage
import io.max.paperplay.core.app.str
import io.max.paperplay.core.backup.Fs
import io.max.paperplay.core.backup.SecretKeys
import io.max.paperplay.core.library.newUuid
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.TdConfig
import io.max.paperplay.core.telegram.TdError
import io.max.paperplay.core.telegram.TdRuntime
import io.max.paperplay.core.telegram.request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The pref that remembers a signed-in account, so a restart reconnects without a code. */
const val USER_SESSION_PREF = "user_session"

private const val READING_RATE_PREF = "rate_reading"
private const val DEFAULT_READING_RATE = 20
private const val PAGE = 100
private const val FLOOD_TRIES = 5

private val CHAT_LIST = buildJsonObject { put("@type", "chatListMain") }

/**
 * The owner's own account on a second TDLib client. Starts only when needed; a saved login
 * reconnects without a code. Never logs phone numbers, codes or passwords.
 */
class UserSession(
    private val scope: CoroutineScope,
    private val runtime: () -> TdRuntime,
    private val dataDir: String,
    private val secrets: SecretStore,
    private val settings: Settings,
    private val pref: (String) -> String?,
    private val setPref: (String, String) -> Unit,
    private val deviceModel: String = "Android",
    private val appVersion: String = "0.1.0",
    private val pause: suspend (millis: Long) -> Unit = { delay(it) },
) : AccountApi {
    private val _step = MutableStateFlow<LoginStep>(if (pref(USER_SESSION_PREF) == "1") LoginStep.Ready else LoginStep.LoggedOut)
    private val _loggedIn = MutableStateFlow(pref(USER_SESSION_PREF) == "1")
    private val lock = Mutex()
    private var client: TdClient? = null
    private var watcher: Job? = null
    private var qrMode = false

    override val step: StateFlow<LoginStep> = _step
    override val loggedIn: StateFlow<Boolean> = _loggedIn

    private val databaseDir get() = "$dataDir/user_td"
    private val filesDir get() = "$dataDir/user_td_files"

    // ---- login ----

    override suspend fun begin() {
        qrMode = false
        try {
            val c = open()
            publish(c.auth.value, c.authLink.value, c.authError.value)
        } catch (e: TdError) {
            _step.value = LoginStep.Failed(e.message)
        }
    }

    override suspend fun beginQr() {
        qrMode = true
        try {
            val c = open()
            withTimeoutOrNull(20.seconds) { c.auth.first { it == "authorizationStateWaitPhoneNumber" || it == "authorizationStateReady" } }
            if (c.auth.value == "authorizationStateWaitPhoneNumber") {
                c.call(request("requestQrCodeAuthentication") { put("other_user_ids", JsonArray(emptyList())) })
            }
        } catch (e: TdError) {
            _step.value = LoginStep.Failed(e.message)
        }
    }

    override suspend fun phone(number: String) = answer { it.call(request("setAuthenticationPhoneNumber") { put("phone_number", number) }) }

    override suspend fun code(code: String) = answer { it.call(request("checkAuthenticationCode") { put("code", code) }) }

    override suspend fun password(password: String) = answer { it.call(request("checkAuthenticationPassword") { put("password", password) }) }

    private suspend fun answer(block: suspend (TdClient) -> Unit) {
        try {
            block(open())
        } catch (e: TdError) {
            _step.value = LoginStep.Failed(e.message)
        }
    }

    override suspend fun logOut() {
        try {
            val c = open()
            runCatching { c.call(request("logOut"), 15.seconds) }
            c.shutdown()
        } catch (_: TdError) {
            // The session is forgotten here either way.
        }
        lock.withLock {
            watcher?.cancel()
            watcher = null
            client = null
        }
        runCatching { Fs.deleteRecursively(databaseDir) }
        runCatching { Fs.deleteRecursively(filesDir) }
        setPref(USER_SESSION_PREF, "0")
        _loggedIn.value = false
        _step.value = LoginStep.LoggedOut
    }

    /** Stops the client so its files are whole for a backup; it starts again when next needed. Stays logged in. */
    suspend fun closeForBackup() {
        val c = lock.withLock {
            watcher?.cancel()
            watcher = null
            client.also { client = null }
        }
        runCatching { c?.shutdown() }
    }

    /** The running client, starting it (and following its login state) when there is none. */
    private suspend fun open(): TdClient = lock.withLock {
        client?.let { return it }
        val apiId = settings.get(Keys.API_ID)?.toIntOrNull()
        val apiHash = settings.get(Keys.API_HASH)
        if (apiId == null || apiHash.isNullOrEmpty()) throw TdError(0, "Enter the API id and API hash first.")
        val key = secrets.get(SecretKeys.USER_SESSION_KEY) ?: (newUuid() + newUuid()).also { secrets.put(SecretKeys.USER_SESSION_KEY, it) }
        val c = TdClient(
            runtime(),
            TdConfig(
                apiId, apiHash, "", databaseDir, filesDir, key,
                deviceModel = deviceModel, appVersion = appVersion, useMessageDatabase = false,
            ),
        )
        client = c
        if (!_loggedIn.value) _step.value = LoginStep.Starting
        watcher = scope.launch {
            combine(c.auth, c.authLink, c.authError) { a, l, e -> Triple(a, l, e) }.collect { (a, l, e) -> publish(a, l, e) }
        }
        c.start()
        c
    }

    private fun publish(state: String, link: String?, error: String?) {
        if (error != null) {
            _step.value = LoginStep.Failed(error)
            return
        }
        when (state) {
            "authorizationStateWaitPhoneNumber" -> {
                if (_loggedIn.value) { // Telegram ended the saved session
                    _loggedIn.value = false
                    setPref(USER_SESSION_PREF, "0")
                }
                _step.value = if (qrMode) LoginStep.Starting else LoginStep.Phone
            }
            "authorizationStateWaitCode" -> _step.value = LoginStep.Code
            "authorizationStateWaitPassword" -> _step.value = LoginStep.Password
            "authorizationStateWaitOtherDeviceConfirmation" -> link?.let { _step.value = LoginStep.Qr(it) }
            "authorizationStateReady" -> {
                _loggedIn.value = true
                setPref(USER_SESSION_PREF, "1")
                _step.value = LoginStep.Ready
            }
            "", "authorizationStateWaitTdlibParameters" -> if (!_loggedIn.value) _step.value = LoginStep.Starting
            "authorizationStateWaitRegistration", "authorizationStateWaitEmailAddress", "authorizationStateWaitEmailCode",
            "authorizationStateWaitPremiumPurchase" -> _step.value = LoginStep.Failed("This account needs a sign-in step the app cannot do.")
        }
    }

    /** A client that is logged in; throws [TdError] when it cannot get there. */
    /** The account's TDLib client once it is logged in, for streaming songs the bot cannot read. */
    fun streamClient(): TdClient? = client.takeIf { _loggedIn.value }

    /** Starts the client and makes [chatId] known to it, so a song of that chat can be played right away. */
    suspend fun warmUp(chatId: Long) {
        runCatching {
            val c = ready()
            knowChat(c, chatId, loadList = true)
        }
    }

    private suspend fun ready(): TdClient {
        val c = open()
        c.ready(30.seconds)
        return c
    }

    /** Sends [req], waiting out flood control and trying again. */
    private suspend fun call(c: TdClient, req: JsonObject, timeoutSeconds: Int = 60): JsonObject {
        var tries = 0
        while (true) {
            try {
                return c.call(req, timeoutSeconds.seconds)
            } catch (e: TdError) {
                val wait = e.retryAfter
                if (wait == null || ++tries >= FLOOD_TRIES) throw e
                pause((wait + 1) * 1000L)
            }
        }
    }

    // ---- chats ----

    override suspend fun groupsAndChannels(): List<ChatInfo> {
        if (!_loggedIn.value) return emptyList()
        val c = ready()
        try {
            call(c, request("loadChats") { put("chat_list", CHAT_LIST); put("limit", 500) })
        } catch (e: TdError) {
            // Already all loaded (TDLib answers 404).
            if (e.code != 404) throw e
        }
        val ids = call(c, request("getChats") { put("chat_list", CHAT_LIST); put("limit", 500) })["chat_ids"] as? JsonArray ?: return emptyList()
        val out = ArrayList<ChatInfo>()
        for (id in ids) {
            val chatId = id.jsonPrimitive.longOrNull ?: continue
            val chat = call(c, request("getChat") { put("chat_id", chatId) })
            val type = chat.obj("type") ?: continue
            when (type.str("@type")) {
                "chatTypeBasicGroup" -> out += ChatInfo(chat.long("id") ?: continue, chat.str("title").orEmpty(), false)
                "chatTypeSupergroup" -> out += ChatInfo(chat.long("id") ?: continue, chat.str("title").orEmpty(), type["is_channel"]?.jsonPrimitive?.content == "true")
            }
        }
        return out
    }

    override fun songsIn(chatId: Long, afterMessageId: Long): Flow<List<ChatPost>> = flow {
        if (!_loggedIn.value) return@flow
        val c = ready()
        val seen = HashSet<Long>()
        for (filter in listOf("searchMessagesFilterAudio", "searchMessagesFilterDocument")) {
            var from = 0L
            while (true) {
                val page = call(c, request("searchChatMessages") {
                    put("chat_id", chatId)
                    put("query", "")
                    put("from_message_id", from)
                    put("offset", 0)
                    put("limit", PAGE)
                    put("filter", buildJsonObject { put("@type", filter) })
                })
                val messages = page["messages"] as? JsonArray ?: JsonArray(emptyList())
                val batch = ArrayList<ChatPost>()
                var reached = false
                for (m in messages) {
                    val msg = m.jsonObject
                    val id = msg.long("id") ?: continue
                    if (afterMessageId > 0 && id <= afterMessageId) { reached = true; break }
                    val song = songFromMessage(msg) ?: continue
                    if (!seen.add(id)) continue
                    batch += ChatPost(
                        chatId = chatId, messageId = id, uniqueId = song.unique_id, title = song.title, artist = song.artist,
                        durationSec = song.duration_sec.toInt(), size = song.size, mime = song.mime, fileName = song.file_name, date = song.date,
                    )
                }
                if (batch.isNotEmpty()) emit(batch)
                val next = page.long("next_from_message_id") ?: 0L
                if (reached || messages.isEmpty() || next == 0L) break
                from = next
                pause(60_000L / readingRate())
            }
        }
    }

    private fun readingRate() = (pref(READING_RATE_PREF)?.toIntOrNull() ?: DEFAULT_READING_RATE).coerceIn(1, 1200)

    /** Makes [chatId] known to this session; a new session knows no chats yet. */
    private suspend fun knowChat(c: TdClient, chatId: Long, loadList: Boolean) {
        try {
            call(c, request("getChat") { put("chat_id", chatId) })
            return
        } catch (_: TdError) {
            // Not in this session's cache yet.
        }
        if (loadList) {
            try {
                call(c, request("loadChats") { put("chat_list", CHAT_LIST); put("limit", 500) })
            } catch (_: TdError) {
                // Already all loaded.
            }
        }
        // -100... is a supergroup or channel; any other negative id is a basic group.
        if (chatId <= -1_000_000_000_000L || !loadList) {
            call(c, request("createSupergroupChat") { put("supergroup_id", -chatId - 1_000_000_000_000L); put("force", false) })
        } else {
            call(c, request("createBasicGroupChat") { put("basic_group_id", -chatId); put("force", false) })
        }
    }

    override suspend fun copyPost(fromChat: Long, messageId: Long, toChat: Long) {
        val c = ready()
        knowChat(c, toChat, loadList = false)
        val sent = call(c, request("forwardMessages") {
            put("chat_id", toChat)
            put("from_chat_id", fromChat)
            put("message_ids", buildJsonArray { add(messageId) })
            put("send_copy", true)
            put("remove_caption", false)
        })
        // Telegram answers with an empty entry for a post it will not copy, for example when the chat blocks saving content.
        val first = (sent["messages"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
        if (first !is kotlinx.serialization.json.JsonObject) {
            throw IllegalStateException("Telegram did not copy this post. The chat may block copying or forwarding.")
        }
    }

    /** Deletes [ids] for everyone in one call. False when not logged in or Telegram refuses. */
    suspend fun deleteMessages(chatId: Long, ids: List<Long>): Boolean {
        if (!_loggedIn.value) return false
        return try {
            val c = ready()
            knowChat(c, chatId, loadList = true)
            call(c, request("deleteMessages") {
                put("chat_id", chatId)
                put("message_ids", buildJsonArray { ids.forEach { add(it) } })
                put("revoke", true)
            })
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: TdError) {
            false
        }
    }

    // ---- Saved Messages ----

    private suspend fun savedChat(c: TdClient): Long {
        val me = call(c, request("getMe")).long("id") ?: throw TdError(0, "Could not read the account.")
        return call(c, request("createPrivateChat") { put("user_id", me); put("force", false) }).long("id") ?: me
    }

    override suspend fun saveToCloud(tag: String, fileName: String, content: ByteArray) {
        val c = ready()
        val chat = savedChat(c)
        val dir = "$filesDir/cloud_${newUuid()}"
        val path = "$dir/${fileName.replace('/', '_').replace('\\', '_')}"
        Fs.mkdirs(dir)
        Fs.write(path, content)
        try {
            val sentId = CompletableDeferred<Long>()
            val outcome = scope.async(start = CoroutineStart.UNDISPATCHED) {
                c.updates.first { u ->
                    val type = u["@type"]?.jsonPrimitive?.content
                    (type == "updateMessageSendSucceeded" || type == "updateMessageSendFailed") && u.long("old_message_id") == sentId.await()
                }
            }
            try {
                val sent = call(c, request("sendMessage") {
                    put("chat_id", chat)
                    put("input_message_content", buildJsonObject {
                        put("@type", "inputMessageDocument")
                        put("document", buildJsonObject { put("@type", "inputFileLocal"); put("path", path) })
                        put("caption", buildJsonObject { put("@type", "formattedText"); put("text", tag) })
                    })
                })
                sentId.complete(sent.long("id") ?: throw TdError(0, "Telegram did not accept the file."))
                val done = withTimeoutOrNull(5.minutes) { outcome.await() } ?: throw TdError(408, "Upload timed out")
                if (done["@type"]?.jsonPrimitive?.content == "updateMessageSendFailed") {
                    throw TdError(0, done.obj("error")?.str("message") ?: "Telegram refused the file.")
                }
            } finally {
                outcome.cancel()
            }
        } finally {
            runCatching { Fs.deleteRecursively(dir) }
        }
    }

    override suspend fun loadFromCloud(tag: String): ByteArray? {
        val c = ready()
        val chat = savedChat(c)
        val page = call(c, request("searchChatMessages") {
            put("chat_id", chat)
            put("query", tag)
            put("from_message_id", 0)
            put("offset", 0)
            put("limit", 50)
            put("filter", buildJsonObject { put("@type", "searchMessagesFilterDocument") })
        })
        val message = (page["messages"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .filter { it.obj("content")?.obj("caption")?.str("text")?.contains(tag) == true }
            .maxByOrNull { it.long("id") ?: 0L } ?: return null
        val fileId = message.obj("content")?.obj("document")?.obj("document")?.long("id") ?: return null
        val file = call(c, request("downloadFile") {
            put("file_id", fileId)
            put("priority", 1)
            put("offset", 0)
            put("limit", 0)
            put("synchronous", true)
        }, 600)
        val path = file.obj("local")?.str("path")?.takeIf { it.isNotEmpty() } ?: return null
        val size = file.long("size") ?: return null
        return readRange(path, 0, size.toInt())
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

/** Lets the delete queue ask the owner's account to delete what the bot is refused. */
class UserAccountDeleter(private val session: UserSession) : io.max.paperplay.core.dupes.AccountDeleter {
    override val isLoggedIn: Boolean get() = session.loggedIn.value

    override suspend fun deleteBatch(chatId: Long, ids: List<Long>): List<Long> {
        if (!isLoggedIn) return ids
        val left = ArrayList<Long>()
        for (chunk in ids.chunked(100)) {
            if (session.deleteMessages(chatId, chunk)) continue
            // One refused post fails the whole call, so the rest go singly.
            for (id in chunk) if (!session.deleteMessages(chatId, listOf(id))) left += id
        }
        return left
    }
}
