package io.max.paperplay.compose

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.max.paperplay.core.app.AppServices
import io.max.paperplay.core.backup.BackupRestorer
import io.max.paperplay.core.backup.BackupSource
import io.max.paperplay.core.backup.RestoreOptions
import io.max.paperplay.core.backup.RestoreTarget
import io.max.paperplay.core.backup.androidSecretStore
import io.max.paperplay.core.backup.streamBackupSource
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.player.initPlayer
import io.max.paperplay.ui.App
import io.max.paperplay.ui.AppEnv
import io.max.paperplay.ui.RestoreHooks
import java.io.File

class MainActivity : ComponentActivity() {
    private var services: AppServices? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initPlayer(applicationContext)
        val library = File(filesDir, "library.sqlite")
        val target = RestoreTarget(
            library.path.replace(File.separatorChar, '/'),
            File(filesDir, "covers").path,
            File(filesDir, "profile").path,
            androidSecretStore(File(filesDir, "secrets")),
            File(filesDir, "user_td").path.replace(File.separatorChar, '/'),
        ) { libraryDriver(applicationContext, it) }
        setContent {
            // Remembered, so a recomposition while the picker is open does not forget who asked.
            val pending = remember { arrayOfNulls<(BackupSource) -> Unit>(1) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) pending[0]?.invoke(streamBackupSource { contentResolver.openInputStream(uri)!! })
            }
            // The media notification needs this on Android 13 and later; playing works without it.
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            val env = remember {
                AppEnv(
                    restore = RestoreHooks(
                        pickBackup = { onPicked -> pending[0] = onPicked; picker.launch(arrayOf("*/*")) },
                        restore = { source, pw -> BackupRestorer().restore(source, target, RestoreOptions(password = pw)) },
                    ),
                    libraryExists = { library.exists() },
                    createServices = { AppServices(AndroidPlatform(applicationContext)).also { services = it } },
                )
            }
            App(env)
        }
    }
}
