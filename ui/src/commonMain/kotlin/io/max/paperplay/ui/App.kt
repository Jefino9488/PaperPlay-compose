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
    /** Opens an image picker and calls back with a copied file path, or null if canceled. Null where unsupported. */
    val pickPhoto: ((onPicked: (String?) -> Unit) -> Unit)? = null,
    /** The error text of the last crash, if one was saved; null otherwise. */
    val lastCrash: () -> String? = { null },
    val clearCrash: () -> Unit = {},
    /** Opens a multi-file audio picker; calls back with file paths and whether they are temporary copies. Null where unsupported. */
    val pickAudio: ((onPicked: (List<String>, Boolean) -> Unit) -> Unit)? = null,
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
)

val LocalAppEnv = androidx.compose.runtime.staticCompositionLocalOf<AppEnv?> { null }

@Composable
fun App(env: AppEnv) {
    var restoring by remember { mutableStateOf(!env.libraryExists()) }
    // A library already on disk means an existing install, so the tour is not forced on it.
    var touring by remember { mutableStateOf(!env.onboarded() && !env.libraryExists()) }
    // Choices made before the library exists; applied once it does.
    val pending = remember { mutableMapOf<String, String>() }
    androidx.compose.runtime.CompositionLocalProvider(LocalAppEnv provides env) { PaperPlayTheme {
        if (touring) {
            val tourPrefs = remember { UiPrefs(pending::get, pending::put) }
            val dark = when (tourPrefs.themeMode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            PaperPlayTheme(dark = dark, seed = tourPrefs.seed, dynamic = tourPrefs.accent == "dynamic", amoled = tourPrefs.amoled) {
                Onboarding(tourPrefs, revisit = false, setPref = { k, v -> pending[k] = v }) { choice ->
                    env.setOnboarded()
                    when (choice) {
                        OnboardingChoice.Restore -> restoring = true
                        OnboardingChoice.Bot -> restoring = false
                        OnboardingChoice.None -> {}
                    }
                    touring = false
                }
            }
        } else if (restoring) {
            RestoreScreen(
                env.restore, onContinue = { restoring = false }, onSkip = { restoring = false },
                skipLabel = "Set up without a backup",
            )
        } else {
            Main(env, pending, onRestore = { restoring = true })
        }
    } }
}

@Composable
private fun Main(env: AppEnv, pending: MutableMap<String, String>, onRestore: () -> Unit) {
    val backend = remember {
        CoreBackend(env.createServices()).also { b ->
            pending.forEach { (k, v) -> b.setPref(k, v) }
            pending.clear()
        }
    }
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
            if (tour) Onboarding(prefs, revisit = true, setPref = backend::setPref) { env.showTour.value = false }
        }
    }
}


