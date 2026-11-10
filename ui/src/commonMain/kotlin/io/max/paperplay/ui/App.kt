package io.max.paperplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.max.paperplay.ui.theme.PaperPlayTheme

/**
 * [restore] is non-null on first launch (no library yet): the restore screen comes first.
 * [debugDemo] adds a way past it to the glass demo.
 */
@Composable
fun App(restore: RestoreHooks? = null, debugDemo: Boolean = false) {
    var restoring by remember { mutableStateOf(restore != null) }
    PaperPlayTheme {
        if (restoring && restore != null) {
            RestoreScreen(restore, onContinue = { restoring = false }, onSkip = if (debugDemo) ({ restoring = false }) else null)
        } else GlassDemo()
    }
}
