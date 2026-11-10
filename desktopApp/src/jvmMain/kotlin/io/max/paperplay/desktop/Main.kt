package io.max.paperplay.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.max.paperplay.core.backup.BackupRestorer
import io.max.paperplay.core.backup.RestoreOptions
import io.max.paperplay.core.backup.desktopRestoreTarget
import io.max.paperplay.core.backup.fileBackupSource
import io.max.paperplay.ui.App
import io.max.paperplay.ui.RestoreHooks
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

fun main() = application {
    val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PaperPlay")
    val hooks = RestoreHooks(
        pickBackup = { onPicked ->
            val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("Backup (*.zip)", "zip") }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) onPicked(fileBackupSource(chooser.selectedFile.path))
        },
        restore = { source, pw -> BackupRestorer().restore(source, desktopRestoreTarget(dir), RestoreOptions(password = pw)) },
    )
    Window(onCloseRequest = ::exitApplication, title = "Paper Play") {
        // -Dpaperplay.demo=true keeps a way past the restore screen to the glass demo.
        App(if (File(dir, "library.sqlite").exists()) null else hooks, System.getProperty("paperplay.demo") == "true")
    }
}
