package io.max.paperplay.core.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import io.max.paperplay.core.db.LibraryDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val VERSION = 1
private const val MANIFEST = "library.json"
private const val CREDENTIALS = "credentials.enc"
private const val USER_SESSION_ENTRY = "usersession.enc"
private const val COVERS = "covers"
private const val PROFILE = "profile"
private const val MAX_COVER = 20L * 1024 * 1024
private const val MAX_MANIFEST = 96L * 1024 * 1024
private const val MAX_CREDENTIALS = 1L * 1024 * 1024
private val safeName = Regex("^[\\w.-]+$")
private val camel = Regex("([A-Z])")

/**
 * Restores a Flutter-app (Teleplayer) backup zip. Nothing is touched until the zip has been read and the new
 * library built next to the old one; then library, covers and profile folders are swapped in with a rollback.
 * Songs come back as not downloaded (the zip holds no audio).
 */
class BackupRestorer {
    /** Emits [RestoreEvent.Progress] and ends with [RestoreEvent.Finished]. Fails with [BackupException] or [WrongPassword]. */
    fun restore(source: BackupSource, target: RestoreTarget, options: RestoreOptions = RestoreOptions()): Flow<RestoreEvent> =
        channelFlow {
            val staging = target.libraryPath + ".restoring"
            try {
                send(RestoreEvent.Finished(run(source, target, options, staging)))
            } finally {
                Fs.deleteRecursively(staging)
            }
        }.flowOn(Dispatchers.Default)

    private class Scanned(
        var manifest: ByteArray? = null,
        var credentials: ByteArray? = null,
        /** The sealed account session was in the backup. It is streamed to [sessionFile] because it can be hundreds of MB. */
        var sessionPresent: Boolean = false,
        var sessionFile: String? = null,
        val covers: MutableSet<String> = mutableSetOf(),
        var profile: String? = null,
        val unknown: MutableList<String> = mutableListOf(),
        var tooBig: Int = 0,
    )

    private fun ProducerScope<RestoreEvent>.progress(stage: String, fraction: Double?) {
        trySend(RestoreEvent.Progress(stage, fraction))
    }

