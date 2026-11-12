package io.max.paperplay.core.dupes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One form POST. Returns the response body, or null if Telegram couldn't be reached (15 s limit). Never throws. */
fun interface HttpPoster {
    suspend fun post(url: String, form: Map<String, String>): String?
}

/** The Bot API step of a delete: removes what TDLib's stale rights refused. */
fun interface BotDeleter {
    /** Deletes up to 100 posts in one call. Telegram skips the ones it can't delete and still answers ok. */
    suspend fun deleteMessages(chatId: Long, tdlibMessageIds: List<Long>): Boolean
}

/**
 * Telegram's HTTP Bot API, used only to ask the server what TDLib can't decide for itself: TDLib
 * judges from cached rights, which can be out of date. The bot token is part of every address, so
 * nothing from a failed request is ever reported.
 */
class BotApi(private val token: suspend () -> String?, private val http: HttpPoster) : BotDeleter {
    private var botId: Long? = null

    private suspend fun call(method: String, params: Map<String, String>): JsonObject? {
        val t = token() ?: return null
        val body = http.post("https://api.telegram.org/bot$t/$method", params) ?: return null
        return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    /** Whether the bot may delete other people's posts in [chatId], as Telegram says it. Null if Telegram couldn't be asked. */
    suspend fun canDelete(chatId: Long): Boolean? {
        if (botId == null) botId = call("getMe", emptyMap())?.get("result")?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
        val id = botId ?: return null
        val member = call("getChatMember", mapOf("chat_id" to "$chatId", "user_id" to "$id"))
            ?.get("result")?.jsonObject ?: return null
        val status = member["status"]?.jsonPrimitive?.contentOrNull
        return status == "creator" ||
            (status == "administrator" && member["can_delete_messages"]?.jsonPrimitive?.booleanOrNull == true)
    }

    /** Why the bot cannot repost and delete in [chatId] (a plain sentence), or null when it can. Asks Telegram, not TDLib's cache. */
    suspend fun moveProblem(chatId: Long): String? {
        if (botId == null) botId = call("getMe", emptyMap())?.get("result")?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
        val id = botId ?: return "Telegram could not be reached to check the bot's rights"
        val member = call("getChatMember", mapOf("chat_id" to "$chatId", "user_id" to "$id"))
            ?.get("result")?.jsonObject ?: return "Telegram could not be reached to check the bot's rights"
        val status = member["status"]?.jsonPrimitive?.contentOrNull
        if (status == "creator") return null
        if (status != "administrator") return "The bot is not an admin of this chat. Make it an admin that can post and delete messages"
        if (member["can_delete_messages"]?.jsonPrimitive?.booleanOrNull != true) return "The bot cannot delete messages in this chat. Give it that admin right"
        if (member["can_post_messages"]?.jsonPrimitive?.booleanOrNull == false) return "The bot cannot post messages in this chat. Give it that admin right"
        return null
    }

    /** TDLib's message id is the server's shifted up by 20 bits. */
    override suspend fun deleteMessages(chatId: Long, tdlibMessageIds: List<Long>): Boolean {
        val ids = JsonArray(tdlibMessageIds.map { JsonPrimitive(it shr 20) }).toString()
        return call("deleteMessages", mapOf("chat_id" to "$chatId", "message_ids" to ids))
            ?.get("ok")?.jsonPrimitive?.booleanOrNull == true
    }

    /** Deletes a post through the server. Null on success, otherwise Telegram's own reason. */
    suspend fun deleteMessage(chatId: Long, tdlibMessageId: Long): String? {
        val reply = call("deleteMessage", mapOf("chat_id" to "$chatId", "message_id" to "${tdlibMessageId shr 20}"))
            ?: return "Telegram couldn't be reached"
        return if (reply["ok"]?.jsonPrimitive?.booleanOrNull == true) null
        else reply["description"]?.jsonPrimitive?.contentOrNull ?: "refused"
    }
}

expect fun defaultHttpPoster(): HttpPoster
