package io.max.paperplay.desktop

import app.cash.sqldelight.db.SqlDriver
import io.max.paperplay.core.app.BackedSecrets
import io.max.paperplay.core.app.BackedSettings
import io.max.paperplay.core.app.Platform
import io.max.paperplay.core.app.SecretStore
import io.max.paperplay.core.app.Settings
import io.max.paperplay.core.backup.desktopSecretStore
import io.max.paperplay.core.db.openLibraryDriver
import io.max.paperplay.core.telegram.TdJsonNative
import io.max.paperplay.core.telegram.TdNative
import io.max.paperplay.player.PlayerEngine
import io.max.paperplay.player.createPlayerEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/** Library, TDLib state and secrets under [dir] (%APPDATA%\PaperPlay on Windows). */
class DesktopPlatform(dir: File) : Platform {
    private val store = desktopSecretStore(File(dir, "secrets"))

    override val dataDir: String = dir.path.replace(File.separatorChar, '/')
    override val secrets: SecretStore = BackedSecrets(store)
    override val settings: Settings = BackedSettings(store)
    override val native: TdNative = TdJsonNative
    override val receiveDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "td-receive").apply { isDaemon = true } }.asCoroutineDispatcher()

    override fun openDriver(path: String): SqlDriver = openLibraryDriver(path)
    override fun createPlayer(): PlayerEngine = createPlayerEngine()
    override val deviceModel: String get() = System.getProperty("os.name") ?: "Desktop"
    override val appVersion: String get() = "0.1.0"
}
