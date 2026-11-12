package io.max.paperplay.core.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a backup holds, for the message after saving. */
class BackupSummary(val songs: Int, val covers: Int, val credentials: Boolean, val session: Boolean)

/**
 * Writes the zip the Flutter app (Teleplayer) writes and reads: `library.json` (Drift `toJson` rows, camelCase keys),
 * `credentials.enc`, `profile/<photo>`, `usersession.enc` and `covers/<name>`. The music itself is not in it.
 */
object BackupWriter {
    private const val VERSION = 1
    private val snake = Regex("_([a-z])")

    /** SQLite has no boolean type; Drift's `fromJson` wants true or false for these, not 0 or 1. */
    private val booleans = mapOf(
        "songs" to setOf("favorite", "cover_hi_res", "user_edited", "pinned", "instrumental"),
        "library_chats" to setOf("use_bot", "walk_done"),
    )

    /**
     * Fills [sink] from the library at [driver]. [sealedCredentials] is the output of [sealBytes] over the credentials
     * JSON; [sealedSessionPath] is a sealed session file made by [sealFile]. [createdAt] is an ISO-8601 time.
     */
    fun write(
        driver: SqlDriver,
        sink: BackupSink,
        createdAt: String,
        sealedCredentials: String? = null,
        sealedSessionPath: String? = null,
        progress: (String, Double?) -> Unit = { _, _ -> },
    ): BackupSummary {
        progress("Reading your library", 0.02)
        val covers = linkedSetOf<String>() // cover paths
        val songs = rows(driver, "songs") { row -> (row["coverPath"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let(covers::add) }
        var prefs = rows(driver, "prefs")
        // Whether the account is signed in on this device travels with its session, not without it.
        if (sealedSessionPath == null) prefs = JsonArray(prefs.filter { (it as JsonObject)["key"]?.let { k -> (k as JsonPrimitive).content } != "user_session" })
        val prefMap = prefs.associate { (it as JsonObject).getValue("key").let { k -> (k as JsonPrimitive).content } to (it.getValue("value") as JsonPrimitive).content }
        val manifest = JsonObject(
            linkedMapOf(
                "version" to JsonPrimitive(VERSION),
                "createdAt" to JsonPrimitive(createdAt),
                "chats" to rows(driver, "library_chats"),
                "songs" to songs,
                "playlists" to rows(driver, "playlists"),
                "playlistSongs" to rows(driver, "playlist_songs"),
                "missingTracks" to rows(driver, "missing_tracks"),
                "moodVotes" to rows(driver, "mood_votes"),
                "forumTopics" to rows(driver, "forum_topics"),
                "postCopies" to rows(driver, "post_copies"),
                "prefs" to prefs,
                "layout" to JsonObject(layoutPrefs.filter { it in prefMap }.associateWith { JsonPrimitive(prefMap.getValue(it)) }),
            ),
        )
        progress("Packing", 0.08)
        sink.add("library.json", Json.encodeToString(JsonObject.serializer(), manifest).encodeToByteArray())
        sealedCredentials?.let { sink.add("credentials.enc", it.encodeToByteArray()) }
        prefMap["profile_photo"]?.takeIf { it.isNotEmpty() }?.let { sink.addFile("profile/${baseName(it)}", it) }
        sealedSessionPath?.let { sink.addFile("usersession.enc", it) }

        val added = mutableSetOf<String>()
        var done = 0
        for (path in covers) {
            done++
            if (done % 25 == 0) progress("Adding covers: $done of ${covers.size}", 0.1 + 0.82 * done / covers.size)
            if (added.contains(baseName(path))) continue
            if (sink.addFile("covers/${baseName(path)}", path)) added += baseName(path)
        }
        progress("Done", 1.0)
        return BackupSummary(songs.size, added.size, sealedCredentials != null, sealedSessionPath != null)
    }

    private fun baseName(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

    /** All rows of [table] as Drift `toJson` objects: camelCase keys, booleans as booleans, nulls as null. */
    private fun rows(driver: SqlDriver, table: String, each: (Map<String, JsonElement>) -> Unit = {}): JsonArray {
        val cols = driver.executeQuery(null, "PRAGMA table_info($table)", { c ->
            val out = mutableListOf<Pair<String, Boolean>>() // name, is integer
            while (c.next().value) out += c.getString(1)!! to c.getString(2)!!.uppercase().startsWith("INT")
            QueryResult.Value<List<Pair<String, Boolean>>>(out)
        }, 0).value
        if (cols.isEmpty()) return JsonArray(emptyList())
        val bools = booleans[table].orEmpty()
        val keys = cols.map { (n, _) -> snake.replace(n) { it.groupValues[1].uppercase() } }
        return driver.executeQuery(null, "SELECT ${cols.joinToString(",") { "\"${it.first}\"" }} FROM $table", { c ->
            val out = mutableListOf<JsonElement>()
            while (c.next().value) {
                val row = linkedMapOf<String, JsonElement>()
                cols.forEachIndexed { i, (name, isInt) ->
                    row[keys[i]] = when {
                        isInt -> c.getLong(i)?.let { if (name in bools) JsonPrimitive(it != 0L) else JsonPrimitive(it) } ?: JsonNull
                        else -> c.getString(i)?.let { JsonPrimitive(it) } ?: JsonNull
                    }
                }
                each(row)
                out += JsonObject(row)
            }
            QueryResult.Value<List<JsonElement>>(out)
        }, 0).value.let(::JsonArray)
    }
}
