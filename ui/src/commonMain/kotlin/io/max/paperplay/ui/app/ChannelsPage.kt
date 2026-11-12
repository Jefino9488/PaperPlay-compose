package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Switch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.AppIcon

internal data class ChannelUi(val id: Long, val title: String)

/** Settings > Telegram > Channels: what is connected, the main chat, and the other chats in the library. */
@Composable
internal fun ChannelsPage(conn: ConnectionUi, backend: CoreBackend, stack: MutableList<String>) {
    var mainChatId by remember { mutableStateOf(backend.mainChatId()) }
    var showAdd by remember { mutableStateOf(false) }
    val scan by backend.scan.collectAsState()
    val loggedIn by backend.account.loggedIn.collectAsState()
    val copyOn = remember { mutableStateMapOf<Long, Boolean>() }
    val all = conn.chats.map { ChannelUi(it.id, it.title) }
    ChannelsContent(
        status = when {
            conn.ready -> "Connected as your bot"
            conn.state.isEmpty() -> "Not connected"
            else -> "Connecting (${conn.state.removePrefix("authorizationState")})"
        },
        error = conn.error, connected = conn.ready,
        main = all.firstOrNull { it.id == mainChatId },
        others = all.filterNot { it.id == mainChatId },
        scanText = scan?.text, scanFraction = scan?.fraction?.toFloat(),
        onReconnect = backend::connect, onAdd = { showAdd = true }, onScan = backend::rescan,
        onSetMain = { backend.setMainChat(it); mainChatId = it },
        onOpenIndex = { stack.add("index") },
        showChats = loggedIn,
        copyOn = { id -> copyOn[id] ?: (backend.pref(io.max.paperplay.core.account.IndexPrefs.channelKey(id)) == "true") },
        onCopy = { id, on -> copyOn[id] = on; backend.setPref(io.max.paperplay.core.account.IndexPrefs.channelKey(id), "$on") },
    )
    if (showAdd) AddChannelDialog(backend, conn.ready) { showAdd = false }
}

@Composable
internal fun ChannelsContent(
    status: String, error: String?, connected: Boolean,
    main: ChannelUi?, others: List<ChannelUi>,
    scanText: String?, scanFraction: Float?,
    onReconnect: () -> Unit, onAdd: () -> Unit, onScan: () -> Unit, onSetMain: (Long) -> Unit, onOpenIndex: () -> Unit,
    showChats: Boolean = false, copyOn: (Long) -> Boolean = { false }, onCopy: (Long, Boolean) -> Unit = { _, _ -> },
) {
    val hasChats = main != null || others.isNotEmpty()
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(10.dp).background(
                            if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape,
                        ),
                    )
                    Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (!connected) OutlinedButton(onReconnect) { Text("Reconnect") }
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (scanText != null) {
                    Text(scanText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (scanFraction != null) LinearProgressIndicator({ scanFraction }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onAdd, Modifier.weight(1f), enabled = connected) {
                Icon(Icons.Rounded.Add, null, Modifier.padding(end = 8.dp))
                Text("Add channel")
            }
            FilledTonalButton(onScan, Modifier.weight(1f), enabled = connected && hasChats) {
                Icon(Icons.Rounded.Sync, null, Modifier.padding(end = 8.dp))
                Text("Scan now")
            }
        }
        if (!connected) Text("Connect first to add or scan a channel.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showChats) NavRow("Your chats and channels", "Chats from your own account: index songs, recover and copy them", Icons.Rounded.Forum, onOpenIndex)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Main chat", Modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (main != null) {
            ChannelCard(main, "Used for filing and duplicates", highlighted = true) {}
        } else {
            Text(
                if (hasChats) "Choose a channel below as the main chat." else "Add a channel to choose the main chat.",
                Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("Other channels", Modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (others.isEmpty()) {
            Text(
                if (hasChats) "No other channels." else "No channels added yet.",
                Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        others.forEach { chat ->
            ChannelCard(chat, "Library channel", highlighted = false, below = {
                Row(Modifier.padding(start = 16.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Copy songs to the main chat", style = MaterialTheme.typography.bodyMedium)
                        Text("Off: this channel stays a playlist of its own", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(copyOn(chat.id), { onCopy(chat.id, it) })
                }
            }) {
                OutlinedButton({ onSetMain(chat.id) }) { Text("Set as main") }
            }
        }
    }
}

@Composable
private fun ChannelCard(chat: ChannelUi, note: String, highlighted: Boolean, below: @Composable () -> Unit = {}, trailing: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
      Column {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Icons.Rounded.Forum, null, Modifier.padding(end = 16.dp))
            Column(Modifier.weight(1f)) {
                Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(chat.id.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            trailing()
        }
        below()
      }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Channels", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun ChannelsPreview() = io.max.paperplay.ui.PreviewFrame(dark = true) {
    Column {
        ChannelsContent(
            "Connected as your bot", null, true, ChannelUi(-1002491394780, "Somg Playlist"),
            listOf(ChannelUi(-1001234567890, "Old library")), null, null, {}, {}, {}, {}, {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Channels scanning", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun ChannelsScanningPreview() = io.max.paperplay.ui.PreviewFrame {
    Column { ChannelsContent("Connected as your bot", null, true, ChannelUi(-1002491394780, "Somg Playlist"), emptyList(), "Reading Somg Playlist: 1200 posts", 0.4f, {}, {}, {}, {}, {}) }
}

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Channels not connected", widthDp = 390, heightDp = 600, showBackground = true)
@Composable
private fun ChannelsOfflinePreview() = io.max.paperplay.ui.PreviewFrame {
    Column { ChannelsContent("Not connected", "Enter the API id, API hash and bot token first.", false, null, emptyList(), null, null, {}, {}, {}, {}, {}) }
}
