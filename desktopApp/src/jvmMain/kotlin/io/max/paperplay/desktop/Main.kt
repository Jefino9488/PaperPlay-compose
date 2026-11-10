package io.max.paperplay.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.max.paperplay.core.app.AppServices
import io.max.paperplay.core.backup.BackupRestorer
import io.max.paperplay.core.backup.RestoreOptions
import io.max.paperplay.core.backup.desktopRestoreTarget
import io.max.paperplay.core.backup.fileBackupSource
import io.max.paperplay.ui.App
import io.max.paperplay.ui.AppEnv
import io.max.paperplay.ui.RestoreHooks
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Where tdjson.dll (and the libraries beside it) are found: an override, a `native` folder next to the app, or the dev copy. */
private fun tdlibDir(): String? = listOfNotNull(
    System.getenv("PAPERPLAY_TDLIB"),
    File(System.getProperty("compose.application.resources.dir") ?: ".", "native").path,
    File("native").path,
    "D:/Teleplayer/windows/third_party/tdlib",
).firstOrNull { File(it, if (System.getProperty("os.name").startsWith("Windows")) "tdjson.dll" else "libtdjson.so").exists() }

fun main() {
    tdlibDir()?.let { System.setProperty("jna.library.path", it) }
    application {
        val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PaperPlay")
        val target = desktopRestoreTarget(dir)
        val env = AppEnv(
            restore = RestoreHooks(
                pickBackup = { onPicked ->
                    val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("Backup (*.zip)", "zip") }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) onPicked(fileBackupSource(chooser.selectedFile.path))
                },
                restore = { source, pw -> BackupRestorer().restore(source, target, RestoreOptions(password = pw)) },
            ),
            libraryExists = { File(dir, "library.sqlite").exists() },
            createServices = { AppServices(DesktopPlatform(dir)) },
        )
        Window(onCloseRequest = ::exitApplication, title = "Paper Play", state = rememberWindowState(width = 1000.dp, height = 760.dp)) {
            App(env)
        }
    }
}
