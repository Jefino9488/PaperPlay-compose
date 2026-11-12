package io.max.paperplay.core.app

import app.cash.sqldelight.db.SqlDriver
import io.max.paperplay.core.telegram.TdNative
import io.max.paperplay.player.PlayerEngine
import kotlinx.coroutines.CoroutineDispatcher

/** Keystore-backed storage for the bot token and database key. Values must never be logged. */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Plain app settings (API id and hash, ...). */
interface Settings {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** Everything that differs between Android and desktop. */
interface Platform {
    /** Writable app folder; TDLib state and the library live under it. */
    val dataDir: String
    val secrets: SecretStore
    val settings: Settings
    val native: TdNative

    /** A thread that may block in td_receive. */
    val receiveDispatcher: CoroutineDispatcher

    /** Opens (creating or upgrading) the library file at [path]. */
    fun openDriver(path: String): SqlDriver
    fun createPlayer(): PlayerEngine
    val deviceModel: String get() = "Android"
    val appVersion: String get() = "0.1.0"

    /** False when heavy background work should wait: app hidden and on battery, or on a metered network. */
    fun canRunBackground(): Boolean = true
}

/** Keys used in [SecretStore] and [Settings]. */
object Keys {
    const val BOT_TOKEN = "bot_token"
    const val TD_KEY = "td_key"
    const val API_ID = "api_id"
    const val API_HASH = "api_hash"
}

/** Reads up to [length] bytes of the file at [path] from [offset]; fewer only at end of file. */
internal expect fun readRange(path: String, offset: Long, length: Int): ByteArray

/** Size of the file at [path] in bytes, or -1 when it is not a readable file. */
internal expect fun fileLength(path: String): Long

internal expect fun deleteFile(path: String)

/** Full paths of the files directly in [dir], not its subfolders. Empty when it cannot be read. */
internal expect fun listFiles(dir: String): List<String>
