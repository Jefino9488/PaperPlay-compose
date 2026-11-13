package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.draw.clip
import io.max.paperplay.ui.state.SongUi
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
 * Other PaperPlay devices for the player's device pickers (desktop popup, phone sheet): each linked device with what it plays
 * and its controls, and paired devices that are away with a Connect button. No addresses here; they live on the Connection page.
 */
@Composable
fun LanDevicesSection(modifier: Modifier = Modifier) {
    val lan = LocalLanSync.current
    val scheme = MaterialTheme.colorScheme
    val muted = MaterialTheme.typography.bodySmall
    if (lan == null) {
        Text(
            "Turn on Sync with devices on this network under Connection to see your other devices here.",
            modifier, style = muted, color = scheme.onSurfaceVariant,
        )
        return
    }
    val connected by lan.connected.collectAsState()
    val paired by lan.paired.collectAsState()
    val now by lan.remoteNow.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (d in connected) {
            val n = now[d.id]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(if (d.desktop) Icons.Rounded.Computer else Icons.Rounded.PhoneAndroid, null, Modifier.size(28.dp), tint = scheme.onSurfaceVariant)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        n?.title?.let { t -> if (n.artist.isNullOrBlank()) t else "$t - ${n.artist}" } ?: "Connected",
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
                AppIcon(Icons.Rounded.Devices, null, Modifier.size(28.dp), tint = scheme.onSurfaceVariant.copy(alpha = 0.6f))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurface.copy(alpha = 0.8f))
                    Text("Not connected", style = muted, color = scheme.onSurfaceVariant)
                }
                OutlinedButton({ scope.launch { note = if (lan.connect(p.id).ok) null else "Could not reach ${p.name}. Check its addresses under Connection." } }) { Text("Connect") }
            }
        }
        if (connected.isEmpty() && paired.isEmpty()) Text("No other devices found. Open Paper Play on another device on this Wi-Fi, with the same bot.", style = muted, color = scheme.onSurfaceVariant)
        note?.let { Text(it, style = muted, color = scheme.error) }
    }
}

/** Tiles that open the device settings: Connection and Network play, and the system output chooser where there is one. */
@Composable
fun DevicesActions(onOpen: ((String) -> Unit)?, switchOutput: (() -> Unit)?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    @Composable
    fun Tile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, run: () -> Unit, mod: Modifier) {
        Column(
            mod.clip(RoundedCornerShape(18.dp)).background(scheme.onSurface.copy(alpha = 0.08f)).clickable { onDone(); run() }.padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppIcon(icon, null, Modifier.size(26.dp), tint = scheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        }
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onOpen != null) Tile(Icons.Rounded.Sync, "Connection", { onOpen("devices") }, Modifier.weight(1f))
        if (onOpen != null) Tile(Icons.Rounded.Wifi, "Network play", { onOpen("netplay") }, Modifier.weight(1f))
        if (switchOutput != null) Tile(Icons.Rounded.Bluetooth, "Output", switchOutput, Modifier.weight(1f))
    }
}

/**
 * The phone's device sheet, in the style of Spotify's Connect: where the sound is going now (the headphones or the phone) with
 * what is playing, the other PaperPlay devices, and tiles into the device settings.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneDevicesDialog(onOpen: (String) -> Unit, switchOutput: (() -> Unit)?, current: SongUi?, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val out = io.max.paperplay.ui.rememberOutputDevice()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Play on", style = MaterialTheme.typography.headlineSmall)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(scheme.primaryContainer.copy(alpha = 0.55f)).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(out.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("This phone", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    if (current != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(Icons.Rounded.GraphicEq, null, Modifier.size(18.dp), tint = scheme.primary)
                        Text(
                            "${current.title} - ${current.artist}", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
                            color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                AppIcon(if (out.external) Icons.Rounded.Headphones else Icons.Rounded.PhoneAndroid, null, Modifier.size(44.dp), tint = scheme.primary)
            }
            LanDevicesSection()
            DevicesActions(onOpen, switchOutput, onDismiss)
        }
    }
}
