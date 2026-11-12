package io.max.paperplay.core.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A music folder the owner chose. Only the songs directly in it count; a subfolder is its own entry or is left alone. */
data class LocalFolder(val path: String, val sync: Boolean)

/** Songs found by a scan of one folder. */
data class FolderScan(val path: String, val songs: List<String>, val fresh: Int)

/**
 * Folders on this device that the owner picked, one by one. Nothing outside them is ever read, and nothing inside
 * their subfolders either (call recordings and the like), unless that subfolder was added too. A folder with
 * [LocalFolder.sync] on sends songs that are new to Telegram, through the upload queue, to the main chat.
 *
 * Stored in the library prefs (`local_folders`, `local_seen`) so it follows the library, not the app install.
 */
class LocalFolders(
    private val pref: (String) -> String?,
    private val setPref: (String, String) -> Unit,
    private val uploader: Uploader,
    private val mainChat: () -> Long?,
    private val mainTitle: () -> String,
) {
    fun list(): List<LocalFolder> = runCatching {
        Json.parseToJsonElement(pref(FOLDERS) ?: "[]").jsonArray.map {
            val o = it.jsonObject
            LocalFolder(o.getValue("path").jsonPrimitive.content, o["sync"]?.jsonPrimitive?.booleanOrNull == true)
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<LocalFolder>) = setPref(
        FOLDERS,
        JsonArray(list.map { JsonObject(mapOf("path" to JsonPrimitive(it.path), "sync" to JsonPrimitive(it.sync))) }).toString(),
    )

    /** Adds [path]. Syncing starts off: the owner turns it on per folder. Returns false when it was there already. */
    fun add(path: String): Boolean {
        val clean = path.trim().trimEnd('/', '\\')
        if (clean.isEmpty()) return false
        val now = list()
        if (now.any { it.path.equals(clean, ignoreCase = true) }) return false
        save(now + LocalFolder(clean, sync = false))
        return true
    }

    fun remove(path: String) = save(list().filter { it.path != path })

    fun setSync(path: String, on: Boolean) = save(list().map { if (it.path == path) it.copy(sync = on) else it })

    private fun seen(): Set<String> = runCatching {
        Json.parseToJsonElement(pref(SEEN) ?: "[]").jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
    }.getOrDefault(emptySet())

    private fun keyOf(path: String) = "$path|${fileLength(path)}"

    /** Audio files directly in [folder], not in its subfolders. */
    fun songsIn(folder: String): List<String> = listFiles(folder)
        .filter { it.substringAfterLast('.', "").lowercase() in audioExtensions && fileLength(it) > 0 }
        .sorted()

    /** What is in each folder and how many songs were not sent yet. Reads names only, no file contents. */
    fun scan(): List<FolderScan> {
        val seen = seen()
        return list().map { f ->
            val songs = songsIn(f.path)
            FolderScan(f.path, songs, songs.count { keyOf(it) !in seen })
        }
    }

    /**
     * Queues the new songs of every folder with sync on. Returns how many were queued, or -1 when there is no main
     * chat to send them to. Songs already in the library (same name and size) are skipped by the upload queue.
     */
    fun syncNow(): Int {
        val chat = mainChat() ?: return -1
        val seen = seen().toMutableSet()
        var queued = 0
        for (f in list().filter { it.sync }) {
            val fresh = songsIn(f.path).filter { keyOf(it) !in seen }
            if (fresh.isEmpty()) continue
            queued += uploader.enqueue(fresh, chat, mainTitle()).queued
            fresh.forEach { seen += keyOf(it) }
        }
        setPref(SEEN, JsonArray(seen.map { JsonPrimitive(it) }).toString())
        return queued
    }

    private companion object {
        const val FOLDERS = "local_folders"
        const val SEEN = "local_seen"
    }
}
