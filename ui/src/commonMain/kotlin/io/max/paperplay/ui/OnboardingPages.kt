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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.backup.BackupException
import io.max.paperplay.core.backup.BackupSource
import io.max.paperplay.core.backup.Outcome
import io.max.paperplay.core.backup.RestoreEvent
import io.max.paperplay.core.backup.RestoreResult
import io.max.paperplay.core.backup.WrongPassword
import io.max.paperplay.ui.app.ChoiceRow
import io.max.paperplay.ui.app.CoreBackend
import io.max.paperplay.ui.app.CredentialsForm
import io.max.paperplay.ui.app.Look
import io.max.paperplay.ui.app.LookGroup
import io.max.paperplay.ui.app.SectionLabel
import io.max.paperplay.ui.app.Swatch
import io.max.paperplay.ui.app.SwitchRow
import io.max.paperplay.ui.app.ThemeMode
import io.max.paperplay.ui.app.UiPrefs
import io.max.paperplay.ui.app.accentOptions
import io.max.paperplay.ui.theme.PaperPlayTheme
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
internal fun BotSetupPage(backend: CoreBackend?, onSaved: () -> Unit) = OnboardingPage(
    "Set up a bot", "Connect your Telegram bot. The API id and hash come from my.telegram.org, the token from @BotFather.",
) {
    if (backend != null) {
        CredentialsForm(backend::saveCredentials, onSaved)
    } else {
        Text("Backend services loading...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun RestorePage(hooks: RestoreHooks?, onContinue: () -> Unit, onSkip: (() -> Unit)? = null, skipLabel: String = "Set up without a backup") = OnboardingPage(
    "Restore backup", "Bring your library, playlists and settings over from a backup file.",
) {
    val scope = rememberCoroutineScope()
    var chosenSource by remember { mutableStateOf<BackupSource?>(null) }
    var isEncrypted by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<RestoreState>(RestoreState.Idle) }

    fun runRestore(source: BackupSource, pwd: String?) {
        if (hooks == null) return
        state = RestoreState.Running("Reading the backup", null)
        scope.launch {
            state = try {
                var result: RestoreResult? = null
                hooks.restore(source, pwd?.ifEmpty { null }).collect {
                    when (it) {
                        is RestoreEvent.Progress -> state = RestoreState.Running(it.stage, it.fraction)
                        is RestoreEvent.Finished -> result = it.result
                    }
                }
                RestoreState.Done(result!!)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WrongPassword) {
                RestoreState.Failed("That password doesn't open this backup. Please check your password and try again.")
            } catch (e: BackupException) {
                RestoreState.Failed(e.message ?: "The backup could not be restored.")
            } catch (e: Exception) {
                RestoreState.Failed("The backup could not be restored.")
            }
        }
    }

    fun pickFile() {
        hooks?.pickBackup { source ->
            chosenSource = source
            val encrypted = isBackupEncrypted(source)
            isEncrypted = encrypted
            if (!encrypted) {
                runRestore(source, null)
            }
        }
    }

    when (val s = state) {
        RestoreState.Idle, is RestoreState.Failed -> {
            val source = chosenSource
            if (source != null && isEncrypted) {
                Text("This backup file is encrypted. Enter the backup password to restore your credentials:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    password, { password = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Backup password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(12.dp))
                if (s is RestoreState.Failed) {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                Button({ runRestore(source, password) }, Modifier.fillMaxWidth()) {
                    Text("Restore with password")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton({ chosenSource = null; password = ""; state = RestoreState.Idle; pickFile() }, Modifier.fillMaxWidth()) {
                    Text("Select a different backup file")
                }
            } else {
                if (s is RestoreState.Failed) {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                Button({ pickFile() }, Modifier.fillMaxWidth(), enabled = hooks != null) {
                    Text("Select backup file")
                }
            }
            onSkip?.let {
                Spacer(Modifier.height(8.dp))
                TextButton(it, Modifier.fillMaxWidth()) { Text(skipLabel) }
            }
        }
        is RestoreState.Running -> {
            Text(s.stage, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            if (s.fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { s.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        is RestoreState.Done -> {
            Text("Restored ${s.result.songs} songs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            for (i in s.result.items) {
                val mark = when (i.outcome) { Outcome.Restored -> "Restored"; Outcome.Skipped -> "Skipped"; Outcome.Failed -> "Failed" }
                Text("$mark: ${i.name}" + if (i.note.isEmpty()) "" else " (${i.note})", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Button(onContinue, Modifier.fillMaxWidth()) { Text("Continue") }
        }
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

@Preview
@Composable
private fun WelcomePagePreview() {
    PaperPlayTheme {
        WelcomePage()
    }
}

@Preview
@Composable
private fun HowItWorksPagePreview() {
    PaperPlayTheme {
        HowItWorksPage()
    }
}

@Preview
@Composable
private fun LookPagePreview() {
    val map = remember { mutableMapOf<String, String>() }
    val prefs = remember { UiPrefs(map::get, map::put) }
    PaperPlayTheme {
        LookPage(prefs)
    }
}

@Preview
@Composable
private fun ConnectPagePreview() {
    PaperPlayTheme {
        ConnectPage(choice = OnboardingChoice.Bot, revisit = false, onPick = {})
    }
}

@Preview
@Composable
private fun BotSetupPagePreview() {
    PaperPlayTheme {
        BotSetupPage(backend = null, onSaved = {})
    }
}

@Preview
@Composable
private fun RestorePagePreview() {
    PaperPlayTheme {
        RestorePage(hooks = null, onContinue = {})
    }
}

@Preview
@Composable
private fun AccountPagePreview() {
    PaperPlayTheme {
        AccountPage()
    }
}

@Preview
@Composable
private fun PermissionsPagePreview() {
    PaperPlayTheme {
        PermissionsPage(setPref = { _, _ -> })
    }
}

@Preview
@Composable
private fun DesktopNotePagePreview() {
    PaperPlayTheme {
        DesktopNotePage()
    }
}

@Preview
@Composable
private fun DonePagePreview() {
    PaperPlayTheme {
        DonePage(revisit = false)
    }
}
