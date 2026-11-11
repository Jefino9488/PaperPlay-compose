@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.max.paperplay.ui.app

import io.max.paperplay.ui.components.AppIcon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.isPhone

internal class Category(val id: String, val title: String, val subtitle: String, val keywords: String, val icon: ImageVector, val phoneOnly: Boolean = false)

internal val categories = listOf(
    Category("style", "Style", "Material 3 Expressive or glass, mixed as you like", "style look glass material expressive blur", Icons.Rounded.AutoAwesome),
    Category("appearance", "Appearance", "Theme, colors, display", "theme dark amoled accent color", Icons.Rounded.Palette),
    Category("nav", "Navigation bar", "Floating bar and its style", "nav bar floating radius", Icons.Rounded.ViewAgenda, phoneOnly = true),
    Category("glass", "Glass", "Liquid glass look", "glass blur refraction", Icons.Rounded.BlurOn, phoneOnly = true),
    Category("telegram", "Telegram", "Channels, your account, bot activity, log out", "bot channels account log tdlib logout speed reading copy", Icons.Rounded.Send),
    Category("library", "Library", "Speeds, sorting, duplicates, rescan, song info", "speed rate rescan duplicates sort general covers info", Icons.Rounded.LibraryMusic),
    Category("mixes", "Mixes", "Taste learning and weather mixes", "mix taste learning weather city", Icons.Rounded.AutoAwesome),
    Category("uploads", "Uploads", "Upload pace and skipping known songs", "upload slow skip speed parallel", Icons.Rounded.Upload),
    Category("spotify", "Spotify import", "Import playlists from Spotify", "spotify playlist import", Icons.Rounded.PlaylistAdd),
    Category("listening", "Listening", "ListenBrainz, volume levelling, equalizer", "listenbrainz scrobble listens loudness replaygain equalizer eq", Icons.Rounded.GraphicEq),
    Category("devices", "Devices and background", "Connect devices, keep running", "device connection sync background battery", Icons.Rounded.Devices),
    Category("storage", "Storage and backup", "Cache size, backup and restore", "cache storage backup restore clear", Icons.Rounded.Storage),
)

private val cacheLimits = listOf(1L shl 29, 1L shl 30, 2L shl 30, 5L shl 30, 10L shl 30, 20L shl 30, 50L shl 30)
private fun bytesText(b: Long) = if (b >= 1L shl 30) "${b / (1L shl 30)} GB" else "${b / (1L shl 20)} MB"

private class RateWork(val key: String, val label: String, val default: Int)

private val rateWorks = mapOf(
    "reading" to RateWork("rate_reading", "Reading channels", 20),
    "accountCopy" to RateWork("rate_accountCopy", "Copying from your chats", 20),
    "filing" to RateWork("rate_filing", "Copying, moving and deleting songs", 12),
    "lookups" to RateWork("rate_lookups", "Song info, lyrics and genre", 50),
    "covers" to RateWork("rate_covers", "Covers", 40),
    "uploads" to RateWork("rate_uploads", "Uploads", 6),
)

/**
 * The Settings pages. [stack] holds the open page ids (empty shows the list), so the shell's back handler can pop it.
 */
@Composable
fun SettingsHost(
    stack: MutableList<String>, prefs: UiPrefs, conn: ConnectionUi, songCount: Int, backend: CoreBackend,
    onRestore: () -> Unit, onExit: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier,
) {
    val page = stack.lastOrNull()
    val title = categories.firstOrNull { it.id == page }?.title ?: settingsPageTitle(page) ?: "Settings"
    Column(modifier) {
        BackTopBar(title, onBack = { if (stack.isEmpty()) onExit() else stack.removeAt(stack.lastIndex) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomPadding)) {
            if (page == null) SettingsList(prefs, backend, stack) else SettingsPage(page, stack, prefs, conn, songCount, backend, onRestore)
        }
    }
}

internal val subTitles = mapOf(
    "channels" to "Channels", "bots" to "Extra bots", "account" to "Your account", "log" to "Bot activity",
    "sort" to "Sort General", "dupes" to "Find duplicates", "mixlearn" to "Mixes and learning", "weather" to "Weather",
    "conn" to "Connection", "index" to "Chats and channels", "eq" to "Equalizer", "listenbrainz" to "ListenBrainz", "stats" to "Listening statistics", "credentials" to "Bot credentials", "profile" to "Profile",
)

