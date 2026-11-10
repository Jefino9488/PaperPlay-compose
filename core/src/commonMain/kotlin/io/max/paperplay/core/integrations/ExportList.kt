package io.max.paperplay.core.integrations

import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.MissingTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.time.Clock

/** One song that wasn't found in the library, with where to find it elsewhere. Links are null when unknown. */
data class ExportRow(
    val title: String,
    val artist: String,
    val durationSec: Int,
    val spotifyUrl: String? = null,
    val appleUrl: String? = null,
) {
    fun toJson() = JsonObject(
        mapOf(
            "title" to JsonPrimitive(title),
            "artist" to JsonPrimitive(artist),
            "durationSec" to JsonPrimitive(durationSec),
            "spotifyUrl" to JsonPrimitive(spotifyUrl),
            "appleUrl" to JsonPrimitive(appleUrl),
        ),
    )

    companion object {
        fun fromJson(j: JsonObject) = ExportRow(
            title = j.getValue("title").jsonPrimitive.content,
            artist = j.getValue("artist").jsonPrimitive.content,
            durationSec = j["durationSec"]?.jsonPrimitive?.intOrNull ?: 0,
            spotifyUrl = j["spotifyUrl"]?.jsonPrimitive?.contentOrNull,
            appleUrl = j["appleUrl"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

/** A temporary list of songs with their links, kept on this device until it is deleted. */
data class ExportList(val id: String, val name: String, val createdAt: Long, val rows: List<ExportRow>) {
    val withApple: Int get() = rows.count { it.appleUrl != null }
    val withSpotify: Int get() = rows.count { it.spotifyUrl != null }

    fun toJson() = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "name" to JsonPrimitive(name),
            "createdAt" to JsonPrimitive(createdAt),
            "rows" to JsonArray(rows.map { it.toJson() }),
        ),
    )

    companion object {
        fun fromJson(j: JsonObject) = ExportList(
            id = j.getValue("id").jsonPrimitive.content,
            name = j.getValue("name").jsonPrimitive.content,
            createdAt = j.getValue("createdAt").jsonPrimitive.long,
            rows = j.getValue("rows").jsonArray.map { ExportRow.fromJson(it.jsonObject) },
        )
    }
}

const val EXPORT_LISTS_KEY = "export_lists"

fun loadExportLists(repo: LibraryRepo): List<ExportList> {
    val raw = repo.pref(EXPORT_LISTS_KEY)
    if (raw.isNullOrEmpty()) return emptyList()
    return try {
        Json.parseToJsonElement(raw).jsonArray.map { ExportList.fromJson(it.jsonObject) }
    } catch (_: Exception) {
        emptyList() // Damaged: they are temporary anyway.
    }
}

fun saveExportLists(repo: LibraryRepo, lists: List<ExportList>) =
    repo.setPref(EXPORT_LISTS_KEY, JsonArray(lists.map { it.toJson() }).toString())

fun deleteExportList(repo: LibraryRepo, id: String) =
    saveExportLists(repo, loadExportLists(repo).filter { it.id != id })

/** A Spotify link from the source's track id (`spotify:track:ID`), if it is one. */
fun spotifyLinkOf(trackKey: String): String? {
    val parts = trackKey.split(':')
    return if (parts.size == 3 && parts[0] == "spotify" && parts[1] == "track" && parts[2].isNotEmpty()) {
        "https://open.spotify.com/track/${parts[2]}"
    } else {
        null
    }
}

private const val APPLE_PAUSE_MS = 3200L

/**
 * Looks a song up in Apple Music through Apple's free search and returns the link of the best
 * match: same title, the artist inside the result's, length within 5 seconds. Null if nothing
 * fits. Tries the US catalogue, then India's.
 */
suspend fun appleLinkFor(
    http: HttpFetcher,
    title: String,
    artist: String,
    durationSec: Int,
    pauseMs: Long = APPLE_PAUSE_MS,
): String? {
    val wantTitle = comparableText(searchTitle(cleanTitle(title, artist)))
    val wantArtist = if (artist == "Unknown artist") "" else comparableText(firstArtist(artist))
    if (wantTitle.isEmpty()) return null
    val term = "${searchTitle(cleanTitle(title, artist))} ${firstArtist(artist)}".trim()
    for (country in listOf<String?>(null, "IN")) {
        // Apple allows about 20 searches a minute.
        if (pauseMs > 0) delay(pauseMs)
        val params = buildMap {
            put("term", term); put("media", "music"); put("entity", "song"); put("limit", "10")
            if (country != null) put("country", country)
        }
        val res = http.send(HttpRequest(urlWith("https://itunes.apple.com/search", params)))
        if (res.status != 200) continue
        val results = Json.parseToJsonElement(res.text).jsonObject["results"] as? JsonArray ?: continue
        for (r in results.map { it.jsonObject }) {
            val length = ((r["trackTimeMillis"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0) / 1000).toInt()
            if (durationSec > 0 && length > 0 && kotlin.math.abs(length - durationSec) > 5) continue
            val name = r["trackName"]?.jsonPrimitive?.contentOrNull ?: ""
            if (comparableText(searchTitle(name)) != wantTitle) continue
            val by = r["artistName"]?.jsonPrimitive?.contentOrNull ?: ""
            if (wantArtist.isNotEmpty() && wantArtist !in comparableText(by)) continue
            val url = r["trackViewUrl"]?.jsonPrimitive?.contentOrNull
            if (url != null) return url
        }
    }
    return null
}

/**
 * Builds a list for [tracks]: Spotify links straight from their ids, Apple Music ones by search,
 * one at a time. [onProgress] is told how many are done; [cancelled] stops early and keeps what
 * was found.
 */
suspend fun buildExportList(
    tracks: List<MissingTrack>,
    name: String,
    http: HttpFetcher,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    cancelled: () -> Boolean = { false },
    pauseMs: Long = APPLE_PAUSE_MS,
): ExportList {
    val rows = mutableListOf<ExportRow>()
    for (t in tracks) {
        if (cancelled()) break
        val apple = try {
            appleLinkFor(http, t.title, t.artist, t.duration_sec.toInt(), pauseMs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // Offline or refused: the row still has its Spotify link.
        }
        rows += ExportRow(t.title, t.artist, t.duration_sec.toInt(), spotifyLinkOf(t.track_key), apple)
        onProgress(rows.size, tracks.size)
    }
    val now = Clock.System.now().toEpochMilliseconds()
    return ExportList(id = "${now * 1000}", name = name, createdAt = now, rows = rows)
}

private fun csv(v: String?) = "\"${(v ?: "").replace("\"", "\"\"")}\""

/** The list as a spreadsheet file: title, artist, length, Spotify link, Apple Music link. */
fun exportCsv(list: ExportList): String = (
    listOf("title,artist,seconds,spotify,apple_music") +
        list.rows.map { listOf(csv(it.title), csv(it.artist), it.durationSec, csv(it.spotifyUrl), csv(it.appleUrl)).joinToString(",") }
    ).joinToString("\n")

/** Every link, one per line, to paste into a playlist importer or a message. */
fun exportLinks(list: ExportList, spotify: Boolean = true, apple: Boolean = true): String =
    list.rows.flatMap { listOfNotNull(if (spotify) it.spotifyUrl else null, if (apple) it.appleUrl else null) }
        .joinToString("\n")
