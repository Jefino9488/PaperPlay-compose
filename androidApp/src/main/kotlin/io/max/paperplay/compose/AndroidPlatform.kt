package io.max.paperplay.compose

import android.content.Context
import android.os.Build
import app.cash.sqldelight.db.SqlDriver
import io.max.paperplay.core.app.BackedSecrets
import io.max.paperplay.core.app.BackedSettings
import io.max.paperplay.core.app.Platform
import io.max.paperplay.core.app.SecretStore
import io.max.paperplay.core.app.Settings
import io.max.paperplay.core.backup.androidSecretStore
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.core.telegram.TdJsonNative
import io.max.paperplay.core.telegram.TdNative
import io.max.paperplay.player.PlayerEngine
import io.max.paperplay.player.createPlayerEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/** Library, TDLib state and secrets all live under the app's private files folder. */
class AndroidPlatform(private val context: Context) : Platform {
    private val store = androidSecretStore(File(context.filesDir, "secrets"))

    override val dataDir: String = context.filesDir.path.replace(File.separatorChar, '/')
    override val secrets: SecretStore = BackedSecrets(store)
    override val settings: Settings = BackedSettings(store)
    override val native: TdNative = TdJsonNative
    override val receiveDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "td-receive").apply { isDaemon = true } }.asCoroutineDispatcher()

    override fun openDriver(path: String): SqlDriver = libraryDriver(context, path)
    override fun createPlayer(): PlayerEngine = createPlayerEngine()
    override val deviceModel: String get() = Build.MODEL
    override val appVersion: String get() = "0.1.0"
}