    private fun ProducerScope<RestoreEvent>.run(
        source: BackupSource,
        target: RestoreTarget,
        options: RestoreOptions,
        staging: String,
    ): RestoreResult {
        Fs.deleteRecursively(staging)
        Fs.mkdirs("$staging/$COVERS")
        progress("Reading the backup", 0.02)
        val scan = Scanned()
        try {
            source.scan { e ->
                if (e.isDirectory) return@scan
                val parts = e.name.split('/')
                when {
                    e.name == MANIFEST -> scan.manifest = e.readBytes(MAX_MANIFEST) ?: throw BackupException("This backup is damaged.")
                    e.name == CREDENTIALS -> scan.credentials = e.readBytes(MAX_CREDENTIALS)
                    e.name == USER_SESSION_ENTRY -> {
                        scan.sessionPresent = true
                        if (target.userSessionDir != null) {
                            progress("Reading the account session", 0.03)
                            e.copyTo("$staging/session.enc")
                            scan.sessionFile = "$staging/session.enc"
                        }
                    }
                    parts.size == 2 && parts[0] == COVERS && safeName.matches(parts[1]) -> {
                        val bytes = e.readBytes(MAX_COVER)
                        if (bytes == null) scan.tooBig++ else {
                            Fs.write("$staging/$COVERS/${parts[1]}", bytes)
                            scan.covers += parts[1]
                        }
                    }
                    parts.size == 2 && parts[0] == PROFILE && safeName.matches(parts[1]) -> {
                        val bytes = e.readBytes(MAX_COVER)
                        if (bytes == null) scan.tooBig++
                        else if (options.applyLayout && scan.profile == null) {
                            Fs.write("$staging/$PROFILE/${parts[1]}", bytes)
                            scan.profile = parts[1]
                        }
                    }
                    else -> scan.unknown += e.name
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw BackupException("This backup file can't be read. It may be damaged.")
        }

        val manifest = parseManifest(scan.manifest ?: throw BackupException("This isn't a PaperPlay backup."))

        // Sealed data is opened first, so a wrong password stops the restore before anything changes.
        val pw = options.password
        val creds: JsonObject? = scan.credentials?.let { if (pw == null) null else parseCredentials(it, pw) }
        // The account session is far too big to hold in memory: it is decrypted file to file and unpacked on disk.
        // It is optional, so a session that can't be opened costs only itself, never the library.
        var sessionKey: ByteArray? = null
        val sessionFile = scan.sessionFile
        if (sessionFile != null && pw != null) {
            progress("Opening the account session", 0.06)
            sessionKey = try {
                openSealedFile(sessionFile, pw, "$staging/session.zip")
                Fs.deleteRecursively(sessionFile)
                extractSessionZip("$staging/session.zip", "$staging/user_td").also { Fs.deleteRecursively("$staging/session.zip") }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Fs.deleteRecursively("$staging/user_td")
                null
            }
        }

        progress("Building the library", 0.1)
        val newDb = target.libraryPath + ".new"
        removeDb(newDb)
        val songCount: Int
        try {
            songCount = buildLibrary(manifest, scan, target, options, newDb)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            removeDb(newDb)
            throw BackupException("This backup is damaged and was not restored.")
        }

        progress("Putting it in place", 0.93)
        val moves = mutableListOf(newDb to target.libraryPath)
        moves += "$staging/$COVERS" to target.coversDir
        if (scan.profile != null) moves += "$staging/$PROFILE" to target.profileDir
        if (sessionKey != null && target.userSessionDir != null) moves += "$staging/user_td" to target.userSessionDir
        try {
            swap(moves, target.libraryPath)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            removeDb(newDb)
            throw BackupException("The restored library could not be put in place. Your previous library was kept.")
        }

        val items = mutableListOf<RestoreItem>()
        items += RestoreItem("Library", Outcome.Restored, "$songCount songs, as not downloaded")
        items += RestoreItem("Covers", Outcome.Restored, "${scan.covers.size} covers")
        if (scan.tooBig > 0) items += RestoreItem("Oversized files", Outcome.Skipped, "${scan.tooBig} skipped")
        items += when {
            !options.applyLayout -> RestoreItem("Layout and settings", Outcome.Skipped, "not requested")
            manifest.layout.isEmpty() -> RestoreItem("Layout and settings", Outcome.Skipped, "the backup has none")
            else -> RestoreItem("Layout and settings", Outcome.Restored)
        }
        items += if (scan.profile != null) RestoreItem("Profile picture", Outcome.Restored)
        else RestoreItem("Profile picture", Outcome.Skipped, "none in the backup")

        progress("Saving credentials", 0.97)
        items += storeSecrets(target.secrets, scan, creds)
        if (scan.sessionPresent) {
            items += when {
                target.userSessionDir == null -> RestoreItem("Account session", Outcome.Skipped, "not supported on this device")
                pw == null -> RestoreItem("Account session", Outcome.Skipped, "needs the backup password")
                sessionKey == null -> RestoreItem("Account session", Outcome.Skipped, "could not be opened; sign in again later")
                else -> try {
                    target.secrets.put(SecretKeys.USER_SESSION_KEY, sessionKey)
                    RestoreItem("Account session", Outcome.Restored)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    RestoreItem("Account session", Outcome.Failed, "could not be saved")
                }
            }
        }
        if (scan.unknown.isNotEmpty()) items += RestoreItem("Unrecognized entries", Outcome.Skipped, "${scan.unknown.size} not understood")
        progress("Done", 1.0)
        return RestoreResult(songCount, items)
    }

    private class Manifest(val json: JsonObject, val layout: Map<String, String>) {
        fun rows(key: String): JsonArray? = json[key]?.takeIf { it !is JsonNull }?.jsonArray
    }

    private fun parseManifest(bytes: ByteArray): Manifest {
        val json = try {
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (_: Exception) {
            throw BackupException("This backup is damaged.")
        }
        if (json["version"]?.jsonPrimitive?.intOrNull != VERSION) {
            throw BackupException("This backup is from a different version of PaperPlay.")
        }
        try {
            for (k in listOf("chats", "songs", "playlists", "playlistSongs", "prefs")) json.getValue(k).jsonArray
            for (k in listOf("missingTracks", "moodVotes", "forumTopics", "postCopies")) json[k]?.let { if (it !is JsonNull) it.jsonArray }
        } catch (_: Exception) {
            throw BackupException("This backup is damaged.")
        }
        val layout = try {
            (json["layout"] as? JsonObject).orEmpty()
                .filterKeys { it in layoutPrefs } // nothing but known settings, whatever the file says
                .mapValues { it.value.jsonPrimitive.content }
        } catch (_: Exception) {
            throw BackupException("This backup is damaged.")
        }
        return Manifest(json, layout)
    }

    private fun parseCredentials(sealed: ByteArray, password: String): JsonObject {
        val plain = try {
            openSealed(sealed.decodeToString(), password)
        } catch (_: IllegalArgumentException) {
            throw BackupException("The credentials in this backup are damaged.")
        }
        return try {
            Json.parseToJsonElement(plain.decodeToString()).jsonObject
        } catch (_: Exception) {
            throw BackupException("The credentials in this backup are damaged.")
        }
    }

    private fun storeSecrets(store: SecretStore, scan: Scanned, creds: JsonObject?): List<RestoreItem> {
        val out = mutableListOf<RestoreItem>()
        fun item(name: String, present: Boolean, block: () -> Unit) {
            if (!present) return
            out += try {
                block()
                RestoreItem(name, Outcome.Restored)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                RestoreItem(name, Outcome.Failed, "could not be saved") // no exception text: it might hold a value
            }
        }
        fun str(k: String) = (creds?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        fun put(k: String, v: String) = store.put(k, v.encodeToByteArray())
        if (scan.credentials != null && creds == null) {
            out += listOf("Bot credentials", "Extra bots", "Spotify", "ListenBrainz")
                .map { RestoreItem(it, Outcome.Skipped, "needs the backup password") }
        } else {
            item("Bot credentials", creds != null) {
                put(SecretKeys.API_ID, creds!!.getValue("apiId").jsonPrimitive.content)
                put(SecretKeys.API_HASH, creds.getValue("apiHash").jsonPrimitive.content)
                put(SecretKeys.BOT_TOKEN, creds.getValue("botToken").jsonPrimitive.content)
            }
            item("Extra bots", creds?.get("extraBots") != null) { put("restoredExtraBots", creds!!.getValue("extraBots").toString()) }
            item("Spotify", str("spotifyRefreshToken") != null) {
                put(SecretKeys.SPOTIFY_REFRESH_TOKEN, str("spotifyRefreshToken")!!)
                str("spotifyClientId")?.let { put(SecretKeys.SPOTIFY_CLIENT_ID, it) }
            }
            item("ListenBrainz", str("listenBrainzToken") != null) { put(SecretKeys.LISTENBRAINZ_TOKEN, str("listenBrainzToken")!!) }
        }
        return out
    }

    // ---- library --------------------------------------------------------------------------------------------

    private fun ProducerScope<RestoreEvent>.buildLibrary(m: Manifest, scan: Scanned, target: RestoreTarget, options: RestoreOptions, path: String): Int {
        val ownPrefs = readDevicePrefs(target)
        val driver = target.openDriver(path)
        try {
            val songs = m.rows("songs")!!
            LibraryDatabase(driver).transaction {
                val cols = Columns(driver)
                fun ins(table: String, rows: JsonArray?) = rows?.forEach { insertRow(driver, cols, table, it.jsonObject) {} }
                // Prefs: the backup's, minus this device's own; then this device's own, unless the layout replaces them.
                val prefs = linkedMapOf<String, String>()
                val backupPrefs = m.rows("prefs")!!.associate {
                    it.jsonObject.getValue("key").jsonPrimitive.content to it.jsonObject.getValue("value").jsonPrimitive.content
                }
                for ((k, v) in backupPrefs) if (k !in devicePrefs) prefs[k] = v
                for ((k, v) in ownPrefs) if (!(options.applyLayout && k in m.layout)) prefs[k] = v
                if (options.applyLayout) prefs.putAll(m.layout)
                scan.profile?.let { prefs["profile_photo"] = "${target.profileDir}/$it" }
                // Topics already read in the backup stay read; backups without topics have to read them again.
                if (m.rows("forumTopics") != null && m.rows("postCopies") != null) backupPrefs["topics_read"]?.let { prefs["topics_read"] = it }
                else prefs.remove("topics_read")
                for ((k, v) in prefs) {
                    driver.execute(null, "INSERT OR REPLACE INTO prefs (key, value) VALUES (?, ?)", 2) {
                        bindString(0, k); bindString(1, v)
                    }
                }
                progress("Restoring songs", 0.15)
                ins("library_chats", m.rows("chats"))
                var done = 0
                for (s in songs) {
                    if (++done % 100 == 0) progress("Restoring songs: $done of ${songs.size}", 0.15 + 0.75 * done / songs.size)
                    insertRow(driver, cols, "songs", s.jsonObject) { row ->
                        // Cover paths include the old install's folder, so only the name is kept.
                        val name = (row["coverPath"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                            ?.substringAfterLast('/')?.substringAfterLast('\\')
                        row["cachedBytes"] = JsonPrimitive(0)
                        if (name != null && name in scan.covers) row["coverPath"] = JsonPrimitive("${target.coversDir}/$name")
                        else row.remove("coverPath")
                    }
                }
                ins("playlists", m.rows("playlists"))
                ins("playlist_songs", m.rows("playlistSongs"))
                ins("missing_tracks", m.rows("missingTracks"))
                ins("mood_votes", m.rows("moodVotes"))
                ins("forum_topics", m.rows("forumTopics"))
                ins("post_copies", m.rows("postCopies"))
                // Readies rows from backups that predate sync (Flutter prepareOlderRows).
                driver.execute(null, "UPDATE songs SET tags_at = 1 WHERE tags_at = 0 AND (user_edited = 1 OR enrich_state <> 0)", 0)
                driver.execute(null, "UPDATE songs SET favorite_at = 1 WHERE favorite_at = 0 AND favorite = 1", 0)
                driver.execute(null, "UPDATE playlists SET uuid = 'legacy-' || id || '-' || name WHERE uuid = ''", 0)
            }
            return songs.size
        } finally {
            driver.close()
        }
    }

    /** This device's own prefs from the library about to be replaced; empty if there is none or it can't be read. */
    private fun readDevicePrefs(target: RestoreTarget): Map<String, String> {
        if (!Fs.exists(target.libraryPath)) return emptyMap()
        return try {
            val d = target.openDriver(target.libraryPath)
            try {
                d.executeQuery(null, "SELECT key, value FROM prefs", { c ->
                    val out = linkedMapOf<String, String>()
                    while (c.next().value) {
                        val k = c.getString(0)!!
                        if (k in devicePrefs) out[k] = c.getString(1)!!
                    }
                    QueryResult.Value<Map<String, String>>(out)
                }, 0).value
            } finally {
                d.close()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private class Columns(private val driver: SqlDriver) {
        private val cache = mutableMapOf<String, Map<String, Boolean>>() // column -> is integer
        operator fun get(table: String) = cache.getOrPut(table) {
            driver.executeQuery(null, "PRAGMA table_info($table)", { c ->
                val out = linkedMapOf<String, Boolean>()
                while (c.next().value) out[c.getString(1)!!] = c.getString(2)!!.uppercase().startsWith("INT")
                QueryResult.Value<Map<String, Boolean>>(out)
            }, 0).value
        }
    }

    /**
     * Inserts one Drift `toJson` row: camelCase keys become snake_case columns; keys the schema doesn't have are
     * ignored, and null or missing ones take the column default (older backups lack newer columns).
     */
    private fun insertRow(driver: SqlDriver, cols: Columns, table: String, json: JsonObject, fix: (MutableMap<String, JsonPrimitive>) -> Unit) {
        val row = json.mapValues { it.value as? JsonPrimitive ?: throw IllegalArgumentException("bad row") }.toMutableMap()
        fix(row)
        val known = cols[table]
        val entries = row.entries
            .map { camel.replace(it.key) { m -> "_" + m.value.lowercase() } to it.value }
            .filter { (c, v) -> c in known && v !is JsonNull }
        val names = entries.joinToString(",") { "\"${it.first}\"" }
        driver.execute(null, "INSERT INTO $table ($names) VALUES (${entries.joinToString(",") { "?" }})", entries.size) {
            entries.forEachIndexed { i, (c, v) ->
                if (known.getValue(c)) {
                    val n = v.booleanOrNull?.let { if (it) 1L else 0L } ?: v.longOrNull ?: v.doubleOrNull?.toLong()
                        ?: throw IllegalArgumentException("bad value")
                    bindLong(i, n)
                } else bindString(i, v.content)
            }
        }
    }

    // ---- swap -----------------------------------------------------------------------------------------------

    private fun removeDb(path: String) {
        for (s in listOf("", "-journal", "-wal", "-shm")) Fs.deleteRecursively(path + s)
    }

    /** Moves each staged item over its final place, keeping the old one aside; any failure puts everything back. */
    private fun swap(moves: List<Pair<String, String>>, libraryPath: String) {
        val done = mutableListOf<Pair<String, Boolean>>() // final path, had an old one
        try {
            for ((staged, final) in moves) {
                val old = "$final.old"
                Fs.deleteRecursively(old)
                val had = Fs.exists(final)
                if (final == libraryPath) for (s in listOf("-journal", "-wal", "-shm")) Fs.deleteRecursively(final + s)
                if (had) Fs.rename(final, old)
                done += final to had
                final.substring(0, final.lastIndexOfAny(charArrayOf('/', 92.toChar())).coerceAtLeast(0)).takeIf { it.isNotEmpty() }?.let { Fs.mkdirs(it) }
                Fs.rename(staged, final)
            }
        } catch (e: Exception) {
            for ((final, had) in done.asReversed()) {
                Fs.deleteRecursively(final)
                if (had) Fs.rename("$final.old", final)
            }
            throw e
        }
        for ((final, _) in done) Fs.deleteRecursively("$final.old")
    }
}

