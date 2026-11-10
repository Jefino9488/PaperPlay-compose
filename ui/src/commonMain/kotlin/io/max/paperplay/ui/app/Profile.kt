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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun ProfileScreen(songs: Int, favorites: Int, playlists: Int, conn: ConnectionUi, onSettings: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = bottomPadding)) {
        item {
            TopBar("Profile") { IconButton(onSettings) { Icon(Icons.Rounded.Settings, "Settings") } }
        }
        item {
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(36.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(150.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Person, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (conn.ready) "Connected" else "Offline", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(Icons.Rounded.MusicNote, songs, "Songs", Modifier.weight(1f))
                Stat(Icons.Rounded.Favorite, favorites, "Favorites", Modifier.weight(1f))
                Stat(Icons.Rounded.PlaylistPlay, playlists, "Playlists", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, n: Int, label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("$n", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
