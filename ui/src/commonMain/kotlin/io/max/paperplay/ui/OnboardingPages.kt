package io.max.paperplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.app.ChoiceRow
import io.max.paperplay.ui.app.Look
import io.max.paperplay.ui.app.LookGroup
import io.max.paperplay.ui.app.SectionLabel
import io.max.paperplay.ui.app.Swatch
import io.max.paperplay.ui.app.SwitchRow
import io.max.paperplay.ui.app.ThemeMode
import io.max.paperplay.ui.app.UiPrefs
import io.max.paperplay.ui.app.accentOptions

@Composable
private fun OnboardingPage(title: String, lead: String, content: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(lead, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        content()
    }
}

@Composable
private fun Point(head: String, text: String) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(head, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun WelcomePage() = OnboardingPage(
    "Paper Play", "Your Telegram chats are your music library.",
) {
    Text("A music player that plays what you and your bot already keep in Telegram.", style = MaterialTheme.typography.bodyLarge)
}

@Composable
internal fun HowItWorksPage() = OnboardingPage("How it works", "Three things to know.") {
    Point("A bot reads your chat", "You add a bot to a chat or channel with your music. It lists the songs.")
    Point("Songs stay in Telegram", "Files are streamed or cached on this device. Nothing is copied elsewhere.")
    Point("Nothing is uploaded", "No account with us and no tracking. Your library lives on this device.")
}

@Composable
internal fun LookPage(prefs: UiPrefs) = OnboardingPage("Make it yours", "You can change all of this later in Settings.") {
    SectionLabel("Theme")
    ChoiceRow(ThemeMode.entries, prefs.themeMode, { it.label }, prefs::putThemeMode)
    SectionLabel("Accent")
    FlowRow(Modifier.padding(horizontal = 16.dp)) {
        Swatch(MaterialTheme.colorScheme.primary, prefs.accent == "dynamic", "Dynamic") { prefs.putAccent("dynamic") }
        accentOptions.forEach { a -> Swatch(Color(a.color), prefs.accent == a.id, a.label) { prefs.putAccent(a.id) } }
    }
    SectionLabel("Style")
    ChoiceRow(Look.entries, if (LookGroup.entries.all { prefs.look(it) == Look.Glass }) Look.Glass else Look.Material, { it.name }, { l ->
        LookGroup.entries.forEach { prefs.setLook(it, l) }
    })
}

@Composable
internal fun ConnectPage(choice: OnboardingChoice, revisit: Boolean, onPick: (OnboardingChoice) -> Unit) = OnboardingPage(
    "Connect", if (revisit) "Restore and bot setup are in Settings." else "Pick how to begin. Nothing to type here.",
) {
    if (!revisit) {
        if (choice == OnboardingChoice.Restore) Button({ onPick(OnboardingChoice.Restore) }, Modifier.fillMaxWidth()) { Text("Restore a backup") }
        else OutlinedButton({ onPick(OnboardingChoice.Restore) }, Modifier.fillMaxWidth()) { Text("Restore a backup") }
        Spacer(Modifier.height(12.dp))
        if (choice == OnboardingChoice.Bot) Button({ onPick(OnboardingChoice.Bot) }, Modifier.fillMaxWidth()) { Text("Set up a bot") }
        else OutlinedButton({ onPick(OnboardingChoice.Bot) }, Modifier.fillMaxWidth()) { Text("Set up a bot") }
    }
}

@Composable
internal fun AccountPage() = OnboardingPage("Your account", "Optional. You can skip this.") {
    Point("Why add it", "Your own account can read chats the bot cannot see and add songs the bot cannot reach.")
    Point("Not now", "The bot alone is enough to start. Add your account later in Settings.")
}

@Composable
internal fun PermissionsPage(setPref: (String, String) -> Unit) = OnboardingPage("Permissions", "Two things that help playback on this phone.") {
    val env = LocalAppEnv.current
    var keep by remember { mutableStateOf(true) }
    Point("Notifications", "Shows what is playing and lets you control it from the lock screen.")
    val request = env?.requestNotifications
    if (request != null) OutlinedButton({ request() }, Modifier.fillMaxWidth()) { Text("Allow notifications") }
    Spacer(Modifier.height(16.dp))
    SwitchRow(
        "Keep running", keep,
        { keep = it; env?.setKeepRunning?.let { f -> f(it) }; setPref("keep_running", "$it") },
        subtitle = "Keeps a quiet notification so Android does not stop syncing in the background.",
    )
}

@Composable
internal fun DesktopNotePage() = OnboardingPage("Nothing to install", "TDLib and VLC are bundled with the app.")

@Composable
internal fun DonePage(revisit: Boolean) = OnboardingPage(
    "All set", if (revisit) "You can reopen this from Settings any time." else "Start sets up your library.",
)
