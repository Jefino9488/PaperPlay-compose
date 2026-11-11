package io.max.paperplay.ui.app

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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Large title with actions on the right, like the Flutter top bars. */
@Composable
fun TopBar(title: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
        actions()
    }
}

@Composable
fun ProfileScreen(songs: Int, favorites: Int, playlists: Int, conn: ConnectionUi, prefs: UiPrefs, onSettings: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = bottomPadding)) {
        item {
            TopBar("Profile") { IconButton(onSettings) { Icon(Icons.Rounded.Settings, "Settings") } }
        }
        item {
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(36.dp)).padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(prefs.profilePhoto, prefs.profileName, 150.dp)
                Text(prefs.profileName, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(Icons.Rounded.MusicNote, songs, "Songs", Modifier.weight(1f))
                Stat(Icons.Rounded.Favorite, favorites, "Favorites", Modifier.weight(1f))
                Stat(Icons.Rounded.PlaylistPlay, playlists, "Playlists", Modifier.weight(1f))
            }
        }
        item {
            var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(28.dp))) {
                Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LibraryMusic, null, Modifier.padding(end = 16.dp))
                    Text("Library", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
                if (open) {
                    SettingsRow("${conn.chats.size} channels", icon = Icons.Rounded.Forum, subtitle = conn.chats.firstOrNull()?.let { "Main chat: ${it.title}" } ?: "No chat added yet")
                    SettingsRow("Tasks", icon = Icons.Rounded.TaskAlt, subtitle = conn.scanText ?: "Nothing running")
                    SettingsRow("Time listened, top songs and artists", icon = Icons.Rounded.BarChart, subtitle = "Not built in this app yet", enabled = false)
                }
            }
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, n: Int, label: String, modifier: Modifier) {
    Column(
        modifier.partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(32.dp)).padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("$n", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
