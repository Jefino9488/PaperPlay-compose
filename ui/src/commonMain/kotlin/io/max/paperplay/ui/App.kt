package io.max.paperplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.max.paperplay.core.app.AppServices
import io.max.paperplay.ui.app.CoreBackend
import io.max.paperplay.ui.app.MainShell
import io.max.paperplay.ui.app.SetupScreen
import io.max.paperplay.ui.theme.PaperPlayTheme

/** What a platform gives the app: restore access, a way to build the services, and whether a library exists yet. */
class AppEnv(
    val restore: RestoreHooks,
    val libraryExists: () -> Boolean,
    val createServices: () -> AppServices,
)

@Composable
fun App(env: AppEnv) {
    var restoring by remember { mutableStateOf(!env.libraryExists()) }
    PaperPlayTheme {
        if (restoring) {
            RestoreScreen(
                env.restore, onContinue = { restoring = false }, onSkip = { restoring = false },
                skipLabel = "Set up without a backup",
            )
        } else {
            Main(env)
        }
    }
}

@Composable
private fun Main(env: AppEnv) {
    val backend = remember { CoreBackend(env.createServices()) }
    var hasCredentials by remember { mutableStateOf(backend.services.hasCredentials) }
    LaunchedEffect(hasCredentials) {
        if (hasCredentials) backend.connect()
    }
    if (hasCredentials) MainShell(backend) else SetupScreen(backend) { hasCredentials = true }
}
