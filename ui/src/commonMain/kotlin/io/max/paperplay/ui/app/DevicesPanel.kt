package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.sync.LanSync
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The local network sync of this app, or null while it has never been turned on. Provided by the shell. */
val LocalLanSync = compositionLocalOf<LanSync?> { null }

/** The backend's sync once it exists; sync is created the first time it is turned on, so this waits for it. */
@Composable
fun rememberLanSync(backend: CoreBackend): LanSync? {
    var lan by remember { mutableStateOf(backend.lanSync) }
    LaunchedEffect(lan) { while (lan == null) { delay(2000); lan = backend.lanSync } }
    return lan
}

/**
 * Other PaperPlay devices for the player's device pickers (desktop popup, phone dialog): this device's own addresses, each linked
 * device with what it plays and its controls, and paired devices that are away with a Connect button.
 */
@Composable
fun LanDevicesSection(modifier: Modifier = Modifier) {
    val lan = LocalLanSync.current
    val scheme = MaterialTheme.colorScheme
    val muted = MaterialTheme.typography.bodySmall
    if (lan == null) {
        Text(
            "Turn on Sync with devices on this network in Settings, Connection, to see your other devices here.",
            modifier, style = muted, color = scheme.onSurfaceVariant,
        )
        return
    }
    val name by lan.deviceName.collectAsState()
    val ips by lan.localAddresses.collectAsState()
    val port by lan.port.collectAsState()
    val connected by lan.connected.collectAsState()
    val paired by lan.paired.collectAsState()
    val now by lan.remoteNow.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "$name: " + if (ips.isEmpty()) "no network address" else ips.joinToString(", ") { "$it:$port" },
            style = muted, color = scheme.onSurfaceVariant,
        )
        for (d in connected) {
            val n = now[d.id]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        n?.title?.let { t -> if (n.artist.isNullOrBlank()) t else "$t - ${n.artist}" } ?: "Connected, ${d.host}",
                        style = muted, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (d.paired) {
                    IconButton({ scope.launch { lan.sendControl(d.id, "previous") } }, Modifier.size(36.dp)) { AppIcon(Icons.Rounded.SkipPrevious, "Previous on ${d.name}", Modifier.size(22.dp)) }
                    IconButton({ scope.launch { lan.sendControl(d.id, if (n?.playing == true) "pause" else "play") } }, Modifier.size(36.dp)) {
                        AppIcon(if (n?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (n?.playing == true) "Pause on ${d.name}" else "Play on ${d.name}", Modifier.size(24.dp))
                    }
                    IconButton({ scope.launch { lan.sendControl(d.id, "next") } }, Modifier.size(36.dp)) { AppIcon(Icons.Rounded.SkipNext, "Next on ${d.name}", Modifier.size(22.dp)) }
                }
            }
        }
        for (p in paired.filter { p -> connected.none { it.id == p.id } }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Not connected", style = muted, color = scheme.onSurfaceVariant)
                }
                OutlinedButton({ scope.launch { note = if (lan.connect(p.id).ok) null else "Could not reach ${p.name}. Check its addresses in Settings, Connection." } }) { Text("Connect") }
            }
        }
        if (connected.isEmpty() && paired.isEmpty()) Text("No other devices yet. Link one in Settings, Connection.", style = muted, color = scheme.onSurfaceVariant)
        note?.let { Text(it, style = muted, color = scheme.error) }
    }
}
