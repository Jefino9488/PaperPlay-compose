package io.max.paperplay.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import com.kyant.backdrop.backdrops.layerBackdrop
import io.max.paperplay.ui.app.partSurface
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.account.LoginStep
import io.max.paperplay.ui.app.AccountPage
import io.max.paperplay.ui.app.AddChannelDialog
import io.max.paperplay.ui.app.ConnectionUi
import io.max.paperplay.ui.app.CoreBackend
import io.max.paperplay.ui.app.UiPrefs
import io.max.paperplay.ui.theme.PaperPlayTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Where the person wants to go after the tour. */
enum class OnboardingChoice { None, Restore, Bot }

private enum class Step { Welcome, How, Look, Profile, Connect, Bot, Chat, Account, Spotify, Permissions, Done }

/**
 * The first-launch tour and setup wizard. With a [backend] it sets the bot up, adds the music chat and offers the
 * account login right inside the tour; [revisit] is true when opened again from Settings, which shows only the tour.
 * [setPref] stores values that belong in the library, such as keep_running.
 */
@Composable
fun Onboarding(
    prefs: UiPrefs,
    revisit: Boolean,
    backend: CoreBackend? = null,
    setPref: (String, String) -> Unit,
    onFinish: (OnboardingChoice) -> Unit,
) {
    val setup = backend != null && !revisit
    val steps = remember(setup) {
        buildList {
            addAll(listOf(Step.Welcome, Step.How, Step.Look, Step.Profile))
            if (setup) addAll(listOf(Step.Connect, Step.Bot, Step.Chat, Step.Account, Step.Spotify))
            if (isPhone) add(Step.Permissions)
            add(Step.Done)
        }
    }
    val pager = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    val idle = remember { MutableStateFlow(ConnectionUi()) }
    val conn by (backend?.connection ?: idle).collectAsState()
    val idleStep = remember { MutableStateFlow<LoginStep>(LoginStep.LoggedOut) }
    val accountStep by (backend?.account?.step ?: idleStep).collectAsState()
    val botConnected = conn.ready
    val accountIn = accountStep == LoginStep.Ready
    var addChat by remember { mutableStateOf(false) }
    val current = steps[pager.currentPage]
    val last = pager.currentPage == steps.size - 1
    val botIndex = steps.indexOf(Step.Bot)
    val blocked = setup && ((current == Step.Bot && !botConnected) || (last && !(botConnected || accountIn)))

    fun goTo(step: Step) { scope.launch { pager.animateScrollToPage(steps.indexOf(step).coerceAtLeast(0)) } }

    // A swipe cannot skip past the bot page until a bot is connected.
    LaunchedEffect(setup, botConnected) {
        if (setup && !botConnected) snapshotFlow { pager.currentPage }.collect { if (it > botIndex) pager.animateScrollToPage(botIndex) }
    }

    val body: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!last) TextButton(onClick = { onFinish(OnboardingChoice.None) }) { Text("Skip") }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), userScrollEnabled = true) { page ->
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val off = kotlin.math.abs((pager.currentPage - page) + pager.currentPageOffsetFraction)
                        alpha = 1f - off.coerceIn(0f, 1f) * 0.7f
                        val s = 1f - off.coerceIn(0f, 1f) * 0.08f
                        scaleX = s; scaleY = s
                    },
                ) {
                    when (steps[page]) {
                        Step.Welcome -> WelcomePage()
                        Step.How -> HowItWorksPage()
                        Step.Look -> LookPage(prefs)
                        Step.Profile -> ProfilePage(prefs)
                        Step.Connect -> ConnectPage(onBot = { goTo(Step.Bot) }, onRestore = { onFinish(OnboardingChoice.Restore) })
                        Step.Bot -> BotSetupPage(onSave = { id, hash, token -> backend?.saveCredentials(id, hash, token) ?: "Not ready yet" }, connected = botConnected, connectionError = conn.error, onConnected = { goTo(Step.Chat) })
                        Step.Chat -> ChatPage(conn.chats.map { it.title }, botConnected, onAdd = { addChat = true })
                        Step.Account -> AccountStepPage {
                            if (backend != null) AccountPage(backend, onOpenIndex = null, onLogOut = { scope.launch { backend.account.logOut() } })
                        }
                        Step.Spotify -> SpotifyStepPage { if (backend != null) io.max.paperplay.ui.app.SpotifyAccountSection(backend) }
                        Step.Permissions -> PermissionsPage(setPref)
                        Step.Done -> DonePage(revisit, botConnected, accountIn, conn.chats.size, onSetupBot = { goTo(Step.Bot) })
                    }
                }
            }
            PageDots(steps.size, pager.currentPage, Modifier.fillMaxWidth().padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (pager.currentPage > 0) TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text("Back") }
                else Spacer(Modifier.size(1.dp))
                val source = remember { MutableInteractionSource() }
                Button(
                    onClick = { if (last) onFinish(OnboardingChoice.Bot) else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    Modifier.bouncy(source), interactionSource = source, enabled = !blocked, shape = RoundedCornerShape(28.dp),
                ) { Text(if (last) (if (revisit) "Close" else "Start") else "Next") }
            }
        }
    }

    // The Style choice shows at once: with glass on, the card blurs the drifting colour wash behind it.
    val glass = io.max.paperplay.ui.app.LookGroup.entries.any { prefs.look(it) == io.max.paperplay.ui.app.Look.Glass }
    val wash = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassBackdrop provides (if (glass) wash else null)) {
        Box(Modifier.fillMaxSize()) {
            Box(if (glass) Modifier.fillMaxSize().layerBackdrop(wash) else Modifier.fillMaxSize()) { GradientBackdrop() }
            if (isPhone) {
                body(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp))
            } else {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    val card = Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxSize()
                    if (glass) Box(card.partSurface(io.max.paperplay.ui.app.LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(36.dp))) {
                        body(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 12.dp))
                    } else Surface(
                        card, shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.82f), tonalElevation = 2.dp,
                    ) { body(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 12.dp)) }
                }
            }
        }
    }
    if (addChat && backend != null) AddChannelDialog(backend, botConnected) { addChat = false }
}

@Preview(name = "Tour", widthDp = 420, heightDp = 800)
@Composable
private fun OnboardingPreview() {
    val map = remember { mutableMapOf<String, String>() }
    PaperPlayTheme { Onboarding(UiPrefs(map::get, map::put), revisit = true, setPref = { _, _ -> }, onFinish = {}) }
}

@Preview(name = "Tour desktop", widthDp = 1100, heightDp = 760)
@Composable
private fun OnboardingDesktopPreview() {
    val map = remember { mutableMapOf<String, String>() }
    PaperPlayTheme(dark = true) { Onboarding(UiPrefs(map::get, map::put), revisit = true, setPref = { _, _ -> }, onFinish = {}) }
}
