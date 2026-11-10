package io.max.paperplay.core.recs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The forum's built-in General topic. Everything unsorted lands there, so it is not learned from unless turned on. */
const val GENERAL_TOPIC_ID = 1048576L

/** What mixes may learn from and do. Shared between devices, newest wins. */
data class TasteSettings(
    /** When these were last changed. */
    val at: Long = 0,
    /** When learned data was last cleared. Older counts from another device are ignored. */
    val resetAt: Long = 0,
    /** Which lists to learn from, where someone chose. See [learnsFrom] for the default. */
    val sources: Map<String, Boolean> = emptyMap(),
    /** Names given to topics here, since a bot can't always see a topic's current name. */
    val topicNames: Map<String, String> = emptyMap(),
    val showMixes: Boolean = true,
    val learnFromPlays: Boolean = false,
    val onlyFromLists: Boolean = false,
    val restRecent: Boolean = true,
    val lookups: Boolean = true,
) {
    fun learnsFrom(key: String): Boolean = sources[key] ?: !key.endsWith(":$GENERAL_TOPIC_ID")

    fun encode(): String = buildJsonObject {
        put("at", at)
        put("resetAt", resetAt)
        put("sources", buildJsonObject { sources.forEach { (k, v) -> put(k, v) } })
        put("topicNames", buildJsonObject { topicNames.forEach { (k, v) -> put(k, v) } })
        put("showMixes", showMixes)
        put("learnFromPlays", learnFromPlays)
        put("onlyFromLists", onlyFromLists)
        put("restRecent", restRecent)
        put("lookups", lookups)
    }.toString()

    companion object {
        const val FAVORITES_KEY = "favorites"
        const val PREF_KEY = "taste_settings"
        fun playlistKey(uuid: String) = "playlist:$uuid"
        fun topicKey(chatId: Long, topicId: Long) = "topic:$chatId:$topicId"

        /** Bad or empty text gives the defaults. */
        fun parse(text: String?): TasteSettings {
            if (text.isNullOrEmpty()) return TasteSettings()
            return try {
                val j = Json.parseToJsonElement(text) as? JsonObject ?: return TasteSettings()
                fun flag(k: String, d: Boolean) = (j[k] as? JsonPrimitive)?.booleanOrNull ?: d
                fun num(k: String) = (j[k] as? JsonPrimitive)?.longOrNull ?: 0L
                TasteSettings(
                    at = num("at"),
                    resetAt = num("resetAt"),
                    sources = (j["sources"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.booleanOrNull?.let { k to it }
                    }.toMap(),
                    topicNames = (j["topicNames"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                    }.toMap(),
                    showMixes = flag("showMixes", true),
                    learnFromPlays = flag("learnFromPlays", false),
                    onlyFromLists = flag("onlyFromLists", false),
                    restRecent = flag("restRecent", true),
                    lookups = flag("lookups", true),
                )
            } catch (e: Exception) {
                TasteSettings()
            }
        }
    }
}

private val listMoods = mapOf(
    "sad" to listOf("sad"),
    "cry" to listOf("sad"),
    "heartbreak" to listOf("sad"),
    "calm" to listOf("chill"),
    "chill" to listOf("chill"),
    "relax" to listOf("chill"),
    "sleep" to listOf("chill"),
    "lofi" to listOf("chill", "focus"),
    "lo-fi" to listOf("chill", "focus"),
    "study" to listOf("focus"),
    "focus" to listOf("focus"),
    "phonk" to listOf("workout", "drive"),
    "gym" to listOf("workout"),
    "workout" to listOf("workout"),
    "hype" to listOf("workout", "party"),
    "drive" to listOf("drive"),
    "driving" to listOf("drive"),
    "road" to listOf("drive"),
    "party" to listOf("party"),
    "dance" to listOf("party"),
    "love" to listOf("love"),
    "romantic" to listOf("love"),
    "rain" to listOf("rain"),
    "monsoon" to listOf("rain"),
    "night" to listOf("night"),
    "midnight" to listOf("night"),
    "happy" to listOf("happy"),
    "feeling good" to listOf("happy"),
    "good vibes" to listOf("happy"),
)

/**
 * Moods a list's own name says it's for: someone's "Sad Songs" is better evidence of what they find sad than
 * any lyric. "Work" is left out, since it's as often a leftover name as a mood.
 */
fun moodsForListName(name: String): Set<String> {
    val n = name.lowercase()
    return buildSet { for ((k, v) in listMoods) if (n.contains(k)) addAll(v) }
}
