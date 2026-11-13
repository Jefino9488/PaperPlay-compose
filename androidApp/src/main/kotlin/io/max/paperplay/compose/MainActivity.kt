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
import io.max.paperplay.ui.app.IncomingShares
import java.io.File
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var services: AppServices? = null
    /** Where a notification tap asked the app to open ("player" or "tasks"). */
    private val open = MutableStateFlow<String?>(null)
    private val auth = BrowserAuth { startActivity(it) }

    /** Copies audio shared to the app into the cache and hands the paths to the uploads page. */
    private fun takeShared(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = if (intent.action == Intent.ACTION_SEND) listOfNotNull(sharedUri(intent))
        else (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra("android.intent.extra.STREAM", android.net.Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<android.net.Uri>("android.intent.extra.STREAM")).orEmpty()
        if (uris.isEmpty()) return
        val dir = File(cacheDir, "uploads").apply { mkdirs() }
        open.value = "upload"
        Thread {
            val files = uris.mapNotNull { copyToCache(it, dir) }
            if (files.isNotEmpty()) IncomingShares.files.update { it + files }
        }.start()
    }

    private fun sharedUri(intent: Intent): android.net.Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("android.intent.extra.STREAM", android.net.Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("android.intent.extra.STREAM")

    /** Copies one picked or shared document into [dir] under its display name; null when it cannot be read. */
    private fun copyToCache(uri: android.net.Uri, dir: File): String? = runCatching {
        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "song"
        val sub = File(dir, System.nanoTime().toString()).apply { mkdirs() }
        val out = File(sub, name.replace('/', '_').replace('\\', '_'))
        contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { o -> input.copyTo(o) } }
        out.path
    }.getOrNull()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(OPEN_EXTRA)?.let { open.value = it }
        takeShared(intent)
        auth.deliver(intent.data)
    }

    /** Runs the keep-alive notification while the pref allows it (off unless chosen), following the scanner. */
    @OptIn(FlowPreview::class)
    private fun watchActivity(s: AppServices) {
        fun allowed() = s.library.pref("keep_running") == "true"
        if (allowed()) KeepAliveService.start(applicationContext)
        // Updates are dropped while the service is off, so the collector can run either way.
        s.scope.launch {
            var idleSince = 0L
            combine(
                combine(s.scanner.progress, s.ready, s.error, s.activity) { scan, ready, error, activity -> arrayOf(scan, ready, error, activity) },
                s.enricher.progress, s.uploader.jobs,
            ) { v, enrich, uploads -> Triple(v, enrich != null, uploads.any { it.status == io.max.paperplay.core.app.UploadStatus.Queued || it.status == io.max.paperplay.core.app.UploadStatus.Uploading }) }
                .debounce(3000)
                .collect { (v, enriching, uploading) ->
                    val scan = v[0] as io.max.paperplay.core.app.ScanProgress?
                    val ready = v[1] as Boolean
                    val error = v[2] as String?
                    val activity = v[3] as String?
                    val busy = scan != null || activity != null || enriching || uploading || !ready
                    val now = System.currentTimeMillis()
                    if (busy) {
                        idleSince = 0L
                        // Work began again: bring the service back if the pref allows it.
                        if (allowed()) KeepAliveService.start(applicationContext)
                    } else {
                        if (idleSince == 0L) idleSince = now
                        if (now - idleSince >= 5 * 60_000L) KeepAliveService.stop(applicationContext)
                    }
                    when {
                        error != null -> KeepAliveService.update(applicationContext, "Paper Play is running", error, null, null, false)
                        scan != null -> {
                            val f = scan.fraction
                            KeepAliveService.update(applicationContext, scan.text, "", f?.let { (it * 1000).toInt() }, f?.let { 1000 }, f == null)
                        }
                        activity != null -> KeepAliveService.update(applicationContext, activity, "", null, null, true)
                        !ready -> KeepAliveService.update(applicationContext, "Paper Play is running", "Connecting to Telegram", null, null, true)
                        else -> KeepAliveService.update(applicationContext, "Paper Play is running", "Everything is up to date", null, null, false)
                    }
                }
        }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun openSettings(action: String, withPackage: Boolean = false) {
        val intent = Intent(action).apply {
            if (withPackage) data = android.net.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Runs the system prompt the first time; after a refusal Android shows no prompt, so [fallback] opens Settings instead. */
    private fun askOnce(permission: String, launch: (String) -> Unit, fallback: () -> Unit) {
        val prefs = getSharedPreferences("permission_checker", MODE_PRIVATE)
        if (prefs.getBoolean(permission, false)) { fallback(); return }
        prefs.edit().putBoolean(permission, true).apply()
        launch(permission)
    }

    private fun bluetoothOn(): Boolean? = runCatching {
        getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled
    }.getOrNull()

    private fun onWifi(): Boolean = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
    }.getOrDefault(false)

    private fun permissionStates(): List<io.max.paperplay.ui.PermissionState> {
        val list = ArrayList<io.max.paperplay.ui.PermissionState>()
        val notifications = getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
        list += io.max.paperplay.ui.PermissionState("notifications", "Notifications", "Shows the playing song with controls, and scan progress", notifications)
        val btOn = bluetoothOn()
        val btPermission = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
        list += io.max.paperplay.ui.PermissionState(
            "bluetooth", "Bluetooth devices", "Lets the app see headphones and speakers, so media buttons and output switching work",
            granted = btPermission && btOn != false,
            detail = when {
                btOn == null -> "This device has no Bluetooth"
                !btPermission -> "Not allowed to see Bluetooth devices"
                !btOn -> "Bluetooth is turned off"
                else -> "Bluetooth is on"
            },
            action = if (btPermission) "Open settings" else "Allow",
        )
        val power = getSystemService(android.os.PowerManager::class.java)
        list += io.max.paperplay.ui.PermissionState(
            "battery", "Battery", "Stops Android from pausing the app in the background, which keeps scans, uploads and playback running",
            power.isIgnoringBatteryOptimizations(packageName), action = "Open settings",
        )
        list += io.max.paperplay.ui.PermissionState(
            "wifi", "Wi-Fi network", "Local sync and Network play only reach devices on the same Wi-Fi or wired network",
            onWifi(), action = "Open settings",
        )
        return list
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // No grey scrim behind 3-button navigation; the app draws under both bars.
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        initPlayer(applicationContext)
        takeShared(intent)
        // Started by the Spotify return link after Android ended the app: keep it until services can finish the login.
        auth.deliver(intent?.data)
        // A crash leaves its error text in a file, shown in Settings > Telegram > Bot activity on the next start.
        val crashFile = File(filesDir, "crash.txt")
        // Once per process: every new activity used to wrap the previous handler again.
        if (!crashHandlerInstalled) {
            crashHandlerInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                runCatching { crashFile.writeText(Thread.currentThread().name + ": " + e.stackTraceToString().take(6000)) }
                previous?.uncaughtException(t, e)
            }
        }
        intent?.getStringExtra(OPEN_EXTRA)?.let { open.value = it }
        val library = File(filesDir, "library.sqlite")
        val onboardedFile = File(filesDir, "onboarded")
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
            // Save backup: the system "save as" picker, then the finished zip is copied to the chosen document.
            val savePending = remember { arrayOfNulls<(io.max.paperplay.ui.app.BackupDestination?) -> Unit>(1) }
            val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                savePending[0]?.invoke(
                    uri?.let {
                        io.max.paperplay.ui.app.BackupDestination { path ->
                            runCatching { contentResolver.openOutputStream(it, "wt")!!.use { out -> File(path).inputStream().use { src -> src.copyTo(out) } } }.isSuccess
                        }
                    },
                )
            }
            androidx.compose.runtime.SideEffect {
                io.max.paperplay.ui.app.BackupFiles.pickDestination = { name, onPicked -> savePending[0] = onPicked; saver.launch(name) }
            }
            // The media notification needs this on Android 13 and later; playing works without it.
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            val bluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            // New installs are asked on the onboarding permissions page, after reading why.
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 && (onboardedFile.exists() || library.exists())) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            val imagePending = remember { arrayOfNulls<(ByteArray?) -> Unit>(1) }
            val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val bytes = uri?.let { runCatching { contentResolver.openInputStream(it)!!.use { s -> s.readBytes() } }.getOrNull() }
                imagePending[0]?.invoke(bytes)
            }
            val audioPending = remember { arrayOfNulls<(List<String>, Boolean) -> Unit>(1) }
            val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                val cb = audioPending[0]
                if (uris.isNotEmpty() && cb != null) {
                    Thread {
                        val dir = File(cacheDir, "uploads").apply { mkdirs() }
                        cb(uris.mapNotNull { copyToCache(it, dir) }, true)
                    }.start()
                }
            }
            val env = remember {
                AppEnv(
                    restore = RestoreHooks(
                        pickBackup = { onPicked -> pending[0] = onPicked; picker.launch(arrayOf("*/*")) },
                        restore = { source, pw -> shared = null; BackupRestorer().restore(source, target, RestoreOptions(password = pw)) },
                    ),
                    libraryExists = { library.exists() },
                    // One AppServices per process. A recreated activity (theme, dark mode, rotation) used to build a second
                    // one beside the first: two Telegram clients on one database and two sockets on the sync port.
                    createServices = {
                        val old = shared
                        if (old != null) {
                            services = old
                            auth.onLateReturn { q -> old.scope.launch { runCatching { old.spotify.resume(q) } } }
                            old
                        } else AppServices(AndroidPlatform(applicationContext)).also {
                            shared = it
                            services = it
                            watchActivity(it)
                            auth.onLateReturn { q -> it.scope.launch { runCatching { it.spotify.resume(q) } } }
                        }
                    },
                    open = open,
                    setKeepRunning = { on -> if (on) KeepAliveService.start(applicationContext) else KeepAliveService.stop(applicationContext) },
                    pickPhoto = { onPicked -> photoPending[0] = onPicked; photoPicker.launch(arrayOf("image/*")) },
                    lastCrash = { crashFile.takeIf { it.exists() }?.readText() },
                    clearCrash = { crashFile.delete() },
                    pickAudio = { onPicked -> audioPending[0] = onPicked; audioPicker.launch(arrayOf("audio/*", "application/ogg", "application/octet-stream")) },
                    pickImage = { onPicked -> imagePending[0] = onPicked; imagePicker.launch(arrayOf("image/*")) },
                    authRedirect = auth,
                    authRedirectUri = SPOTIFY_REDIRECT,
                    onboarded = { onboardedFile.exists() },
                    setOnboarded = { runCatching { onboardedFile.writeText("1") } },
                    requestNotifications = if (Build.VERSION.SDK_INT >= 33) ({ notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) else null,
                    permissions = io.max.paperplay.ui.PermissionHooks(
                        check = { permissionStates() },
                        request = { id ->
                            when (id) {
                                "notifications" -> {
                                    val openNotificationSettings = {
                                        openSettings(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    }
                                    if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                                        askOnce(Manifest.permission.POST_NOTIFICATIONS, { notifications.launch(it) }, openNotificationSettings)
                                    } else openNotificationSettings()
                                }
                                "bluetooth" ->
                                    if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                                        askOnce(Manifest.permission.BLUETOOTH_CONNECT, { bluetooth.launch(it) }, { openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) })
                                    } else openSettings(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                "battery" -> openSettings(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                "wifi" -> openSettings(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            }
                        },
                        openAppSettings = { openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) },
                    ),
                )
            }
            App(env)
        }
    }

    companion object {
        const val OPEN_EXTRA = "open"

        /** The services of this process, shared by every activity instance. Cleared by a restore, which closes them. */
        @Volatile
        var shared: AppServices? = null

        private var crashHandlerInstalled = false

        /** True while an activity of the app is visible. */
        @Volatile
        var foreground = false
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onStop() {
        foreground = false
        super.onStop()
    }
}
