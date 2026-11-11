package io.max.paperplay.core.account

import io.max.paperplay.core.app.Keys
import io.max.paperplay.core.app.SecretStore
import io.max.paperplay.core.app.Settings
import io.max.paperplay.core.dupes.AccountDeleter
import io.max.paperplay.core.telegram.FakeTdNative
import io.max.paperplay.core.telegram.TdRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val JsonObject.type get() = this["@type"]!!.jsonPrimitive.content
private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

private fun answer(req: JsonObject, json: String = """{"@type":"ok"}""") =
    Json.parseToJsonElement(json).jsonObject.let { JsonObject(it + ("@extra" to req["@extra"]!!)) }

private fun state(type: String, extra: String = "") =
    Json.parseToJsonElement("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"$type"$extra}}""").jsonObject

private fun failure(req: JsonObject, code: Int, message: String) = answer(req, """{"@type":"error","code":$code,"message":"$message"}""")

private fun audio(id: Long) = """{"@type":"message","id":$id,"chat_id":-100,"date":5,"content":{"@type":"messageAudio",
 "audio":{"duration":200,"title":"T$id","performer":"A","file_name":"f$id.mp3","mime_type":"audio/mpeg",
 "audio":{"id":$id,"size":1000,"remote":{"id":"r$id","unique_id":"u$id"}}}}}"""

private fun document(id: Long, name: String, mime: String) = """{"@type":"message","id":$id,"chat_id":-100,"date":5,"content":{"@type":"messageDocument",
 "document":{"file_name":"$name","mime_type":"$mime","document":{"id":$id,"size":10,"remote":{"id":"r$id","unique_id":"u$id"}}}}}"""

private fun page(next: Long, vararg messages: String) =
    """{"@type":"foundChatMessages","next_from_message_id":$next,"messages":[${messages.joinToString(",")}]}"""

class UserSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = HashMap<String, String>()
    private val pauses = ArrayList<Long>()
    private val secretMap = HashMap<String, String>()
    private val secrets = object : SecretStore {
        override fun get(key: String) = secretMap[key]
        override fun put(key: String, value: String) { secretMap[key] = value }
        override fun remove(key: String) { secretMap.remove(key) }
    }
    private val settings = object : Settings {
        private val m = mapOf(Keys.API_ID to "1", Keys.API_HASH to "hash")
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) {}
    }

    @AfterTest fun tearDown() = scope.cancel()

    private fun session(fake: FakeTdNative, loggedIn: Boolean = false): UserSession {
        if (loggedIn) prefs[USER_SESSION_PREF] = "1"
        return UserSession(
            scope, { TdRuntime(fake, scope, Dispatchers.IO) }, "/data", secrets, settings, { prefs[it] }, { k, v -> prefs[k] = v },
            pause = { pauses += it },
        )
    }

    /** TDLib that logs in at once (as a saved session) and answers other requests with [other]. */
    private fun connected(other: (JsonObject) -> List<JsonObject>) = FakeTdNative { _, req ->
        when (req.type) {
            "getOption" -> listOf(state("authorizationStateWaitTdlibParameters"))
            "setTdlibParameters" -> listOf(answer(req), state("authorizationStateReady"))
            else -> other(req)
        }
    }

    private suspend fun UserSession.waitFor(wanted: LoginStep) { withTimeout(5000) { step.first { it == wanted } } }

    @Test fun phoneLoginGoesThroughCodeAndPassword() = runBlocking {
        val fake = FakeTdNative { _, req ->
            when (req.type) {
                "getOption" -> listOf(state("authorizationStateWaitTdlibParameters"))
                "setTdlibParameters" -> listOf(answer(req), state("authorizationStateWaitPhoneNumber"))
                "setAuthenticationPhoneNumber" -> listOf(answer(req), state("authorizationStateWaitCode"))
                "checkAuthenticationCode" -> listOf(answer(req), state("authorizationStateWaitPassword"))
                "checkAuthenticationPassword" -> listOf(answer(req), state("authorizationStateReady"))
                else -> emptyList()
            }
        }
        val s = session(fake)
        assertEquals(LoginStep.LoggedOut, s.step.value)
        s.begin()
        s.waitFor(LoginStep.Phone)
        s.phone("+100")
        s.waitFor(LoginStep.Code)
        assertFalse(s.loggedIn.value)
        s.code("12345")
        s.waitFor(LoginStep.Password)
        s.password("pw")
        s.waitFor(LoginStep.Ready)
        assertTrue(s.loggedIn.value)
        assertEquals("1", prefs[USER_SESSION_PREF])
        val params = fake.sent.first { it.type == "setTdlibParameters" }
        assertEquals("/data/user_td", params.str("database_directory"))
        assertFalse(fake.sent.any { it.type == "checkAuthenticationBotToken" })
    }

    @Test fun qrLoginShowsLinkThenReady() = runBlocking {
        val fake = FakeTdNative { _, req ->
            when (req.type) {
                "getOption" -> listOf(state("authorizationStateWaitTdlibParameters"))
                "setTdlibParameters" -> listOf(answer(req), state("authorizationStateWaitPhoneNumber"))
                "requestQrCodeAuthentication" ->
                    listOf(answer(req), state("authorizationStateWaitOtherDeviceConfirmation", ""","link":"tg://login?token=abc""""))
                else -> emptyList()
            }
        }
        val s = session(fake)
        s.beginQr()
        s.waitFor(LoginStep.Qr("tg://login?token=abc"))
        fake.push(1, state("authorizationStateReady"))
        s.waitFor(LoginStep.Ready)
        assertTrue(s.loggedIn.value)
    }

    @Test fun wrongCodeFailsAndBeginRepublishesTheStep() = runBlocking {
        val fake = FakeTdNative { _, req ->
            when (req.type) {
                "getOption" -> listOf(state("authorizationStateWaitTdlibParameters"))
                "setTdlibParameters" -> listOf(answer(req), state("authorizationStateWaitCode"))
                "checkAuthenticationCode" -> listOf(failure(req, 400, "PHONE_CODE_INVALID"))
                else -> emptyList()
            }
        }
        val s = session(fake)
        s.begin()
        s.waitFor(LoginStep.Code)
        s.code("0")
        assertEquals(LoginStep.Failed("PHONE_CODE_INVALID"), s.step.value)
        s.begin()
        assertEquals(LoginStep.Code, s.step.value)
    }

    @Test fun songsInPagesNewestFirstAndStopsAtAfterId() = runBlocking {
        val fake = connected { req ->
            val filter = req["filter"]?.jsonObject?.type
            val from = req["from_message_id"]?.jsonPrimitive?.content
            listOf(answer(req, when {
                req.type != "searchChatMessages" -> """{"@type":"ok"}"""
                filter == "searchMessagesFilterAudio" && from == "0" -> page(300, audio(400), audio(300))
                filter == "searchMessagesFilterAudio" && from == "300" -> page(100, audio(200), audio(100))
                filter == "searchMessagesFilterDocument" ->
                    page(0, document(150, "x.flac", "application/octet-stream"), document(140, "a.pdf", "application/pdf"), document(130, "b.mp3", "audio/mpeg"))
                else -> page(0)
            }))
        }
        val s = session(fake, loggedIn = true)
        val all = s.songsIn(-100).toList()
        assertEquals(listOf(listOf(400L, 300L), listOf(200L, 100L), listOf(150L, 130L)), all.map { b -> b.map { it.messageId } })
        assertEquals("A", all[0][0].artist)
        assertEquals(listOf(3000L, 3000L), pauses)

        // Stops inside the second page at 250: 200 and 100 are older and never come out.
        val after = s.songsIn(-100, afterMessageId = 150).toList()
        assertEquals(listOf(listOf(400L, 300L), listOf(200L)), after.map { b -> b.map { it.messageId } }.take(2))
        assertFalse(after.flatten().any { it.messageId == 100L || it.messageId == 150L || it.messageId == 130L })
        val firstSearch = fake.sent.first { it.type == "searchChatMessages" }
        assertEquals("100", firstSearch.str("limit"))
        assertEquals("0", firstSearch.str("from_message_id"))
    }

    @Test fun floodWaitIsWaitedOut() = runBlocking {
        var failed = false
        val fake = connected { req ->
            if (!failed) { failed = true; listOf(failure(req, 429, "Too Many Requests: retry after 2")) }
            else listOf(answer(req, page(0, audio(9))))
        }
        val s = session(fake, loggedIn = true)
        assertEquals(1, s.songsIn(-100).toList().first().size)
        assertEquals(3000L, pauses.first())
    }

    @Test fun copyPostMakesTargetKnownAndForwardsACopy() = runBlocking {
        val fake = connected { req ->
            listOf(
                when (req.type) {
                    "getChat" -> failure(req, 400, "Chat not found")
                    "forwardMessages" -> answer(req, """{"@type":"messages","total_count":1,"messages":[{"@type":"message","id":5}]}""")
                    else -> answer(req)
                },
            )
        }
        val s = session(fake, loggedIn = true)
        s.copyPost(-1009L, 77L, -1001234567890L)
        val sent = fake.sent.filter { it.type in setOf("getChat", "createSupergroupChat", "forwardMessages") }
        assertEquals(listOf("getChat", "createSupergroupChat", "forwardMessages"), sent.map { it.type })
        assertEquals("1234567890", sent[1].str("supergroup_id"))
        val fwd = sent[2]
        assertEquals("-1001234567890", fwd.str("chat_id"))
        assertEquals("-1009", fwd.str("from_chat_id"))
        assertEquals("77", fwd["message_ids"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("true", fwd.str("send_copy"))
        assertEquals("false", fwd.str("remove_caption"))
    }

    @Test fun copyPostFailsWhenTelegramRefusesToCopy() = runBlocking {
        val fake = connected { req ->
            listOf(if (req.type == "forwardMessages") answer(req, """{"@type":"messages","total_count":1,"messages":[null]}""") else answer(req))
        }
        val s = session(fake, loggedIn = true)
        val failed = runCatching { s.copyPost(-1009L, 77L, -1009L) }.exceptionOrNull()
        assertTrue(failed is IllegalStateException)
    }

    @Test fun cloudLookupTakesNewestMessageWithTagAndReadsFile() = runBlocking {
        val file = File.createTempFile("cloud", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val path = file.path.replace("\\", "\\\\")
        fun doc(id: Long, caption: String, fileId: Long) =
            """{"@type":"message","id":$id,"content":{"@type":"messageDocument","document":{"document":{"id":$fileId}},"caption":{"text":"$caption"}}}"""
        val fake = connected { req ->
            listOf(answer(req, when (req.type) {
                "getMe" -> """{"@type":"user","id":42}"""
                "createPrivateChat" -> """{"@type":"chat","id":42}"""
                "searchChatMessages" -> page(0, doc(40, "other", 4), doc(30, "paperplay-x", 3), doc(20, "paperplay-x", 2))
                "downloadFile" -> """{"@type":"file","id":3,"size":3,"local":{"path":"$path"}}"""
                else -> """{"@type":"ok"}"""
            }))
        }
        val s = session(fake, loggedIn = true)
        assertContentEquals(byteArrayOf(1, 2, 3), s.loadFromCloud("paperplay-x"))
        val search = fake.sent.last { it.type == "searchChatMessages" }
        assertEquals("paperplay-x", search.str("query"))
        assertEquals("42", search.str("chat_id"))
        assertEquals("searchMessagesFilterDocument", search["filter"]!!.jsonObject.type)
        val dl = fake.sent.last { it.type == "downloadFile" }
        assertEquals("3", dl.str("file_id"))
        assertEquals("true", dl.str("synchronous"))
    }

    @Test fun cloudLookupWithoutMatchIsNull() = runBlocking {
        val fake = connected { req ->
            listOf(answer(req, if (req.type == "searchChatMessages") page(0) else """{"@type":"chat","id":42}"""))
        }
        assertNull(session(fake, loggedIn = true).loadFromCloud("none"))
    }

    @Test fun deleterBatchesAndReportsRefused() = runBlocking {
        val deleted = ArrayList<List<Long>>()
        val fake = connected { req ->
            if (req.type == "deleteMessages") {
                val ids = req["message_ids"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() }
                if (-1L in ids) listOf(failure(req, 400, "MESSAGE_DELETE_FORBIDDEN")) else { deleted += ids; listOf(answer(req)) }
            } else listOf(answer(req, """{"@type":"chat","id":-100}"""))
        }
        val d: AccountDeleter = UserAccountDeleter(session(fake, loggedIn = true))
        assertTrue(d.isLoggedIn)
        assertEquals(listOf(-1L), d.deleteBatch(-100, listOf(1L, -1L, 2L)))
        assertEquals(listOf(listOf(1L), listOf(2L)), deleted)
        assertEquals(listOf(5L), UserAccountDeleter(session(connected { emptyList() })).deleteBatch(-100, listOf(5L)))
    }
}