@Composable
private fun SettingsList(prefs: UiPrefs, backend: CoreBackend, stack: MutableList<String>) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = categories.filter { (!it.phoneOnly || isPhone) && (q.isEmpty() || q in "${it.title} ${it.subtitle} ${it.keywords}".lowercase()) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PillField(query, { query = it }, "Search settings")
        Spacer(Modifier.height(12.dp))
        if (q.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp).partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(28.dp))
                    .clickableRow { stack.add("profile") }.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(prefs.profilePhoto, prefs.profileName, 60.dp)
                Text(prefs.profileName, Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.titleLarge)
                AppIcon(Icons.Rounded.ChevronRight, null)
            }
        }
        shown.forEach { c -> SettingsCard(c.icon, c.title, c.subtitle, { stack.add(c.id) }) }
        if (shown.isEmpty()) Text("No settings match", Modifier.padding(24.dp))
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

@Composable
fun Avatar(photo: String?, name: String, size: Dp) {
    if (photo != null) CoverImage(photo, "avatar", size = size, radius = size / 2)
    else Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
internal fun SettingsPage(
    id: String, stack: MutableList<String>, prefs: UiPrefs, conn: ConnectionUi, songCount: Int, backend: CoreBackend, onRestore: () -> Unit,
) {
    var confirm by remember { mutableStateOf<String?>(null) }
    val notice = remember { mutableStateOf<String?>(null) }
    val env = io.max.paperplay.ui.LocalAppEnv.current
    fun pref(k: String) = backend.pref(k)
    when (id) {
        "style" -> {
            Text("Pick Material 3 or glass for each part, or all at once.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionLabel("All parts")
            ChoiceRow(Look.entries, if (LookGroup.entries.all { prefs.look(it) == Look.Glass }) Look.Glass else Look.Material, { it.name }, { l ->
                LookGroup.entries.forEach { prefs.setLook(it, l) }
            })
            LookGroup.entries.forEach { g ->
                SectionLabel(g.label)
                Text(g.hint, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceRow(Look.entries, prefs.look(g), { it.name }, { prefs.setLook(g, it) })
            }
        }
        "appearance" -> {
            SectionLabel("Theme")
            ChoiceRow(ThemeMode.entries, prefs.themeMode, { it.label }, prefs::putThemeMode)
            SectionLabel("Icons")
            ChoiceRow(IconSet.entries, prefs.iconSet, { it.label }, prefs::putIconSet)
            Text("Applies to the app on Android and desktop. Only the icons used by PaperPlay are included.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow("True black", prefs.amoled, prefs::putAmoled, Icons.Rounded.DarkMode, "Pure black surfaces on dark, easier on OLED screens")
            SectionLabel("Accent")
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp)) {
                Swatch(MaterialTheme.colorScheme.primary, prefs.accent == "dynamic", "Dynamic") { prefs.putAccent("dynamic") }
                Swatch(MaterialTheme.colorScheme.tertiary, prefs.accent == "cover", "Cover") { prefs.putAccent("cover") }
                accentOptions.forEach { a -> Swatch(Color(a.color), prefs.accent == a.id, a.label) { prefs.putAccent(a.id) } }
            }
            SectionLabel("Display")
            SwitchRow("Cover gradient", prefs.coverGradient, prefs::putCoverGradient, Icons.Rounded.Gradient, "The current cover's colours behind the app")
            SwitchRow("Now playing panel", prefs.nowPlayingPanel, prefs::putNowPlayingPanel, Icons.Rounded.MusicNote, "Shown beside the lists on wide screens")
            SliderRow("Corner radius: ${prefs.cornerRadius.toInt()}%", prefs.cornerRadius, 0f..100f, { prefs.putCornerRadius(it) })
        }
        "nav" -> {
            SwitchRow("Floating bar", prefs.floatingNav, prefs::putFloatingNav, Icons.Rounded.ViewAgenda, "Off docks the bar to the screen edges")
            SectionLabel("Layout")
            ChoiceRow(NavLayout.entries, prefs.navLayout, { it.label }, { prefs.setNav(layout = it) })
            Text(prefs.navLayout.hint, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionLabel("Selection")
            ChoiceRow(SelectionLook.entries, prefs.selectionLook, { it.label }, { prefs.setNav(look = it) })
            SliderRow("Corner radius: ${prefs.cornerRadius.toInt()}%", prefs.cornerRadius, 0f..100f, { prefs.putCornerRadius(it) })
        }
        "glass" -> {
            Text("Applies to every glass part.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SliderRow("Blur: ${prefs.glassBlur.toInt()}", prefs.glassBlur, 0f..40f, { prefs.setGlass(blur = it) })
            SliderRow("Tint: ${(prefs.glassTint * 100).toInt()}%", prefs.glassTint, 0f..1f, { prefs.setGlass(tint = it) })
            Text("Lens and blur need Android 12 or newer; older phones get a plain tint.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "telegram" -> {
            RateSlider("reading", backend, notice)
            RateSlider("accountCopy", backend, notice)
            NavRow("Channels", "See what's added, add more, and choose the main chat", Icons.Rounded.Forum) { stack.add("channels") }
            NavRow("Bot credentials", "API id, API hash and bot token", Icons.Rounded.Key) { stack.add("credentials") }
            NavRow("Extra bots", "More bots share the uploading", Icons.Rounded.SmartToy) { stack.add("bots") }
            NavRow("Your account (optional)", "Log in as yourself to read and copy from your chats", Icons.Rounded.Person) { stack.add("account") }
            NavRow("Chats and channels", "Index songs in your chats and copy them to the main chat", Icons.Rounded.Forum) { stack.add("index") }
            NavRow("Bot activity since the app opened", "Message reads, downloads, uploads and rate limits", Icons.Rounded.MonitorHeart) { stack.add("log") }
            SettingsRow("Log out", icon = Icons.Rounded.Logout, subtitle = "Forgets the bot's credentials. Your library stays on this device", onClick = { confirm = "logout" })
        }
        "library" -> {
            RateSlider("filing", backend, notice)
            NavRow("Sort General", "Suggests topics for songs in General. Nothing moves till you confirm", Icons.Rounded.DriveFileMove) { stack.add("sort") }
            NavRow("Find duplicates", "Repeated songs in your main chat, and deleting them", Icons.Rounded.ContentCopy) { stack.add("dupes") }
            SettingsRow("Full rescan", icon = Icons.Rounded.Sync, subtitle = "Also removes songs deleted while the app was closed", onClick = { confirm = "rescan" })
            RateSlider("lookups", backend, notice)
            RateSlider("covers", backend, notice)
            SettingsRow("Songs in your library", icon = Icons.Rounded.MusicNote, subtitle = "$songCount")
        }
        "mixes" -> {
            NavRow("Mixes and learning", "What your taste is learned from, and clearing it", Icons.Rounded.AutoAwesome) { stack.add("mixlearn") }
            NavRow("Weather", "Add your city for weather mixes", Icons.Rounded.Cloud) { stack.add("weather") }
        }
        "uploads" -> {
            RateSlider("uploads", backend, notice)
            var parallel by remember { mutableStateOf((pref("upload_parallel")?.toIntOrNull() ?: 6).coerceIn(1, 10)) }
            SliderRow("Uploads at once: $parallel", parallel.toFloat(), 1f..10f, { parallel = it.toInt() }, steps = 8,
                hint = "Telegram lets a bot post about 20 messages a minute in one group, so keep this modest.",
                onDone = { backend.setPref("upload_parallel", "$parallel") })
            var slower by remember { mutableStateOf(pref("upload_slower") == "true") }
            SwitchRow("Upload more slowly", slower, { slower = it; backend.setPref("upload_slower", "$it") }, Icons.Rounded.HourglassBottom, "30-60 seconds between songs instead of 8-20. For really big uploads.")
            var skip by remember { mutableStateOf(pref("upload_skip_known") != "false") }
            SwitchRow("Skip songs already in my library", skip, { skip = it; backend.setPref("upload_skip_known", "$it") }, Icons.Rounded.Rule, "Matches the file name and size")
        }
        "spotify" -> SpotifyPage(backend)
        "listening" -> {
            NavRow("Listening statistics", "Time listened, top songs and artists", Icons.Rounded.BarChart) { stack.add("stats") }
            NavRow("ListenBrainz", "Send what you listen to", Icons.Rounded.Sync) { stack.add("listenbrainz") }
            NavRow("Equalizer", icon = Icons.Rounded.Equalizer) { stack.add("eq") }
        }
        "stats" -> StatsPage(backend)
        "listenbrainz" -> ListenBrainzPage(backend)
        "eq" -> EqualizerPage(backend)
        "devices" -> {
            if (isPhone) {
                var keep by remember { mutableStateOf(pref("keep_running") != "false") }
                SwitchRow("Keep running in the background", keep, { keep = it; backend.setPref("keep_running", "$it"); env?.setKeepRunning?.invoke(it) },
                    Icons.Rounded.Sync, "Syncing carries on after you leave. A notification shows what the app is doing")
            }
            NavRow("Connection", "No devices connected. Addresses and encryption.", Icons.Rounded.SettingsEthernet) { stack.add("conn") }
        }
        "storage" -> {
            var limit by remember { mutableStateOf(pref("cache_limit")?.toLongOrNull() ?: (2L shl 30)) }
            val idx = cacheLimits.indexOfFirst { it >= limit }.coerceAtLeast(0)
            SettingsRow("Cache limit: ${bytesText(cacheLimits[idx])}", icon = Icons.Rounded.Storage, subtitle = "Pinned songs are not counted")
            SliderRow("Cache size", idx.toFloat(), 0f..(cacheLimits.size - 1f), { limit = cacheLimits[it.toInt()] }, steps = cacheLimits.size - 2,
                onDone = { backend.setPref("cache_limit", "$limit") })
            SettingsRow("Clear cache", icon = Icons.Rounded.CleaningServices, subtitle = "Deletes streamed songs stored on this device. They download again when played", onClick = { confirm = "clearcache" })
            SettingsRow("Save backup", icon = Icons.Rounded.SaveAlt, subtitle = "Not built in this app yet", enabled = false)
            SettingsRow("Restore backup", icon = Icons.Rounded.Restore, subtitle = "Replaces your current library", onClick = { confirm = "restore" })
        }
        "channels" -> ChannelsPage(conn, backend)
        "credentials" -> Column(Modifier.padding(16.dp)) {
            Text("Saving reconnects the bot with the new details.", Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            CredentialsForm(backend::saveCredentials, { stack.removeAt(stack.lastIndex) })
        }
        "profile" -> {
            var name by remember { mutableStateOf(prefs.profileName) }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(prefs.profilePhoto, prefs.profileName, 96.dp)
                val pick = env?.pickPhoto
                if (pick != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ pick { path -> if (path != null) prefs.putProfilePhoto(path) } }) { Text("Change photo") }
                    if (prefs.profilePhoto != null) OutlinedButton({ prefs.putProfilePhoto(null) }) { Text("Remove photo") }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                Button({ prefs.putProfileName(name.trim().ifEmpty { "Listener" }) }) { Text("Save name") }
            }
        }
        "mixlearn" -> MixLearnPage(backend) { confirm = "clearlearned" }
        "weather" -> WeatherPage(backend)
        "conn" -> ConnectionPage(conn, backend)
        "log" -> LogPage(backend)
        "dupes" -> DuplicatesPage(backend)
        "account" -> AccountPage(backend) { confirm = "accountout" }
        "index" -> IndexPage(backend, stack)
        else -> if (id.startsWith("index:")) ChatSongsPage(backend, id.removePrefix("index:").toLongOrNull() ?: 0L, stack) else NotBuilt(subTitles[id] ?: id)
    }
    notice.value?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    when (confirm) {
        "rescan" -> ConfirmDialog("Run a full rescan?", "Re-reads every message in your music chats. Takes a few minutes and sends Telegram a lot of requests.", "Rescan", backend::rescan) { confirm = null }
        "restore" -> ConfirmDialog("Restore a backup?", "This replaces your library, playlists and favorites with the backup.", "Restore", onRestore) { confirm = null }
        "clearcache" -> ConfirmDialog("Clear the cache?", "Streamed songs stored on this device are deleted. Stop playback first. They download again when played.", "Clear", { backend.clearCache { notice.value = it } }) { confirm = null }
        "accountout" -> ConfirmDialog("Log out of your account?", "Your index stays on this device. Chats and channels need a new login.", "Log out", { backend.services.scope.launch { backend.account.logOut() } }) { confirm = null }
        "clearlearned" -> ConfirmDialog("Clear what mixes have learned?", "Forgets play and skip counts and your \"doesn't fit this mix\" choices. Songs and playlists stay.", "Clear", backend::clearLearned) { confirm = null }
        "logout" -> ConfirmDialog("Log out?", "The bot's API id, hash and token are forgotten and the setup screen shows again. Songs, playlists and settings stay.", "Log out", backend::logOut) { confirm = null }
    }
}

@Composable
private fun RateSlider(key: String, backend: CoreBackend, notice: androidx.compose.runtime.MutableState<String?>) {
    val w = rateWorks.getValue(key)
    var v by remember { mutableStateOf(backend.pref(w.key)?.toIntOrNull()?.coerceIn(1, 1200) ?: w.default) }
    SliderRow("${w.label}: $v a minute", v.toFloat(), 1f..120f, { v = it.toInt() }, hint = "Faster gets done sooner, but Telegram may slow the bot down if it's pushed too hard.",
        onDone = { backend.setPref(w.key, "$v") })
}

@Composable
private fun ChannelsPage(conn: ConnectionUi, backend: CoreBackend) {
    var mainChatId by remember { mutableStateOf(backend.mainChatId()) }
    var showAdd by remember { mutableStateOf(false) }
    val mainChat = conn.chats.firstOrNull { it.id == mainChatId }
    val otherChats = conn.chats.filterNot { it.id == mainChatId }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when {
                conn.ready -> "Connected as your bot."
                conn.state.isEmpty() -> "Not connected."
                else -> "Connecting… (${conn.state.removePrefix("authorizationState")})"
            },
        )
        if (conn.error != null) Text(conn.error, color = MaterialTheme.colorScheme.error)
        if (!conn.ready) OutlinedButton(backend::connect) { Text("Reconnect") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            IconButton({ showAdd = true }) { AppIcon(Icons.Rounded.Add, "Add channel") }
            OutlinedButton(backend::rescan, enabled = conn.ready && conn.chats.isNotEmpty()) { Text("Scan") }
        }
        SectionLabel("Main chat")
        if (mainChat != null) {
            SettingsRow(
                mainChat.title,
                icon = Icons.Rounded.Forum,
                subtitle = "${mainChat.id}  •  Used for filing and duplicates",
            )
        } else {
            Text(
                if (conn.chats.isEmpty()) "Add a channel to choose the main chat."
                else "Choose a channel below as the main chat.",
                Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionLabel("Other channels")
        if (otherChats.isEmpty()) {
            Text(
                if (conn.chats.isEmpty()) "No channels added yet." else "No other channels.",
                Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        otherChats.forEach { chat ->
            SettingsRow(
                chat.title,
                icon = Icons.Rounded.Forum,
                subtitle = chat.id.toString(),
                trailing = {
                    OutlinedButton({
                        backend.setMainChat(chat.id)
                        mainChatId = chat.id
                    }) { Text("Set as main") }
                },
            )
        }
        if (conn.scanText != null) Text(conn.scanText)
        if (!conn.ready) Text("Connect first to add a chat.", style = MaterialTheme.typography.bodySmall)
    }
    if (showAdd) AddChannelDialog(backend, conn.ready) { showAdd = false }
}

@Composable
private fun AddChannelDialog(backend: CoreBackend, connected: Boolean, onDismiss: () -> Unit) {
    var chatId by remember { mutableStateOf("") }
    var chatTitle by remember { mutableStateOf("") }
    var chatError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add channel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    chatId,
                    { chatId = it },
                    label = { Text("Chat id") },
                    placeholder = { Text("-1001234567890") },
                    singleLine = true,
                )
                OutlinedTextField(
                    chatTitle,
                    { chatTitle = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                )
                if (chatError != null) Text(chatError!!, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    chatError = backend.addChat(chatId, chatTitle)
                    if (chatError == null) onDismiss()
                },
                enabled = connected,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Preview
@Composable
private fun AvatarPreview() {
    Avatar(photo = null, name = "Alex", size = 80.dp)
}

