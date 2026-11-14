package io.max.paperplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.max.paperplay.core.app.AppServices
import io.max.paperplay.ui.app.CoreBackend
import io.max.paperplay.ui.app.MainShell
import io.max.paperplay.ui.app.SetupScreen
import io.max.paperplay.ui.app.ThemeMode
import io.max.paperplay.ui.app.UiPrefs
import io.max.paperplay.ui.theme.PaperPlayTheme

/** What a platform gives the app: restore access, a way to build the services, and whether a library exists yet. */
class AppEnv(
    val restore: RestoreHooks,
    val libraryExists: () -> Boolean,
    val createServices: () -> AppServices,
    /** Where a notification tap asked the app to go: "player" or "tasks". The shell clears it once handled. */
    val open: kotlinx.coroutines.flow.MutableStateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    /** Starts or stops the background keep-alive notification; nothing on desktop. */
    val setKeepRunning: (Boolean) -> Unit = {},
    /** Opens a folder picker and calls back with the folder's path only; nothing inside is read. Null where unsupported. */
    val pickFolderPath: ((onPicked: (String) -> Unit) -> Unit)? = null,
    /** Opens an image picker and calls back with a copied file path, or null if canceled. Null where unsupported. */
    val pickPhoto: ((onPicked: (String?) -> Unit) -> Unit)? = null,
    /** The error text of the last crash, if one was saved; null otherwise. */
    val lastCrash: () -> String? = { null },
    val clearCrash: () -> Unit = {},
    /** Opens a multi-file audio picker; calls back with file paths and whether they are temporary copies. Null where unsupported. */
    val pickAudio: ((onPicked: (List<String>, Boolean) -> Unit) -> Unit)? = null,
    /** Opens a folder picker and calls back with every audio file path inside it (subfolders too), never temporary. Null where unsupported. */
    val pickAudioFolder: ((onPicked: (List<String>) -> Unit) -> Unit)? = null,
    /** Opens an image picker and calls back with the image bytes, or null if canceled. Null where unsupported. */
    val pickImage: ((onPicked: (ByteArray?) -> Unit) -> Unit)? = null,
    /** Opens Spotify's login page and returns the callback on [authRedirectUri]. Null where unsupported. */
    val authRedirect: io.max.paperplay.core.integrations.AuthRedirect? = null,
    /** The redirect link to add in the Spotify dashboard for this platform. */
    val authRedirectUri: String = "",
    /** True once the first-launch tour was finished or skipped. */
    val onboarded: () -> Boolean = { true },
    val setOnboarded: () -> Unit = {},
    /** Set true (from Settings) to show the tour again. */
    val showTour: kotlinx.coroutines.flow.MutableStateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    /** Asks for the notification permission; null where not needed. */
    val requestNotifications: (() -> Unit)? = null,
    /** Permission checker (Settings > Permissions); null where the platform needs none. */
    val permissions: PermissionHooks? = null,
    /** Desktop: tints the window title bar. Called with (dark, background ARGB, text ARGB). */
    val windowTheme: ((Boolean, Int, Int) -> Unit)? = null,
    /** Startup timing marks (desktop writes them to startup.log). */
    val mark: (String) -> Unit = {},
)

val LocalAppEnv = androidx.compose.runtime.staticCompositionLocalOf<AppEnv?> { null }

@Composable
fun App(env: AppEnv) {
    // The first launch of a fresh install runs the tour inside Main, where the bot can be set up for real.
    var firstRun by remember { mutableStateOf(!env.onboarded() && !env.libraryExists()) }
    var restoring by remember { mutableStateOf(!env.libraryExists() && !firstRun) }
    androidx.compose.runtime.CompositionLocalProvider(LocalAppEnv provides env) { PaperPlayTheme {
        if (restoring) {
            io.max.paperplay.ui.app.WindowTint(MaterialTheme.colorScheme.background)
            RestoreScreen(
                env.restore, onContinue = { restoring = false }, onSkip = { restoring = false },
                skipLabel = "Set up without a backup",
            )
        } else {
            Main(env, firstRun, onTourDone = { firstRun = false }, onRestore = { restoring = true })
        }
    } }
}

@Composable
private fun Main(env: AppEnv, firstRun: Boolean, onTourDone: () -> Unit, onRestore: () -> Unit) {
    var closing by remember { mutableStateOf(false) }
    val closeScope = rememberCoroutineScope()
    // The database, services and backend are built off the UI thread; a blank themed screen shows meanwhile.
    val built by produceState<CoreBackend?>(null) {
        value = withContext(Dispatchers.Default) {
            CoreBackend(env.createServices())
        }
        env.mark("services ready")
    }
    val backend = built
    if (backend == null || closing) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        return
    }
    LaunchedEffect(Unit) { env.mark("first composition of main shell") }
    val tour by env.showTour.collectAsState()
    val hasCredentials by backend.hasCredentials.collectAsState()
    LaunchedEffect(hasCredentials) {
        if (hasCredentials) backend.connect()
    }
    val prefs = remember(backend) { UiPrefs(backend::pref, backend::setPref) }
    val dark = when (prefs.themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val restore = {
        // Everything holding the library file, a socket or TDLib must stop before a restore replaces the library.
        closing = true
        closeScope.launch {
            backend.services.shutdown()
            onRestore()
        }
        Unit
    }
    // Only the cover path is read here, so position ticks do not recompose the theme.
    val pb = backend.playback.collectAsState()
    val coverUri by remember { derivedStateOf { pb.value.current?.coverUri } }
    // The desktop has no wallpaper colour, so "dynamic" there follows the cover art of the playing song.
    val coverAccent = prefs.accent == "cover" || (prefs.accent == "dynamic" && !isPhone)
    val seed = if (coverAccent) io.max.paperplay.ui.app.CoverSeeds.seed(coverUri) else prefs.seed
    PaperPlayTheme(dark = dark, seed = seed, dynamic = prefs.accent == "dynamic", amoled = prefs.amoled) {
        androidx.compose.runtime.CompositionLocalProvider(io.max.paperplay.ui.app.LocalUiPrefs provides prefs, io.max.paperplay.ui.theme.LocalReduceMotion provides prefs.reduceMotion) {
            if (firstRun) {
                io.max.paperplay.ui.app.WindowTint(MaterialTheme.colorScheme.background)
                Onboarding(prefs, revisit = false, backend = backend, setPref = backend::setPref) { choice ->
                    env.setOnboarded()
                    onTourDone()
                    if (choice == OnboardingChoice.Restore) restore()
                }
            } else {
                if (hasCredentials) MainShell(backend, prefs, restore, env.open)
                else {
                    io.max.paperplay.ui.app.WindowTint(MaterialTheme.colorScheme.background)
                    SetupScreen(backend, onSaved = {}, onRestore = restore)
                }
                if (tour) Onboarding(prefs, revisit = true, backend = backend, setPref = backend::setPref) { env.showTour.value = false }
            }
        }
    }
}


