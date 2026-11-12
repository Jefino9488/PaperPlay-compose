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
import androidx.compose.runtime.collectAsState
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
import com.kyant.backdrop.backdrops.layerBackdrop
import io.max.paperplay.ui.isPhone

internal class Category(
    val id: String, val title: String, val subtitle: String, val keywords: String, val icon: ImageVector,
    val phoneOnly: Boolean = false, private val desktopSubtitle: String? = null,
) {
    /** The line shown under the title; some pages have fewer options on the desktop. */
    val text: String get() = if (isPhone) subtitle else desktopSubtitle ?: subtitle
}

internal val categories = listOf(
    Category("style", "Appearance", "Theme, colors, icons, glass look, sidebar and bar", "style look glass material expressive blur theme dark amoled accent color icons nav bar sidebar floating radius selection", Icons.Rounded.AutoAwesome),
    Category("glass", "Glass", "Blur and tint for every glass part", "glass blur refraction tint", Icons.Rounded.BlurOn),
    Category("listening", "Listening", "ListenBrainz, volume levelling, equalizer", "listenbrainz scrobble listens loudness replaygain equalizer eq", Icons.Rounded.GraphicEq),
    Category("library", "Library", "Repair, sorting, duplicates, rescan, song info", "speed rate rescan duplicates sort general covers info repair fix missing songs metadata organize tidy", Icons.Rounded.LibraryMusic),
    Category("mixes", "Mixes", "Taste learning and weather mixes", "mix taste learning weather city", Icons.Rounded.AutoAwesome),
    Category("uploads", "Uploads", "Upload pace and skipping known songs", "upload slow skip speed parallel", Icons.Rounded.Upload),
    Category("spotify", "Spotify import", "Import playlists from Spotify", "spotify playlist import", Icons.Rounded.PlaylistAdd),
    Category("devices", "Devices and background", "Connect devices, keep running", "device connection sync background battery", Icons.Rounded.Devices, desktopSubtitle = "Connect other devices"),
    Category("storage", "Storage and backup", "Cache size, backup and restore", "cache storage backup restore clear", Icons.Rounded.Storage),
    Category("telegram", "Telegram", "Channels, your account, bot activity, log out", "bot channels account log tdlib logout speed reading copy", Icons.Rounded.Send),
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
    onRestore: () -> Unit, onExit: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier, deep: Boolean = false,
) {
    val page = stack.lastOrNull()
    val title = categories.firstOrNull { it.id == page }?.title ?: settingsPageTitle(page) ?: "Settings"
    Column(modifier) {
        BackTopBar(title, onBack = { if (stack.isEmpty() || (deep && stack.size <= 1)) onExit() else stack.removeAt(stack.lastIndex) }) {
            val loggedIn by backend.account.loggedIn.collectAsState()
            if (page == "channels" && loggedIn) IconButton({ stack.add("index") }) { AppIcon(Icons.Rounded.Forum, "Your chats and channels") }
        }
        // The chat pages hold long lists, so they scroll lazily instead of inside the shared scroll column.
        val isLazy = page == "index" || page == "missing" || page == "log" || page?.startsWith("index:") == true
        if (page == "index") IndexPage(backend, stack, bottomPadding)
        else if (page != null && page.startsWith("index:")) ChatSongsPage(backend, page.removePrefix("index:").toLongOrNull() ?: 0L, stack, bottomPadding)
        else Column(
            Modifier.fillMaxSize()
                .then(if (isLazy) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(bottom = bottomPadding)
        ) {
            if (page == null) SettingsList(prefs, stack) else SettingsPage(page, stack, prefs, conn, songCount, backend, onRestore)
        }
    }
}

internal val subTitles = mapOf(
    "channels" to "Channels and chats", "bots" to "Bots", "account" to "Your account", "log" to "Activity log",
    "repair" to "Repair library", "recover" to "Recover songs", "folders" to "Music folders", "exports" to "Saved export lists", "cache" to "Cache manager", "sort" to "Sort General", "dupes" to "Find duplicates", "missing" to "Song info left", "uploadhub" to "Upload songs", "mixlearn" to "Mixes and learning", "weather" to "Weather",
    "permissions" to "Permissions", "conn" to "Connection", "netplay" to "Network play", "index" to "Chats and channels", "eq" to "Equalizer", "listenbrainz" to "ListenBrainz", "stats" to "Listening statistics", "credentials" to "Bots", "profile" to "Profile",
)

@Composable
private fun SettingsList(prefs: UiPrefs, stack: MutableList<String>) {
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
        shown.forEach { c -> SettingsCard(c.icon, c.title, c.text, { stack.add(c.id) }) }
        val tour = io.max.paperplay.ui.LocalAppEnv.current?.showTour
        if (tour != null && (q.isEmpty() || "welcome tour intro about" .contains(q))) {
            SettingsCard(Icons.Rounded.MusicNote, "Welcome tour", "Show the introduction again", { tour.value = true })
        }
        if (shown.isEmpty() && q.isNotEmpty() && !"welcome tour intro about".contains(q)) Text("No settings match", Modifier.padding(24.dp))
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
            SectionLabel("Theme")
            ChoiceRow(ThemeMode.entries, prefs.themeMode, { it.label }, prefs::putThemeMode)
            SectionLabel("Icons")
            ChoiceRow(IconSet.entries, prefs.iconSet, { it.label }, prefs::putIconSet)
            Text("Applies to the app on Android and desktop. Only the icons used by PaperPlay are included.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow("True black", prefs.amoled, prefs::putAmoled, Icons.Rounded.DarkMode, "Pure black surfaces on dark, easier on OLED screens")
            SectionLabel("Accent")
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp)) {
                if (isPhone) {
                    Swatch(MaterialTheme.colorScheme.primary, prefs.accent == "dynamic", "Dynamic") { prefs.putAccent("dynamic") }
                    Swatch(MaterialTheme.colorScheme.tertiary, prefs.accent == "cover", "Cover") { prefs.putAccent("cover") }
                } else {
                    // No wallpaper colour on the desktop: the accent follows the playing song's cover.
                    Swatch(MaterialTheme.colorScheme.tertiary, prefs.accent == "cover" || prefs.accent == "dynamic", "Album art") { prefs.putAccent("cover") }
                }
                accentOptions.forEach { a -> Swatch(Color(a.color), prefs.accent == a.id, a.label) { prefs.putAccent(a.id) } }
            }
            SectionLabel("Display")
            BackgroundSettings(prefs)
            if (!isPhone) SwitchRow("Now playing panel", prefs.nowPlayingPanel, prefs::putNowPlayingPanel, Icons.Rounded.MusicNote, "Shown beside the lists on wide screens")
            SliderRow("Corner radius: ${prefs.cornerRadius.toInt()}%", prefs.cornerRadius, 0f..100f, { prefs.putCornerRadius(it) })
            SectionLabel("Look")
            Text("Pick Material 3 or glass for each part, or all at once.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionLabel("All parts")
            // On desktop the nav part is the sidebar, drawn with blur and tint like the cards.
            val groups = LookGroup.entries.filter { !isPhone || it != LookGroup.Player }
            ChoiceRow(Look.entries, if (groups.all { prefs.look(it) == Look.Glass }) Look.Glass else Look.Material, { it.name }, { l ->
                groups.forEach { prefs.setLook(it, l) }
            })
            if (!io.max.paperplay.ui.glassLensSupported()) {
                Text("On desktop, glass is blur, tint and gradient. The sidebar uses the Navigation setting; blur and tint are on the Glass page.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            @Composable
            fun part(g: LookGroup) {
                SectionLabel(g.label)
                Text(g.hint, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceRow(Look.entries, prefs.look(g), { it.name }, { prefs.setLook(g, it) })
            }
            // Wide screens lay the parts out two to a row instead of one long column.
            if (isPhone) groups.forEach { part(it) } else androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(), maxItemsInEachRow = 2,
            ) { groups.forEach { g -> Column(Modifier.weight(1f)) { part(g) } } }
            SectionLabel(if (isPhone) "Navigation bar" else "Sidebar")
            NavSettings(prefs)
            if (!isPhone) PlayerBarSettings(prefs)
        }
        "nav" -> NavSettings(prefs)
        "glass" -> {
            GlassPreview(prefs)
            Text("Applies to every part set to Glass on the Appearance page.", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val g = prefs.glass
            SectionLabel("Material")
            if (!isPhone) SwitchRow("Blur the background", prefs.desktopBlur, prefs::putDesktopBlur, null, "Off is much faster; glass is then a tint and an edge line")
            SliderRow("Blur: ${g.blur.toInt()} dp", g.blur, 0f..40f, { prefs.updateGlass(g.copy(blur = it)) })
            if (io.max.paperplay.ui.glassLensSupported()) {
                SliderRow("Refraction height: ${g.lensHeight.toInt()} dp", g.lensHeight, 0f..48f, { prefs.updateGlass(g.copy(lensHeight = it)) })
                SliderRow("Refraction amount: ${g.lensAmount.toInt()} dp", g.lensAmount, 0f..48f, { prefs.updateGlass(g.copy(lensAmount = it)) })
            }
            SliderRow("Colour saturation: ${"%.2f".format(g.saturation)}", g.saturation, 0f..2.5f, { prefs.updateGlass(g.copy(saturation = it)) })
            SliderRow("Tint: ${(g.tint * 100).toInt()}%", g.tint, 0f..1f, { prefs.updateGlass(g.copy(tint = it)) })
            if (io.max.paperplay.ui.glassLensSupported()) {
                SwitchRow("Depth effect", g.depth, { prefs.updateGlass(g.copy(depth = it)) })
                SwitchRow("Colour fringing", g.chromatic, { prefs.updateGlass(g.copy(chromatic = it)) })
            }
            SectionLabel("Edge")
            SliderRow("Edge line width: ${"%.1f".format(g.edgeWidth)} dp", g.edgeWidth, 0f..4f, { prefs.updateGlass(g.copy(edgeWidth = it)) })
            SliderRow("Edge brightness: ${"%.2f".format(g.edgeAlpha)}", g.edgeAlpha, 0f..1f, { prefs.updateGlass(g.copy(edgeAlpha = it)) })
            Text("Edge reflection", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyLarge)
            ChoiceRow(listOf(0, 1, 2), g.edgeStyle, { listOf("Directional", "Ambient", "Plain")[it] }, { prefs.updateGlass(g.copy(edgeStyle = it)) })
            SectionLabel("Shadow")
            SliderRow("Outer shadow size: ${g.shadowRadius.toInt()} dp", g.shadowRadius, 0f..40f, { prefs.updateGlass(g.copy(shadowRadius = it)) })
            SliderRow("Outer shadow strength: ${"%.2f".format(g.shadowAlpha)}", g.shadowAlpha, 0f..0.5f, { prefs.updateGlass(g.copy(shadowAlpha = it)) })
            SliderRow("Inner glow size: ${g.innerRadius.toInt()} dp", g.innerRadius, 0f..40f, { prefs.updateGlass(g.copy(innerRadius = it)) })
            SliderRow("Inner glow strength: ${"%.2f".format(g.innerAlpha)}", g.innerAlpha, 0f..0.5f, { prefs.updateGlass(g.copy(innerAlpha = it)) })
            OutlinedButton({ prefs.updateGlass(GlassStyle()) }, Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) { Text("Reset to defaults") }
            if (LookGroup.entries.none { prefs.look(it) == Look.Glass }) {
                Text("No part uses Glass yet. Choose it on the Appearance page.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (!io.max.paperplay.ui.glassLensSupported()) {
                Text("Glass here is blur, tint and gradient.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!io.max.paperplay.ui.glassEffectsSupported()) {
                Text("Blur needs Android 12 or newer; this device gets a plain tint.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Dialogs, sheets and menus open in their own window, so they get the tint but not the blur.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        "telegram" -> {
            RateSlider("reading", backend, notice)
            RateSlider("accountCopy", backend, notice)
            NavRow("Channels and chats", "Added channels, the main chat, and your own chats and channels", Icons.Rounded.Forum) { stack.add("channels") }
            NavRow("Bots", "Main bot credentials and extra bots for uploading", Icons.Rounded.SmartToy) { stack.add("bots") }
            val accountStep by backend.account.step.collectAsState()
            NavRow(
                "Your account (optional)",
                if (accountStep == io.max.paperplay.core.account.LoginStep.Ready) "Logged in. Your chats can be read and copied from" else "Log in as yourself to read and copy from your chats",
                Icons.Rounded.Person,
            ) { stack.add("account") }
            NavRow("Activity log", "Reads, downloads, uploads, rate limits and problems. Search, copy and save", Icons.Rounded.MonitorHeart) { stack.add("log") }
            SettingsRow("Log out", icon = Icons.Rounded.Logout, subtitle = "Forgets the bot's credentials. Your library stays on this device", onClick = { confirm = "logout" })
        }
        "library" -> {
            NavRow("Repair library", "Read every chat again and fill in what is missing", Icons.Rounded.Build) { stack.add("repair") }
            NavRow("Music folders", "Songs on this device, optionally sent to Telegram", Icons.Rounded.Folder) { stack.add("folders") }
            NavRow("Recover songs the bot cannot read", "Songs another bot posted. Your account posts them again", Icons.Rounded.Restore) { stack.add("recover") }
            RateSlider("filing", backend, notice)
            NavRow("Sort General", "Suggests topics for songs in General. Nothing moves till you confirm", Icons.Rounded.DriveFileMove) { stack.add("sort") }
            NavRow("Find duplicates", "Repeated songs in your main chat, and deleting them", Icons.Rounded.ContentCopy) { stack.add("dupes") }
            NavRow("Saved export lists", "Songs not in your library, with Spotify and Apple Music links", Icons.Rounded.IosShare) { stack.add("exports") }
            SettingsRow("Full rescan", icon = Icons.Rounded.Sync, subtitle = "Also removes songs deleted while the app was closed", onClick = { confirm = "rescan" })
            RateSlider("lookups", backend, notice)
            val lyricsRunning by backend.lyricsRunning.collectAsState()
            var lyricsAuto by remember { mutableStateOf(backend.lyricsAuto) }
            SwitchRow("Fill in missing info in the background", lyricsAuto, { lyricsAuto = it; backend.setLyricsAuto(it) }, Icons.Rounded.Lyrics, "Looks up song info, covers, genres and lyrics for songs that lack them, ahead of time. Needs song lookups on")
            SettingsRow(
                if (lyricsRunning) "Stop fetching lyrics" else "Fetch missing lyrics",
                icon = Icons.Rounded.Lyrics,
                subtitle = "Looks up lyrics on LRCLIB for songs without them. Progress shows in Tasks",
                onClick = { if (lyricsRunning) backend.stopLyricsRun() else backend.startLyricsRun() },
            )
            RateSlider("covers", backend, notice)
            LibraryStatusRows(backend) { stack.add("missing") }
        }
        "mixes" -> {
            NavRow("Mixes and learning", "What your taste is learned from, and clearing it", Icons.Rounded.AutoAwesome) { stack.add("mixlearn") }
            NavRow("Weather", "Add your city for weather mixes", Icons.Rounded.Cloud) { stack.add("weather") }
        }
        "uploads" -> {
            NavRow("Upload songs", "Pick songs, see the queue, retry failures", Icons.Rounded.Upload) { stack.add("uploadhub") }
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
            val levelOn by backend.normalizeOn.collectAsState()
            SwitchRow("Even out loudness", levelOn, backend::setNormalize, Icons.Rounded.GraphicEq,
                if (isPhone) "Pulls loud peaks down and lifts quiet songs. Needs Android 9 or newer" else "Evens out loud and quiet songs. Applies from the next song")
            var restore by remember { mutableStateOf(backend.sessionRestore) }
            SwitchRow("Restore last session", restore, { restore = it; backend.setSessionRestore(it) }, Icons.Rounded.Sync,
                "Opens with the last queue and position, paused")
        }
        "stats" -> StatsPage(backend)
        "listenbrainz" -> ListenBrainzPage(backend)
        "eq" -> EqualizerPage(backend)
        "devices" -> {
            if (isPhone) {
                var keep by remember { mutableStateOf(pref("keep_running") == "true") }
                SwitchRow("Keep running in the background", keep, { keep = it; backend.setPref("keep_running", "$it"); env?.setKeepRunning?.invoke(it) },
                    Icons.Rounded.Sync, "Syncing carries on after you leave. A notification shows what the app is doing")
            }
            if (env?.permissions != null) NavRow("Permissions", "Notifications, Bluetooth and battery: what is allowed", Icons.Rounded.Security) { stack.add("permissions") }
            NavRow("Connection", "Devices on this network, pairing, addresses and encryption", Icons.Rounded.SettingsEthernet) { stack.add("conn") }
            NavRow("Network play", "Listen together with people on the same network", Icons.Rounded.Devices) { stack.add("netplay") }
        }
        "storage" -> {
            var limit by remember { mutableStateOf(pref("cache_limit")?.toLongOrNull() ?: (2L shl 30)) }
            val idx = cacheLimits.indexOfFirst { it >= limit }.coerceAtLeast(0)
            val used by androidx.compose.runtime.produceState(0L to 0L, notice.value) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { backend.cacheUsed() } }
            SettingsRow("Used: ${bytesText(used.second)}", icon = Icons.Rounded.Storage, subtitle = "Pinned songs: ${bytesText(used.first)}, not counted in the limit")
            SettingsRow("Cache limit: ${bytesText(cacheLimits[idx])}", icon = Icons.Rounded.Storage, subtitle = "Pinned songs are not counted")
            SliderRow("Cache size", idx.toFloat(), 0f..(cacheLimits.size - 1f), { limit = cacheLimits[it.toInt()] }, steps = cacheLimits.size - 2,
                onDone = { backend.setPref("cache_limit", "$limit") })
            NavRow("Cache manager", "Songs stored on this device, unpin and clear", Icons.Rounded.Storage) { stack.add("cache") }
            SettingsRow("Clear cache", icon = Icons.Rounded.CleaningServices, subtitle = "Deletes streamed songs stored on this device. They download again when played", onClick = { confirm = "clearcache" })
            val backupBusy by backend.backupProgress.collectAsState()
            SettingsRow("Save backup", icon = Icons.Rounded.SaveAlt, subtitle = "Song info, covers, playlists, favorites and layout, as a .zip file",
                enabled = backupBusy == null && BackupFiles.pickDestination != null, onClick = { confirm = "savebackup" })
            SettingsRow("Restore from file", icon = Icons.Rounded.Restore, subtitle = "Replaces your current library", enabled = backupBusy == null, onClick = { confirm = "restore" })
            backupBusy?.let { (stage, fraction) ->
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(stage + (fraction?.let { " (${(it * 100).toInt()}%)" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        "channels" -> ChannelsPage(conn, backend, stack)
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
        "permissions" -> PermissionsPage()
        "conn" -> ConnectionPage(conn, backend)
        "netplay" -> NetplayPage(backend)
        "log" -> LogPage(backend)
        "bots", "credentials" -> BotsPage(backend)
        "repair" -> RepairPage(backend, stack)
        "recover" -> RecoverPage(backend)
        "folders" -> LocalFoldersPage(backend)
        "cache" -> CachePage(backend, notice)
        "dupes" -> DuplicatesPage(backend)
        "exports" -> ExportListsPage(backend)
        "sort" -> SortPage(backend)
        "missing" -> MissingInfoPage(backend)
        "uploadhub" -> UploadsPage(backend)
        "account" -> AccountPage(backend, onOpenIndex = { stack.add("index") }) { confirm = "accountout" }
        "index" -> IndexPage(backend, stack)
        else -> if (id.startsWith("index:")) ChatSongsPage(backend, id.removePrefix("index:").toLongOrNull() ?: 0L, stack) else NotBuilt(subTitles[id] ?: id)
    }
    notice.value?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    when (confirm) {
        "savebackup" -> BackupPasswordDialog({ pw ->
            backend.services.scope.launch { notice.value = backend.saveBackup(pw, "paperplay-backup-${io.max.paperplay.core.app.dayOf(io.max.paperplay.ui.nowMoment().epochMs)}.zip") }
        }) { confirm = null }
        "rescan" -> ConfirmDialog("Run a full rescan?", "Re-reads every message in your music chats. Takes a few minutes and sends Telegram a lot of requests.", "Rescan", backend::rescan) { confirm = null }
        "restore" -> ConfirmDialog("Restore a backup?", "This replaces your library, playlists and favorites with the backup.", "Restore", onRestore) { confirm = null }
        "clearcache" -> ConfirmDialog("Clear the cache?", "Streamed songs stored on this device are deleted. Stop playback first. They download again when played.", "Clear", { backend.clearCache { notice.value = it } }) { confirm = null }
        "accountout" -> ConfirmDialog("Log out of your account?", "Your index stays on this device. Chats and channels need a new login.", "Log out", { backend.services.scope.launch { runCatching { backend.services.indexCloud.save() }; backend.account.logOut() } }) { confirm = null }
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
internal fun AddChannelDialog(backend: CoreBackend, connected: Boolean, onDismiss: () -> Unit) {
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


/** Live sample of the glass material over a colourful backdrop, using the current blur and tint. */
@Composable
private fun GlassPreview(prefs: UiPrefs) {
    val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(150.dp).clip(RoundedCornerShape(28.dp))) {
        Box(
            Modifier.fillMaxSize().layerBackdrop(backdrop)
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(scheme.primary, scheme.tertiary, scheme.secondary))),
        ) {
            Box(Modifier.padding(start = 24.dp, top = 20.dp).size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f)))
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 16.dp).size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)))
        }
        io.max.paperplay.ui.GlassSurface(
            backdrop, Modifier.align(Alignment.Center).fillMaxWidth(0.72f).height(68.dp),
            cornerRadius = 24.dp, blur = prefs.glassBlur.dp, tintAlpha = prefs.glassTint,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Glass preview", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Preview(name = "Settings list phone", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun SettingsListPhonePreview() = io.max.paperplay.ui.PreviewFrame {
    SettingsList(io.max.paperplay.ui.previewPrefs(), mutableListOf())
}

@Preview(name = "Settings list desktop", widthDp = 520, heightDp = 800, showBackground = true)
@Composable
private fun SettingsListDesktopPreview() = io.max.paperplay.ui.PreviewFrame(dark = true) {
    SettingsList(io.max.paperplay.ui.previewPrefs(), mutableListOf())
}

/** Desktop player bar: background, corners and which buttons show. The Material or Glass look is chosen with the other parts above. */
@Composable
private fun PlayerBarSettings(prefs: UiPrefs) {
    SectionLabel("Player bar")
    SwitchRow("Bar background", prefs.playerCard, prefs::putPlayerCard, null, "A solid card behind the bar; Glass always has one")
    SliderRow("Bar corners: ${prefs.playerRadius.toInt()}%", prefs.playerRadius, 0f..100f, { prefs.putPlayerRadius(it) })
    listOf(
        "favorite" to "Favorite", "sleep" to "Sleep timer", "panel" to "Now playing panel", "queue" to "Queue",
        "devices" to "Devices and mini player", "fullscreen" to "Full screen player",
    ).forEach { (id, label) -> SwitchRow(label, prefs.playerButton(id), { prefs.setPlayerButton(id, it) }) }
}

/** Sidebar settings on desktop, the bottom tab bar's on the phone. Shown at the end of Appearance. */
@Composable
private fun NavSettings(prefs: UiPrefs) {
    if (!isPhone) {
        ChoiceRow(Look.entries, prefs.look(LookGroup.Nav), { it.name }, { prefs.setLook(LookGroup.Nav, it) })
        Text("Glass blurs the page background behind the sidebar. Blur and tint are on the Glass page.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel("Selection")
        ChoiceRow(SelectionLook.entries, prefs.selectionLook, { it.label }, { prefs.setNav(look = it) })
        SliderRow("Sidebar corners: ${prefs.barRadius.toInt()}%", prefs.barRadius, 0f..100f, { prefs.setNav(bar = it) })
        SliderRow("Selection corners: ${prefs.selectionRadius.toInt()}%", prefs.selectionRadius, 0f..100f, { prefs.setNav(selection = it) })
        SliderRow("Sidebar width: ${prefs.sidebarWidth.toInt()} dp", prefs.sidebarWidth, 180f..320f, { prefs.putSidebarWidth(it) })
    } else {
        SwitchRow("Floating bar", prefs.floatingNav, prefs::putFloatingNav, Icons.Rounded.ViewAgenda, "Off docks the bar to the screen edges")
        SectionLabel("Layout")
        ChoiceRow(NavLayout.entries, prefs.navLayout, { it.label }, { prefs.setNav(layout = it) })
        Text(prefs.navLayout.hint, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel("Selection")
        ChoiceRow(SelectionLook.entries, prefs.selectionLook, { it.label }, { prefs.setNav(look = it) })
        if (prefs.floatingNav) SliderRow("Corner radius: ${prefs.cornerRadius.toInt()}%", prefs.cornerRadius, 0f..100f, { prefs.putCornerRadius(it) })
        else Text("A docked bar spans the screen edge to edge, so it has no corner radius.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
