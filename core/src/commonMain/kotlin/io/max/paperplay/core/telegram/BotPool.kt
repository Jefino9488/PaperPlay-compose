package io.max.paperplay.core.telegram

import io.max.paperplay.core.app.SecretStore
import io.max.paperplay.core.backup.SecretKeys
import io.max.paperplay.core.dupes.HttpPoster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** What the Extra bots page shows for one bot. The token itself is never part of this. */
data class PoolBot(
    /** Telegram's bot id (the part of the token before the colon). 0 is the main bot. */
    val id: Long,
    val username: String? = null,
    /** The bot's display name from Telegram. */
    val name: String? = null,
    /** A local file with the bot's profile picture, when it has one and it has been downloaded. */
    val photoPath: String? = null,
    /** "starting", "working" or "error". */
    val state: String = "starting",
    val error: String? = null,
    /** Epoch ms until which Telegram told this bot to wait (429 / FLOOD_WAIT); 0 when free. */
    val waitUntil: Long = 0L,
    val sent: Int = 0,
    val floods: Int = 0,
    val isMain: Boolean = false,
)

/** A bot chosen for one job. [id] is 0 for the main bot. */
class PoolPick(val id: Long, val client: TdClient)

/**
 * The main bot plus extra bots, each with its own TDLib session, so uploads can be shared. Tokens live in the
 * secret store under [SecretKeys.EXTRA_BOTS] as a JSON array of strings (the Flutter backup format). All bots must be
 * admins of the chat they post to.
 */
