package io.max.paperplay.compose

import android.Manifest
import android.content.Intent
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var services: AppServices? = null
    /** Where a notification tap asked the app to open ("player" or "tasks"). */
    private val open = MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(OPEN_EXTRA)?.let { open.value = it }
    }

    /** Runs the keep-alive notification while the pref allows it, following the scanner. */
    @OptIn(FlowPreview::class)
    private fun watchActivity(s: AppServices) {
        if (s.library.pref("keep_running") != "false") KeepAliveService.start(applicationContext)
        // Updates are dropped while the service is off, so the collector can run either way.
        s.scope.launch {
            combine(s.scanner.progress, s.ready, s.error) { scan, ready, error -> Triple(scan, ready, error) }
                .debounce(1000)
                .collect { (scan, ready, error) ->
                    when {
                        error != null -> KeepAliveService.update(applicationContext, "Paper Play is running", error, null, null, false)
                        scan != null -> {
                            val f = scan.fraction
                            KeepAliveService.update(applicationContext, scan.text, "", f?.let { (it * 1000).toInt() }, f?.let { 1000 }, f == null)
                        }
                        !ready -> KeepAliveService.update(applicationContext, "Paper Play is running", "Connecting to Telegram", null, null, true)
                        else -> KeepAliveService.update(applicationContext, "Paper Play is running", "Everything is up to date", null, null, false)
                    }
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initPlayer(applicationContext)
        intent?.getStringExtra(OPEN_EXTRA)?.let { open.value = it }
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
            // Profile photo: copied into the app's files, so the picked document can be released.
            val photoPending = remember { arrayOfNulls<(String?) -> Unit>(1) }
            val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val path = uri?.let {
                    runCatching {
                        val dir = File(filesDir, "profile").apply { mkdirs() }
                        dir.listFiles()?.filter { f -> f.name.startsWith("photo-") }?.forEach { f -> f.delete() }
                        val out = File(dir, "photo-${System.currentTimeMillis()}.jpg")
                        contentResolver.openInputStream(it)!!.use { input -> out.outputStream().use { o -> input.copyTo(o) } }
                        out.path
                    }.getOrNull()
                }
                photoPending[0]?.invoke(path)
            }
            val env = remember {
                AppEnv(
                    restore = RestoreHooks(
                        pickBackup = { onPicked -> pending[0] = onPicked; picker.launch(arrayOf("*/*")) },
                        restore = { source, pw -> BackupRestorer().restore(source, target, RestoreOptions(password = pw)) },
                    ),
                    libraryExists = { library.exists() },
                    createServices = { AppServices(AndroidPlatform(applicationContext)).also { services = it; watchActivity(it) } },
                    open = open,
                    setKeepRunning = { on -> if (on) KeepAliveService.start(applicationContext) else KeepAliveService.stop(applicationContext) },
                    pickPhoto = { onPicked -> photoPending[0] = onPicked; photoPicker.launch(arrayOf("image/*")) },
                )
            }
            App(env)
        }
    }

    companion object {
        const val OPEN_EXTRA = "open"
    }
}
