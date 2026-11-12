package io.max.paperplay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import io.max.paperplay.ui.theme.PaperPlayTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
internal fun WelcomePage() = OnboardingPage("Paper Play", "Your Telegram chats are your music library.", logo = true) {
    Text("A music player that plays what you and your bot already keep in Telegram.", style = MaterialTheme.typography.bodyLarge)
}

@Composable
internal fun HowItWorksPage() = OnboardingPage("How it works", "Three things to know.", Icons.Rounded.Forum) {
    Point("A bot reads your chat", "You add a bot to a chat or channel with your music. It lists the songs.")
    Point("Songs stay in Telegram", "Files are streamed or cached on this device. Nothing is copied elsewhere.")
    Point("Nothing is uploaded", "No account with us and no tracking. Your library lives on this device.")
}

@Composable
internal fun LookPage(prefs: UiPrefs) = OnboardingPage("Make it yours", "You can change all of this later in Settings.", Icons.Rounded.Palette) {
    SectionLabel("Theme")
    ChoiceRow(ThemeMode.entries, prefs.themeMode, { it.label }, prefs::putThemeMode)
    SectionLabel("Accent")
    FlowRow(Modifier.padding(horizontal = 16.dp)) {
        // Without a wallpaper colour on the desktop, "dynamic" follows the cover art of the playing song.
        Swatch(MaterialTheme.colorScheme.primary, prefs.accent == "dynamic", if (isPhone) "Dynamic" else "Album art") { prefs.putAccent("dynamic") }
        accentOptions.forEach { a -> Swatch(Color(a.color), prefs.accent == a.id, a.label) { prefs.putAccent(a.id) } }
    }
    SectionLabel("Style")
    ChoiceRow(Look.entries, if (LookGroup.entries.all { prefs.look(it) == Look.Glass }) Look.Glass else Look.Material, { it.name }, { l ->
        LookGroup.entries.forEach { prefs.setLook(it, l) }
    })
}

@Composable
internal fun ProfilePage(prefs: UiPrefs) = OnboardingPage("About you", "Shown on Home and in Settings. Optional.", Icons.Rounded.Person) {
    var name by remember { mutableStateOf(if (prefs.profileName == "Listener") "" else prefs.profileName) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        io.max.paperplay.ui.app.Avatar(prefs.profilePhoto, name.ifBlank { prefs.profileName }, 96.dp)
        val pick = LocalAppEnv.current?.pickPhoto
        if (pick != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ pick { path -> if (path != null) prefs.putProfilePhoto(path) } }) { Text("Choose photo") }
            if (prefs.profilePhoto != null) OutlinedButton({ prefs.putProfilePhoto(null) }) { Text("Remove") }
        }
        androidx.compose.material3.OutlinedTextField(
            name, { name = it; prefs.putProfileName(it.trim().ifEmpty { "Listener" }) }, Modifier.fillMaxWidth(),
            label = { Text("Your name") }, singleLine = true,
        )
    }
}

