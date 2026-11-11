package io.max.paperplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    /** Opens an image picker and calls back with a copied file path, or null if cancelled. Null where unsupported. */
    val pickPhoto: ((onPicked: (String?) -> Unit) -> Unit)? = null,
    /** The error text of the last crash, if one was saved; null otherwise. */
    val lastCrash: () -> String? = { null },
    val clearCrash: () -> Unit = {},
    /** Opens a multi-file audio picker; calls back with file paths and whether they are temporary copies. Null where unsupported. */
    val pickAudio: ((onPicked: (List<String>, Boolean) -> Unit) -> Unit)? = null,
    /** Opens Spotify's login page and returns the callback on [authRedirectUri]. Null where unsupported. */
    val authRedirect: io.max.paperplay.core.integrations.AuthRedirect? = null,
    /** The redirect link to add in the Spotify dashboard for this platform. */
    val authRedirectUri: String = "",
)

val LocalAppEnv = androidx.compose.runtime.staticCompositionLocalOf<AppEnv?> { null }

@Composable
fun App(env: AppEnv) {
    var restoring by remember { mutableStateOf(!env.libraryExists()) }
    androidx.compose.runtime.CompositionLocalProvider(LocalAppEnv provides env) { PaperPlayTheme {
        if (restoring) {
            RestoreScreen(
                env.restore, onContinue = { restoring = false }, onSkip = { restoring = false },
                skipLabel = "Set up without a backup",
            )
        } else {
            Main(env, onRestore = { restoring = true })
        }
    } }
}

@Composable
private fun Main(env: AppEnv, onRestore: () -> Unit) {
    val backend = remember { CoreBackend(env.createServices()) }
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
        // The library file must be closed before a restore replaces it.
        backend.services.close()
        onRestore()
    }
    // Only the cover path is read here, so position ticks do not recompose the theme.
    val pb = backend.playback.collectAsState()
    val coverUri by remember { derivedStateOf { pb.value.current?.coverUri } }
    val seed = if (prefs.accent == "cover") io.max.paperplay.ui.app.CoverSeeds.seed(coverUri) else prefs.seed
    PaperPlayTheme(dark = dark, seed = seed, dynamic = prefs.accent == "dynamic", amoled = prefs.amoled) {
        androidx.compose.runtime.CompositionLocalProvider(io.max.paperplay.ui.app.LocalUiPrefs provides prefs) {
            if (hasCredentials) MainShell(backend, prefs, restore, env.open) else SetupScreen(backend, onSaved = {}, onRestore = restore)
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun AppPreview() {
    val dummyRestore = RestoreHooks(pickBackup = {}, restore = { _, _ -> kotlinx.coroutines.flow.emptyFlow() })
    val dummyEnv = AppEnv(
        restore = dummyRestore,
        libraryExists = { false },
        createServices = { error("Not used in preview") },
    )
    App(env = dummyEnv)
}
