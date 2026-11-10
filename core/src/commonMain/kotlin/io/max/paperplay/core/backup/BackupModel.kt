package io.max.paperplay.core.backup

import app.cash.sqldelight.db.SqlDriver

/**
 * App layout and settings a backup carries (Flutter `layoutPrefs`). Applied only when
 * [RestoreOptions.applyLayout] is set; nothing outside this list is ever applied from `layout`.
 */
val layoutPrefs = listOf(
    "theme_mode", "theme_amoled", "theme_accent", "cover_gradient", "now_playing_panel", "floating_nav",
    "nav_radius", "nav_style", "glass_style", "profile_name", "sort_songs", "sort_reverse", "sort_playlist",
    "norm_mode", "norm_preamp", "eq_on", "eq_gains", "rate_filing", "rate_uploads", "rate_reading",
    "rate_lookups", "rate_covers", "rate_accountCopy", "upload_slower", "upload_parallel", "upload_ask",
    "upload_skip_known", "keep_running", "listenbrainz_on", "weather_city", "weather_lat", "weather_lon",
)

/** Prefs tied to one device (Flutter `AppDb.devicePrefs`): kept from this device, never taken from a backup. */
val devicePrefs = listOf(
    "device_id", "listenbrainz_on", "last_session", "sort_songs", "sort_reverse", "sort_playlist", "norm_mode",
    "norm_preamp", "eq_on", "eq_gains", "listenbrainz_queue", "rate_filing", "rate_uploads", "rate_reading",
    "rate_lookups", "rate_covers", "rate_accountCopy", "lan_addresses", "lan_discovery", "theme_mode",
    "theme_amoled", "theme_accent", "now_playing_panel", "audio_device", "weather_city", "weather_lat",
    "weather_lon", "spotify_client_id", "upload_slower", "upload_parallel", "uploaded_files", "upload_skip_known",
    "topics_read", "floating_nav", "nav_radius", "nav_style", "glass_style", "profile_name", "profile_photo",
    "profile_name_at", "profile_photo_at", "file_here", "file_existing", "upload_ask", "keep_running", "export_lists",
)

/** One file in the backup zip, read once, in zip order. */
interface BackupEntry {
    val name: String
    val isDirectory: Boolean

    /** The whole entry, or null if it is larger than [limit] bytes. */
    fun readBytes(limit: Long): ByteArray?

    /** Streams the whole entry into the file at [path] without holding it in memory; returns its size in bytes. */
    fun copyTo(path: String): Long
}

/** Where a backup zip comes from. [scan] calls [visit] for each entry, one pass. */
fun interface BackupSource {
    fun scan(visit: (BackupEntry) -> Unit)
}

/** Holds credentials and tokens. Values must never be logged. */
interface SecretStore {
    fun put(key: String, value: ByteArray)
    fun get(key: String): ByteArray?
    fun remove(key: String)
}

class RestoreTarget(
    /** The library database file. Replaced as a whole on success. */
    val libraryPath: String,
    val coversDir: String,
    val profileDir: String,
    val secrets: SecretStore,
    /** Where the account's TDLib folder from the backup goes; null leaves the account session out. */
    val userSessionDir: String? = null,
    /** Opens (creating or upgrading) a library database at a path; used on a temp file and on the old library. */
    val openDriver: (path: String) -> SqlDriver,
)

class RestoreOptions(
    /** The password the backup's credentials and account session were sealed with; without it they are skipped. */
    val password: String? = null,
    val applyLayout: Boolean = true,
)

/** A restore that changed nothing. [message] is safe to show: it never holds backup contents. */
class BackupException(message: String) : Exception(message)

/** The password did not open the sealed data. */
class WrongPassword : Exception("Wrong password")

enum class Outcome { Restored, Skipped, Failed }

class RestoreItem(val name: String, val outcome: Outcome, val note: String = "")

class RestoreResult(val songs: Int, val items: List<RestoreItem>) {
    fun of(outcome: Outcome) = items.filter { it.outcome == outcome }
}

sealed interface RestoreEvent {
    class Progress(val stage: String, val fraction: Double?) : RestoreEvent
    class Finished(val result: RestoreResult) : RestoreEvent
}

/** Secret keys in a [SecretStore]. */
object SecretKeys {
    const val API_ID = "apiId"
    const val API_HASH = "apiHash"
    const val BOT_TOKEN = "botToken"
    /** JSON array of strings. */
    const val EXTRA_BOTS = "extraBots"
    const val SPOTIFY_CLIENT_ID = "spotifyClientId"
    const val SPOTIFY_REFRESH_TOKEN = "spotifyRefreshToken"
    const val LISTENBRAINZ_TOKEN = "listenBrainzToken"
    /** Flutter's zip of the account's TDLib folder plus a `__key` entry holding its database key. */
    const val USER_SESSION = "usersession"

    /** The database key of the restored account folder (the `__key` entry of the session zip). */
    const val USER_SESSION_KEY = "usersessionKey"
}
