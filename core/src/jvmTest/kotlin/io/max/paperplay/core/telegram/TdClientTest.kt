package io.max.paperplay.core.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** Scripted TDLib: [onSend] sees each request and returns the messages TDLib would emit. */
class FakeTdNative(@Volatile var onSend: (clientId: Int, req: JsonObject) -> List<JsonObject> = { _, _ -> emptyList() }) : TdNative {
    val sent = LinkedBlockingQueue<JsonObject>()
    private val inbox = LinkedBlockingQueue<String>()
    private var nextId = 1

    override fun createClientId() = nextId++
    override fun send(clientId: Int, request: String) {
        val req = Json.parseToJsonElement(request).jsonObject
        sent.add(req)
        onSend(clientId, req).forEach { push(clientId, it) }
    }

    fun push(clientId: Int, m: JsonObject) {
        inbox.add(JsonObject(m + ("@client_id" to JsonPrimitive(clientId))).toString())
    }

    override fun receive(timeoutSeconds: Double): String? = inbox.poll((timeoutSeconds * 1000).toLong(), TimeUnit.MILLISECONDS)
    override fun execute(request: String): String? = null
}

private fun reply(req: JsonObject, type: String = "ok", value: String? = null) = buildJsonObject {
    put("@type", type)
    req["@extra"]?.let { put("@extra", it) }
    value?.let { put("value", it) }
}

private fun err(req: JsonObject, code: Int, message: String) = buildJsonObject {
    put("@type", "error")
    put("@extra", req["@extra"]!!)
    put("code", code)
    put("message", message)
}

private fun state(type: String) = buildJsonObject {
    put("@type", "updateAuthorizationState")
    put("authorization_state", buildJsonObject { put("@type", type) })
}

private val JsonObject.type get() = this["@type"]!!.jsonPrimitive.content
private val JsonObject.name get() = this["name"]?.jsonPrimitive?.content
private val JsonObject.value get() = this["value"]!!.jsonPrimitive.content
private fun opt(n: String) = request("getOption") { put("name", n) }

class TdClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = TdConfig(1, "hash", "123:fake-token", "/db", "/files", "key")

    @AfterTest fun tearDown() = scope.cancel()

    private fun client(fake: FakeTdNative) = TdClient(TdRuntime(fake, scope, Dispatchers.IO), config).also { it.start() }

    @Test fun matchesAnswersToCallsOutOfOrder() = runBlocking {
        val held = LinkedBlockingQueue<JsonObject>()
        val fake = FakeTdNative { _, req ->
            when (req.name) {
                "first" -> { held.add(req); emptyList() } // answered only after "second"
                "second" -> listOf(reply(req, value = "B"), reply(held.take(), value = "A"))
                else -> emptyList()
            }
        }
        val c = client(fake)
        val a = async(Dispatchers.Default) { c.call(opt("first")).value }
        while (held.isEmpty()) Thread.sleep(5)
        assertEquals("B", c.call(opt("second")).value)
        assertEquals("A", withTimeout(2000) { a.await() })
    }

    @Test fun errorCarriesRetryAfter() = runBlocking {
        val fake = FakeTdNative { _, req ->
            when (req.name) {
                "flood" -> listOf(err(req, 429, "Too Many Requests: retry after 7"))
                "floodNoNumber" -> listOf(err(req, 429, "Too Many Requests"))
                "bad" -> listOf(err(req, 400, "Bad Request"))
                else -> emptyList()
            }
        }
        val c = client(fake)
        assertEquals(7, assertFailsWith<TdError> { c.call(opt("flood")) }.retryAfter)
        assertEquals(5, assertFailsWith<TdError> { c.call(opt("floodNoNumber")) }.retryAfter)
        val bad = assertFailsWith<TdError> { c.call(opt("bad")) }
        assertEquals(400, bad.code)
        assertNull(bad.retryAfter)
    }

    @Test fun timesOutAndIgnoresLateAnswer() = runBlocking {
        val fake = FakeTdNative()
        val c = client(fake)
        val e = assertFailsWith<TdError> { c.call(request("getMessages"), timeout = 100.milliseconds) }
        assertEquals(408, e.code)
        // The late answer finds no taker and must not break later calls.
        fake.push(1, reply(fake.sent.last()))
        fake.onSend = { _, req -> listOf(reply(req, value = "ok")) }
        assertEquals("ok", c.call(opt("x")).value)
    }

    @Test fun updatesAreFilteredAndAnswersStayOut() = runBlocking {
        val fake = FakeTdNative { _, req -> listOf(reply(req)) }
        val c = client(fake)
        val first = async(start = CoroutineStart.UNDISPATCHED) { c.updates.first() }
        fake.push(1, buildJsonObject { put("@type", "updateUserStatus") })
        fake.push(1, buildJsonObject { put("@type", "updateNewMessage"); put("id", 9) })
        c.call(request("getMe"))
        assertEquals("updateNewMessage", withTimeout(2000) { first.await() }.type)
    }

    @Test fun clientsAreRoutedByClientId() = runBlocking {
        val fake = FakeTdNative { id, req -> listOf(reply(req, value = "c$id")) }
        val rt = TdRuntime(fake, scope, Dispatchers.IO)
        val a = TdClient(rt, config).also { it.start() }
        val b = TdClient(rt, config).also { it.start() }
        assertEquals("c1", a.call(opt("x")).value)
        assertEquals("c2", b.call(opt("x")).value)
    }

    @Test fun botLoginFlow() = runBlocking {
        val fake = FakeTdNative { _, req ->
            when (req.type) {
                "getOption" -> listOf(reply(req), state("authorizationStateWaitTdlibParameters"))
                "setTdlibParameters" -> listOf(reply(req), state("authorizationStateWaitPhoneNumber"))
                "checkAuthenticationBotToken" -> listOf(reply(req), state("authorizationStateReady"))
                else -> emptyList()
            }
        }
        client(fake).ready()
        val params = fake.sent.first { it.type == "setTdlibParameters" }
        assertEquals("/db", params["database_directory"]!!.jsonPrimitive.content)
        assertEquals("key", params["database_encryption_key"]!!.jsonPrimitive.content)
        assertEquals("123:fake-token", fake.sent.first { it.type == "checkAuthenticationBotToken" }["token"]!!.jsonPrimitive.content)
    }

    @Test fun badTokenFailsReady() = runBlocking {
        val fake = FakeTdNative { _, req ->
            when (req.type) {
                "getOption" -> listOf(reply(req), state("authorizationStateWaitPhoneNumber"))
                "checkAuthenticationBotToken" -> listOf(err(req, 401, "Unauthorized"))
                else -> emptyList()
            }
        }
        assertEquals("Unauthorized", assertFailsWith<TdError> { client(fake).ready() }.message)
    }
}
