package io.max.paperplay.ui.app

import io.max.paperplay.ui.components.AppIcon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Full-width bar; a rounded glass strip inset from the edges when the Top bars part is glass. */
@Composable
fun Modifier.barSurface(): Modifier =
    if (glassLook(LookGroup.Bars)) fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        .partSurface(LookGroup.Bars, MaterialTheme.colorScheme.surfaceContainer, appShape(24.dp))
    else fillMaxWidth()

/** Large title with actions on the right, like the Flutter top bars. */
@Composable
fun TopBar(title: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(modifier.barSurface().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
        actions()
    }
}

@Composable
fun ProfileScreen(
    songs: Int,
    favorites: Int,
    playlists: Int,
    conn: ConnectionUi,
    prefs: UiPrefs,
    mainChatTitle: String?,
    onSettings: (String?) -> Unit,
    onTasks: () -> Unit,
    onLibraryTab: (Int) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = bottomPadding)) {
        item {
            TopBar("Profile") { IconButton({ onSettings(null) }) { AppIcon(Icons.Rounded.Settings, "Settings") } }
        }
        item {
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(36.dp))
                    .clickable { onSettings("profile") }
                    .padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(prefs.profilePhoto, prefs.profileName, 150.dp)
                Text(prefs.profileName, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(Icons.Rounded.MusicNote, songs, "Songs", Modifier.weight(1f)) { onLibraryTab(0) }
                Stat(Icons.Rounded.Favorite, favorites, "Favorites", Modifier.weight(1f)) { onLibraryTab(4) }
                Stat(Icons.Rounded.PlaylistPlay, playlists, "Playlists", Modifier.weight(1f)) { onLibraryTab(3) }
            }
        }
        item {
            var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(28.dp))) {
                Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(Icons.Rounded.LibraryMusic, null, Modifier.padding(end = 16.dp))
                    Text("Library", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    AppIcon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
                if (open) {
                    NavRow(if (conn.chats.size == 1) "1 channel" else "${conn.chats.size} channels", icon = Icons.Rounded.Forum, subtitle = mainChatTitle?.let { "Main chat: $it" } ?: "No chat added yet") {
                        onSettings("channels")
                    }
                    NavRow("Chats and channels", icon = Icons.Rounded.Forum, subtitle = "Index songs in your own chats") { onSettings("index") }
                    NavRow("Tasks", icon = Icons.Rounded.TaskAlt, subtitle = conn.scanText ?: "Nothing running", onClick = onTasks)
                    NavRow("Listening", icon = Icons.Rounded.BarChart, subtitle = "Time listened, top songs and artists") {
                        onSettings("stats")
                    }
                    NavRow("Duplicates", icon = Icons.Rounded.ContentCopy, subtitle = "Find repeated songs and remove the extras") {
                        onSettings("library")
                    }
                    NavRow("Song info left", icon = Icons.Rounded.Rule, subtitle = "See which songs still lack info or a cover") {
                        onSettings("missing")
                    }
                }
            }
        }
        item {
            ProfileSection("Devices", Icons.Rounded.Devices) {
                NavRow("No other device connected", icon = Icons.Rounded.SettingsEthernet, subtitle = "Sync and play on your other devices") {
                    onSettings("devices")
                }
            }
        }
        item {
            ProfileSection("Storage", Icons.Rounded.Storage) {
                NavRow("Cache and backup", icon = Icons.Rounded.CleaningServices, subtitle = "Manage cached songs and library backups") {
                    onSettings("storage")
                }
            }
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, n: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(32.dp))
            .clickable(onClick = onClick).padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            AppIcon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("$n", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProfileSection(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(28.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, null, Modifier.padding(end = 16.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            AppIcon(Icons.Rounded.ExpandLess, null)
        }
        content()
    }
}

@Preview
@Composable
private fun TopBarPreview() {
    TopBar(title = "Profile")
}

@Preview
@Composable
private fun ProfileScreenPreview() {
    val dummyConn = ConnectionUi(ready = true, state = "Connected")
    val dummyPrefs = UiPrefs({ null }, { _, _ -> })
    ProfileScreen(
        songs = 120,
        favorites = 15,
        playlists = 3,
        conn = dummyConn,
        prefs = dummyPrefs,
        mainChatTitle = null,
        onSettings = {},
        onTasks = {},
        onLibraryTab = {},
        bottomPadding = 0.dp,
    )
}

