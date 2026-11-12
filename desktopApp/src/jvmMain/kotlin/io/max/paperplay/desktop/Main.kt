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

/**
 * Where tdjson.dll (and the libraries beside it) are found.
 *
 * Priority: PAPERPLAY_TDLIB env var → `native/` next to the app resources →
 * `native/` in the working directory → dev copy inside the Flutter tree.
 *
 * VLC loading is handled separately by VlcLoader inside JvmPlayerEngine; there
 * is no need to mix VLC paths into jna.library.path here.
 */
private fun tdlibDir(): String? = listOfNotNull(
    System.getenv("PAPERPLAY_TDLIB"),
    File(System.getProperty("compose.application.resources.dir") ?: ".", "native").path,
    File("native").path,
    "D:/Git forks/Teleplayer/windows/third_party/tdlib",
).firstOrNull { File(it, if (System.getProperty("os.name").startsWith("Windows")) "tdjson.dll" else "libtdjson.so").exists() }

fun main() {
    // Set jna.library.path for TDLib only. VlcLoader will append the VLC path
    // automatically when the first JvmPlayerEngine is created.
    tdlibDir()?.let { System.setProperty("jna.library.path", it) }
    io.max.paperplay.ui.app.BackupFiles.pickDestination = { name, onPicked ->
        javax.swing.SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply {
                fileFilter = FileNameExtensionFilter("Backup (*.zip)", "zip")
                selectedFile = File(name)
            }
            if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) onPicked(null)
            else {
                val chosen = chooser.selectedFile.let { if (it.extension.isEmpty()) File(it.path + ".zip") else it }
                onPicked(io.max.paperplay.ui.app.BackupDestination { path -> runCatching { File(path).copyTo(chosen, overwrite = true) }.isSuccess })
            }
        }
    }
    application {
        val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PaperPlay")
        val target = desktopRestoreTarget(dir)
        var titleBarWindow: java.awt.Window? = null
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
            pickImage = { onPicked ->
                val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("Pictures", "jpg", "jpeg", "png", "webp") }
                if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) onPicked(null)
                else onPicked(runCatching { chooser.selectedFile.readBytes() }.getOrNull())
            },
            pickPhoto = { onPicked ->
                val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("Pictures", "jpg", "jpeg", "png", "webp") }
                if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) onPicked(null)
                else onPicked(runCatching {
                    val folder = File(dir, "profile").apply { mkdirs() }
                    folder.listFiles()?.filter { it.name.startsWith("photo-") }?.forEach { it.delete() }
                    chooser.selectedFile.copyTo(File(folder, "photo-${System.currentTimeMillis()}.${chooser.selectedFile.extension}")).path
                }.getOrNull())
            },
            pickAudio = { onPicked ->
                val chooser = JFileChooser().apply {
                    isMultiSelectionEnabled = true
                    fileFilter = FileNameExtensionFilter("Audio", "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "alac", "aiff", "ape", "wv", "mka")
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) onPicked(chooser.selectedFiles.map { it.path }, false)
            },
            authRedirect = LoopbackAuth(),
            authRedirectUri = SPOTIFY_REDIRECT,
            onboarded = { File(dir, "onboarded").exists() },
            setOnboarded = { runCatching { dir.mkdirs(); File(dir, "onboarded").writeText("1") } },
            windowTheme = { dark, bg, text -> WindowsTitleBar.apply(titleBarWindow, dark, bg, text) },
        )
        Window(onCloseRequest = ::exitApplication, title = "Paper Play", state = rememberWindowState(width = 1320.dp, height = 840.dp), icon = androidx.compose.ui.res.painterResource("icon.png")) {
            titleBarWindow = window
            App(env)
        }
    }
}