class BotPool(
    private val scope: CoroutineScope,
    private val secrets: SecretStore,
    private val http: HttpPoster,
    /** The main bot's token, to refuse a duplicate. */
    private val mainToken: () -> String?,
    private val main: () -> TdClient?,
    /** Makes a (not yet started) client for an extra bot, or null when there are no API credentials. */
    private val open: (id: Long, token: String) -> TdClient?,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _bots = MutableStateFlow<List<PoolBot>>(emptyList())
    val bots: StateFlow<List<PoolBot>> = _bots
    private val _mainWait = MutableStateFlow(PoolBot(0, state = "working", isMain = true))
    val mainBot: StateFlow<PoolBot> = _mainWait
    private val clients = HashMap<Long, TdClient>()
    private val lock = Mutex()
    private var turn = 0
    private var loaded = false

    /** Tokens as stored, in order. */
    fun tokens(): List<String> = readTokens()

    /** The logged-in client of extra bot [id], or null when it is not running. */
    fun clientOf(id: Long): TdClient? =
        clients[id]?.takeIf { c -> c.auth.value == "authorizationStateReady" && _bots.value.any { it.id == id && it.state == "working" } }

    /** Every extra bot that is logged in, with its client. */
    fun readyBots(): List<Pair<PoolBot, TdClient>> = _bots.value.mapNotNull { b -> clientOf(b.id)?.let { b to it } }

    /** Starts every saved extra bot. Safe to call again; it does nothing the second time. */
    fun load() {
        if (loaded) return
        loaded = true
        val tokens = readTokens()
        val loadedBots = tokens.mapNotNull { t -> botId(t)?.let { id -> PoolBot(id) } }
        _bots.value = loadedBots
        for (t in tokens) {
            val id = botId(t) ?: continue
            scope.launch { start(id, t) }
        }
    }

    /** Adds bots that a restored backup carries and this device lacks. */
    fun adopt(remote: List<String>) {
        val have = readTokens().toSet()
        for (t in remote) if (t !in have && parseBotToken(t) != null) scope.launch { add(t) }
    }

    /** Checks [token] with Telegram, saves it and starts the bot. Returns a problem in plain words, or null. */
    suspend fun add(token: String): String? {
        val t = parseBotToken(token) ?: return "That doesn't look like a bot token."
        val id = botId(t) ?: return "That doesn't look like a bot token."
        if (t == mainToken() || id == mainToken()?.let { m -> botId(m) }) return "That is the main bot."
        if (readTokens().contains(t) || _bots.value.any { it.id == id }) return "That bot is already added."
        val who = validate(t) ?: return "Telegram did not accept that token."
        _bots.update { it + PoolBot(id, username = who) }
        writeTokens(readTokens() + t)
        scope.launch { start(id, t) }
        return null
    }

    suspend fun remove(id: Long) {
        val keep = readTokens().filter { botId(it) != id }
        writeTokens(keep)
        _bots.update { l -> l.filter { it.id != id } }
        lock.withLock { clients.remove(id) }?.let { runCatching { it.shutdown() } }
    }

    /** Asks the Bot API who the token belongs to. Returns `@name`, or null when it is refused or unreachable. */
    suspend fun validate(token: String): String? {
        val body = http.post("https://api.telegram.org/bot$token/getMe", emptyMap()) ?: return null
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
        val name = root["result"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull
        return if (name == null) "@bot" else "@$name"
    }

    private suspend fun start(id: Long, token: String) {
        try {
            val c = open(id, token) ?: run {
                setBot(id) { it.copy(state = "error", error = "Enter the API id and hash first.") }
                return
            }
            lock.withLock { clients[id] = c }
            c.start()
            c.ready(45.seconds)
            val who = profileOf(c)
            setBot(id) { it.copy(state = "working", error = null, username = who.username ?: it.username, name = who.name ?: it.name, photoPath = who.photoPath ?: it.photoPath) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            setBot(id) { it.copy(state = "error", error = e.message ?: "It did not start.") }
        }
    }

    private class Who(val name: String?, val username: String?, val photoPath: String?)

    /** Name, @username and profile picture of the account [c] is logged in as. Anything Telegram does not give is null. */
    private suspend fun profileOf(c: TdClient): Who {
        val me = runCatching { c.call(request("getMe")) }.getOrNull() ?: return Who(null, null, null)
        val user = me.get("usernames")?.jsonObject?.get("active_usernames")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
        val name = listOfNotNull(me["first_name"]?.jsonPrimitive?.contentOrNull, me["last_name"]?.jsonPrimitive?.contentOrNull)
            .joinToString(" ").trim().ifEmpty { null }
        val fileId = me["profile_photo"]?.jsonObject?.get("small")?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
        val path = fileId?.let {
            runCatching {
                c.call(request("downloadFile") { put("file_id", it); put("priority", 1); put("offset", 0); put("limit", 0); put("synchronous", true) }, 60.seconds)
                    .get("local")?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull?.takeIf { p -> p.isNotEmpty() }
            }.getOrNull()
        }
        return Who(name, user?.let { "@$it" }, path)
    }

    /** Fills in the main bot's name, @username and picture from Telegram. False when the main bot is not logged in yet. */
    suspend fun loadMainProfile(): Boolean {
        val c = main() ?: return false
        if (c.auth.value != "authorizationStateReady") return false
        val who = profileOf(c)
        if (who.name == null && who.username == null) return false
        _mainWait.update { it.copy(name = who.name, username = who.username, photoPath = who.photoPath ?: it.photoPath) }
        return true
    }

    private fun setBot(id: Long, change: (PoolBot) -> PoolBot) =
        _bots.update { l -> l.map { if (it.id == id) change(it) else it } }

    /**
     * The next bot to send with: round robin over the bots that are logged in and not waiting out a flood limit. When
     * every one is waiting, the one that frees up first.
     */
    fun pick(): PoolPick? {
        val t = now()
        val candidates = buildList {
            main()?.let { add(PoolPick(0, it) to _mainWait.value.waitUntil) }
            for (b in _bots.value) {
                val c = clients[b.id] ?: continue
                if (b.state == "working" && c.auth.value == "authorizationStateReady") add(PoolPick(b.id, c) to b.waitUntil)
            }
        }
        if (candidates.isEmpty()) return null
        val free = candidates.filter { it.second <= t }
        if (free.isNotEmpty()) return free[turn++ % free.size].first
        return candidates.minBy { it.second }.first
    }

    /** Remembers that Telegram told bot [id] to wait [seconds]. */
    fun recordFlood(id: Long, seconds: Int) {
        val until = now() + seconds * 1000L
        if (id == 0L) _mainWait.update { it.copy(waitUntil = maxOf(it.waitUntil, until), floods = it.floods + 1) }
        else setBot(id) { it.copy(waitUntil = maxOf(it.waitUntil, until), floods = it.floods + 1) }
    }

    fun recordSent(id: Long) {
        if (id == 0L) _mainWait.update { it.copy(sent = it.sent + 1) } else setBot(id) { it.copy(sent = it.sent + 1) }
    }

    private fun readTokens(): List<String> {
        val raw = secrets.get(SecretKeys.EXTRA_BOTS) ?: return emptyList()
        val arr: JsonArray = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { parseBotToken(it) != null }
    }

    private fun writeTokens(list: List<String>) {
        if (list.isEmpty()) secrets.remove(SecretKeys.EXTRA_BOTS)
        else secrets.put(SecretKeys.EXTRA_BOTS, JsonArray(list.map { JsonPrimitive(it) }).toString())
    }

    companion object {
        private val TOKEN = Regex("\\d{5,}:[A-Za-z0-9_-]{20,}")

        /** The token inside [text] (trimmed, a leading `bot` allowed), or null when it is not shaped like one. */
        fun parseBotToken(text: String): String? {
            val t = text.trim().removePrefix("bot")
            return if (TOKEN.matches(t)) t else null
        }

        fun botId(token: String): Long? = token.substringBefore(':').toLongOrNull()
    }
}
