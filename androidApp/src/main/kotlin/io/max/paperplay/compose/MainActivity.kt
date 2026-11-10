package io.max.paperplay.compose

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import io.max.paperplay.core.backup.BackupRestorer
import io.max.paperplay.core.backup.BackupSource
import io.max.paperplay.core.backup.RestoreOptions
import io.max.paperplay.core.backup.RestoreTarget
import io.max.paperplay.core.backup.androidSecretStore
import io.max.paperplay.core.backup.streamBackupSource
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.player.initPlayer
import io.max.paperplay.ui.App
import io.max.paperplay.ui.RestoreHooks
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initPlayer(applicationContext)
        val db = getDatabasePath("library.sqlite")
        val firstLaunch = !db.exists()
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val target = RestoreTarget(
            db.path.replace(File.separatorChar, '/'),
            File(filesDir, "covers").path,
            File(filesDir, "profile").path,
            androidSecretStore(File(filesDir, "secrets")),
        ) { libraryDriver(applicationContext, it) }
        setContent {
            var pending: ((BackupSource) -> Unit)? = null
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) pending?.invoke(streamBackupSource { contentResolver.openInputStream(uri)!! })
            }
            val hooks = remember {
                RestoreHooks(
                    pickBackup = { onPicked -> pending = onPicked; picker.launch(arrayOf("*/*")) },
                    restore = { source, pw -> BackupRestorer().restore(source, target, RestoreOptions(password = pw)) },
                )
            }
            App(if (firstLaunch) hooks else null, debuggable)
        }
    }
}
