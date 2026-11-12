package io.max.paperplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.app.CoreBackend
import io.max.paperplay.ui.app.UiPrefs
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Where the person wants to go after the tour. */
enum class OnboardingChoice { None, Restore, Bot }

/**
 * The unified first-launch tour and setup wizard.
 * [revisit] is true when opened again from Settings, which hides the hand-off choices.
 * [setPref] stores values that belong in the library, such as keep_running.
 */
@Composable
fun Onboarding(
    prefs: UiPrefs,
    revisit: Boolean,
    initialChoice: OnboardingChoice = OnboardingChoice.None,
    restoreHooks: RestoreHooks? = null,
    backend: CoreBackend? = null,
    setPref: (String, String) -> Unit,
    onFinish: (OnboardingChoice) -> Unit,
) {
    var choice by remember(initialChoice) { mutableStateOf(initialChoice) }
    val pages = 8
    val pager = rememberPagerState { pages }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages - 1

    val body: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!last) TextButton(onClick = { onFinish(OnboardingChoice.None) }) { Text("Skip") }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), userScrollEnabled = true) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> HowItWorksPage()
                    2 -> LookPage(prefs)
                    3 -> ConnectPage(choice, revisit) { chosen ->
                        choice = chosen
                        scope.launch { pager.animateScrollToPage(4) }
                    }
                    4 -> if (choice == OnboardingChoice.Restore) {
                        RestorePage(restoreHooks, onContinue = { scope.launch { pager.animateScrollToPage(5) } })
                    } else {
                        BotSetupPage(backend, onSaved = { scope.launch { pager.animateScrollToPage(5) } })
                    }
                    5 -> AccountPage()
                    6 -> if (isPhone) PermissionsPage(setPref) else DesktopNotePage()
                    else -> DonePage(revisit)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                repeat(pages) { i ->
                    Box(
                        Modifier.padding(horizontal = 4.dp).size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (pager.currentPage > 0) TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text("Back") }
                else Spacer(Modifier.size(1.dp))
                Button(onClick = {
                    if (last) onFinish(choice) else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }) { Text(if (last) (if (revisit) "Close" else "Start") else "Next") }
            }
        }
    }

    if (isPhone) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            body(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp))
        }
    } else {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Surface(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().height(620.dp),
                    shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp,
                ) { body(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp)) }
            }
        }
    }
}

@Preview
@Composable
private fun OnboardingPreview() {
    val map = remember { mutableMapOf<String, String>() }
    Onboarding(UiPrefs(map::get, map::put), revisit = false, setPref = { _, _ -> }, onFinish = {})
}