/** A big tappable card that springs when pressed. */
@Composable
private fun ChoiceCard(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick, Modifier.fillMaxWidth().bouncy(source), interactionSource = source,
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ExpressiveHero(icon, size = 56.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ConnectPage(onBot: () -> Unit, onRestore: () -> Unit) = OnboardingPage("Connect", "Pick how to begin.", Icons.Rounded.Send, nudge = 2.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ChoiceCard(Icons.Rounded.SmartToy, "Set up a bot", "Connect a Telegram bot and add your music chat.", onBot)
        ChoiceCard(Icons.Rounded.Restore, "Restore a backup", "Bring your library, playlists and settings from a backup file.", onRestore)
    }
}

@Composable
internal fun BotSetupPage(
    onSave: (String, String, String) -> String?, connected: Boolean, connectionError: String?, onConnected: () -> Unit = {},
) = OnboardingPage("Set up a bot", "Three quick steps, then connect.", Icons.Rounded.SmartToy) {
    BotSetupForm(onSave, connected, connectionError, onConnected = onConnected)
}

@Composable
internal fun ChatPage(chats: List<String>, connected: Boolean, onAdd: () -> Unit) = OnboardingPage(
    "Add your music chat", "The chat or channel where your songs are. The bot must be an admin there.", Icons.Rounded.Forum,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        chats.forEach { name ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        if (chats.isEmpty()) Text("No chat added yet. You can also add one later in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val source = remember { MutableInteractionSource() }
        Button(onAdd, Modifier.fillMaxWidth().bouncy(source), interactionSource = source, enabled = connected, shape = RoundedCornerShape(28.dp)) {
            Icon(Icons.Rounded.Add, null, Modifier.padding(end = 8.dp))
            Text(if (chats.isEmpty()) "Add a chat" else "Add another chat")
        }
        if (!connected) Text("Connect the bot first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Posts from other bots show up only if those bots post as the chat (anonymous admin).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Optional: bring Spotify playlists in. The login is not needed for public playlists; it lifts the 100 track limit. */
@Composable
internal fun SpotifyStepPage(login: @Composable () -> Unit) = OnboardingPage(
    "Spotify", "Optional. Skip it and set it up later in Settings.", Icons.Rounded.PlaylistAdd,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Point("Import your playlists", "Paste a Spotify playlist link and the songs you already have join a new playlist. The rest wait there and join when they show up.")
        Point("Log in for the full list", "Without a login only a playlist's first 100 tracks can be read. Logging in with your own Spotify app reads every track.")
        login()
    }
}

@Composable
internal fun AccountStepPage(login: @Composable () -> Unit) = OnboardingPage(
    "Your account", "Optional. The bot alone is enough to start.", Icons.Rounded.Person,
) {
    var more by remember { mutableStateOf(false) }
    Column(Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Point("Read chats the bot cannot", "Your account can read any chat you are in, even where you cannot add a bot.")
        Point("Find songs other bots posted", "A bot never sees what another bot posted in a group. Your account does.")
        Point("Copy them into one library", "Copy songs into your main chat in one tap, without a forwarded label.")
        TextButton({ more = !more }) {
            Icon(Icons.Rounded.Info, null, Modifier.padding(end = 8.dp))
            Text(if (more) "Less info" else "More info")
            Icon(if (more) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        AnimatedVisibility(more, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Detail("Any chat you are in", "Friends' channels, groups owned by others, private chats and Saved Messages can be indexed. A bot can only read chats it was added to.")
                    Detail("Songs from other bots", "In a group, a bot cannot see messages from other bots. Your account can, so songs that looked missing appear.")
                    Detail("One clean library", "Songs found in your other chats are copied into your main chat without a forwarded-from label, so everything plays from one place.")
                    Detail("Playback through your account", "Songs the bot cannot reach are streamed through your account instead.")
                    Detail("A saved index", "The list of what you indexed is saved to your Saved Messages, so a new device or a reinstall can restore it without reading every chat again.")
                    Detail("What it never does", "It posts nothing on its own. Only the copies you ask for. The login stays on this device and you can log out in Settings any time.")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        login()
    }
}

@Composable
private fun Detail(head: String, text: String) {
    Column {
        Text(head, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PermissionsPage(setPref: (String, String) -> Unit) = OnboardingPage(
    "Permissions", "Two things that help playback on this phone.", Icons.Rounded.Notifications,
) {
    val env = LocalAppEnv.current
    var keep by remember { mutableStateOf(true) }
    Point("Notifications", "Shows what is playing and lets you control it from the lock screen.")
    val request = env?.requestNotifications
    // Re-read while the page is open, so the answer shows as soon as the system dialog closes.
    val check = env?.permissions?.check
    var allowed by remember { mutableStateOf(check?.invoke()?.firstOrNull { it.id == "notifications" }?.granted == true) }
    androidx.compose.runtime.LaunchedEffect(check) {
        while (check != null) {
            allowed = check().firstOrNull { it.id == "notifications" }?.granted == true
            kotlinx.coroutines.delay(1000)
        }
    }
    if (allowed) Tick("Notifications allowed")
    else if (request != null) OutlinedButton({ request() }, Modifier.fillMaxWidth()) { Text("Allow notifications") }
    Spacer(Modifier.height(16.dp))
    SwitchRow(
        "Keep running", keep,
        { keep = it; env?.setKeepRunning?.let { f -> f(it) }; setPref("keep_running", "$it") },
        subtitle = "Keeps a quiet notification so Android does not stop syncing in the background.",
    )
}

/** The last page. "All set" shows only once a bot is connected or an account is logged in. */
@Composable
internal fun DonePage(revisit: Boolean, botConnected: Boolean, accountLoggedIn: Boolean, chatCount: Int, onSetupBot: () -> Unit) {
    if (revisit) {
        OnboardingPage("All set", "You can reopen this from Settings any time.", Icons.Rounded.CheckCircle)
    } else if (botConnected || accountLoggedIn) {
        OnboardingPage("All set", "Start opens your library.", Icons.Rounded.CheckCircle) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (botConnected) Tick("Bot connected")
                if (accountLoggedIn) Tick("Your account is logged in")
                if (chatCount > 0) Tick("$chatCount ${if (chatCount == 1) "chat" else "chats"} added")
                else Text("No chat added yet. Add one under Settings > Channels.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        OnboardingPage("Almost there", "Nothing is connected yet.", Icons.Rounded.SmartToy) {
            Text("Set up a bot or log in with your account first, then you can start.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onSetupBot, shape = RoundedCornerShape(28.dp)) { Text("Set up a bot") }
        }
    }
}

@Composable
private fun Tick(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Preview(name = "Welcome", widthDp = 420, heightDp = 640, showBackground = true)
@Composable
private fun WelcomePreview() = PaperPlayTheme { WelcomePage() }

@Preview(name = "How it works", widthDp = 420, heightDp = 640, showBackground = true)
@Composable
private fun HowItWorksPreview() = PaperPlayTheme { HowItWorksPage() }

@Preview(name = "Look", widthDp = 420, heightDp = 760, showBackground = true)
@Composable
private fun LookPreview() = PaperPlayTheme { LookPage(io.max.paperplay.ui.previewPrefs()) }

@Preview(name = "Connect", widthDp = 420, heightDp = 640, showBackground = true)
@Composable
private fun ConnectPreview() = PaperPlayTheme { ConnectPage({}, {}) }

@Preview(name = "Bot setup page", widthDp = 420, heightDp = 1100, showBackground = true)
@Composable
private fun BotSetupPreview() = PaperPlayTheme { BotSetupPage({ _, _, _ -> null }, false, null) }

@Preview(name = "Chat page", widthDp = 420, heightDp = 640, showBackground = true)
@Composable
private fun ChatPreview() = PaperPlayTheme { ChatPage(listOf("Somg Playlist"), true, {}) }

@Preview(name = "Chat page empty", widthDp = 420, heightDp = 640, showBackground = true)
@Composable
private fun ChatEmptyPreview() = PaperPlayTheme { ChatPage(emptyList(), false, {}) }

@Preview(name = "Account page", widthDp = 420, heightDp = 900, showBackground = true)
@Composable
private fun AccountStepPreview() = PaperPlayTheme { AccountStepPage { OutlinedButton({}) { Text("Log in with phone number") } } }

@Preview(name = "Permissions", widthDp = 420, heightDp = 640, showBackground = true)
@Composable
private fun PermissionsPreview() = PaperPlayTheme { PermissionsPage { _, _ -> } }

@Preview(name = "Done", widthDp = 420, heightDp = 640, showBackground = true)
@Composable
private fun DonePreview() = PaperPlayTheme { DonePage(false, true, false, 1) {} }

@Preview(name = "Done not ready", widthDp = 420, heightDp = 640, showBackground = true)
@Composable
private fun DoneNotReadyPreview() = PaperPlayTheme { DonePage(false, false, false, 0) {} }
