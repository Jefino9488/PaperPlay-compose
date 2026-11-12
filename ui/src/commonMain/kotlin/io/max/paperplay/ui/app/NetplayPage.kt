package io.max.paperplay.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.max.paperplay.core.sync.netplay.NetControl
import io.max.paperplay.core.sync.netplay.NetFailure
import io.max.paperplay.core.sync.netplay.NetHost
import io.max.paperplay.core.sync.netplay.NetSession
import io.max.paperplay.core.sync.netplay.Netplay
import io.max.paperplay.core.sync.netplay.NetplayLink
import io.max.paperplay.core.sync.netplay.SongSource
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.launch

/**
 * Network play: listen together with other people on the same network. One person starts a session and shows the PIN
 * and QR code; the others join and the songs play in sync. Guests are not linked devices: they get no library, settings
 * or secrets, only what plays.
 */
@Composable
internal fun NetplayPage(backend: CoreBackend) {
    val np = backend.services.netplay
    val session by np.session.collectAsState()
    val notice by np.notice.collectAsState()
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        notice?.let { text ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(np::clearNotice) { Text("Dismiss") }
                }
            }
        }
        val s = session
        when {
            s == null -> NetplayHome(np)
            s.host -> HostSessionView(np, s)
            else -> GuestSessionView(np, s)
        }
    }
}

internal fun netFailureText(f: NetFailure?): String = when (f) {
    NetFailure.Unreachable -> "Nothing answered at that address. Check that both devices are on the same network."
    NetFailure.WrongPin -> "That PIN is not right."
    NetFailure.LockedOut -> "Too many wrong PINs. Wait a few minutes and try again."
    NetFailure.Full -> "The session is full."
    NetFailure.Refused -> "The host did not let this device in."
    NetFailure.BadProof -> "The device did not prove it knows the PIN. Nothing was joined."
    NetFailure.Busy -> "This device is already in a session."
    NetFailure.Protocol, null -> "Could not join. The other device may not be hosting a session."
}

@Composable
private fun NpCard(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun NpHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NetplayHome(np: Netplay) {
    val scope = rememberCoroutineScope()
    var canControl by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val found by np.discovered.collectAsState()

    DisposableEffect(np) {
        np.startDiscovery()
        onDispose { np.stopDiscovery() }
    }

    NpHint(
        "Listen together with people on the same network. They do not need to be your devices or have your library. " +
            "Songs play at the same time on every device.",
    )

    NpCard {
        Text("Start a session", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Guests can control", canControl, { canControl = it }, subtitle = "Guests may pause, skip and seek. You can change this later.")
        SwitchRow(
            "Share audio with guests", share, { share = it },
            subtitle = "Guests who do not have a song get it from this device",
        )
        if (share) NpHint(
            "Sharing sends the audio file of the songs in your queue to every guest, over an encrypted link, " +
                "for as long as the session is open. It uses your network and data on this device. Turn it off to share only what is playing.",
        )
        Button({
            scope.launch {
                busy = true
                error = if (np.startHost(canControl, share) == null) "Could not start a session. Another one may be open." else null
                busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Start session") }
    }

    NpCard {
        Text("Join a session", style = MaterialTheme.typography.titleMedium)
        NpHint("Paste the link from the host's QR code, or type the host's address and the PIN.")
        OutlinedTextField(
            target, { target = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Link or host address") }, placeholder = { Text("192.168.1.20:47873") },
        )
        val isLink = NetplayLink.parse(target) != null
        if (!isLink) OutlinedTextField(
            pin, { pin = it.filter { c -> c in '0'..'9' }.take(6) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Button({
            scope.launch {
                busy = true
                error = null
                val link = NetplayLink.parse(target)
                val r = if (link != null) np.join(link) else {
                    val ep = NetplayLink.splitEndpoint(target)
                    if (ep == null) null else np.join(ep.first, ep.second, pin)
                }
                if (r == null) error = "Enter the host address or paste the link."
                else if (!r.ok) error = netFailureText(r.failure)
                busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = !busy && target.isNotBlank() && (isLink || pin.length == 6)) { Text("Join") }
        NpHint("Scanning the QR code with the camera is not built yet. Read the code with another app and paste the link here.")

        if (found.isNotEmpty()) {
            Text("Hosts on this network", style = MaterialTheme.typography.titleSmall)
            found.forEach { h -> HostRow(h) { target = "${h.host}:${h.port}" } }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun HostRow(h: NetHost, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Icons.Rounded.Devices, null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(h.name)
            NpHint("${h.host}:${h.port}. Tap, then type the PIN")
        }
    }
}

@Composable
private fun HostSessionView(np: Netplay, s: NetSession) {
    val scope = rememberCoroutineScope()
    var confirmEnd by remember { mutableStateOf(false) }
    NpCard {
        Text("Your session is open", style = MaterialTheme.typography.titleMedium)
        s.pin?.let { pin ->
            Text(
                pin.chunked(3).joinToString(" "), Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        NpHint("The PIN works until you end the session or make a new one.")
        s.link?.let { link ->
            if (s.addresses.isEmpty()) Text("No network address found. Connect to Wi-Fi so others can join.", color = MaterialTheme.colorScheme.error)
            else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrView(link) }
            NpHint("Addresses: " + s.addresses.joinToString(", "))
            androidx.compose.foundation.text.selection.SelectionContainer { Text(link, style = MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ scope.launch { np.regeneratePin() } }) { Text("New PIN") }
            Button({ confirmEnd = true }) { Text("End session") }
        }
    }

    NpCard {
        Text("Controls", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Guests can control", s.guestsCanControl, { scope.launch { np.setGuestsCanControl(it) } }, subtitle = "Guests may pause, skip and seek")
        SwitchRow("Share audio with guests", s.shareAudio, { scope.launch { np.setShareAudio(it) } }, subtitle = "Guests who do not have a song get it from this device")
        if (s.shareAudio) NpHint("Sharing sends the audio file of the songs in your queue to every guest for as long as the session is open.")
        Text(
            s.now?.let { "${if (s.playing) "Playing" else "Paused"}: ${it.title}" + (if (it.artist.isNotBlank()) ", ${it.artist}" else "") } ?: "Nothing playing. Start a song and the guests follow.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    NpCard {
        Text("Guests (${s.guests.size})", style = MaterialTheme.typography.titleMedium)
        if (s.guests.isEmpty()) NpHint("Nobody has joined yet.")
        s.guests.forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.name)
                    NpHint(g.address)
                }
                TextButton({ scope.launch { np.kick(g.id) } }) { Text("Remove") }
            }
        }
        if (s.guests.isNotEmpty()) NpHint("A removed guest cannot join again from the same address until you make a new PIN.")
    }
    if (confirmEnd) ConfirmDialog("End the session?", "Everyone is disconnected and the PIN stops working.", "End", { scope.launch { np.endSession() } }) { confirmEnd = false }
}

@Composable
private fun GuestSessionView(np: Netplay, s: NetSession) {
    val scope = rememberCoroutineScope()
    NpCard {
        Text("In ${s.hostName}'s session", style = MaterialTheme.typography.titleMedium)
        val song = s.now
        if (song == null) Text("Waiting for the host to play something.")
        else {
            Text(song.title, style = MaterialTheme.typography.titleLarge)
            if (song.artist.isNotBlank()) Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when (s.source) {
                    SongSource.Local -> "Playing from your library"
                    SongSource.Streamed -> "Streaming from the host"
                    SongSource.Missing -> if (s.shareAudio) "This song could not be loaded" else "Not in your library, and the host is not sharing audio"
                    null -> "Getting ready"
                },
                color = if (s.source == SongSource.Missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        if (s.guestsCanControl) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ scope.launch { np.control(NetControl.Previous) } }) { AppIcon(Icons.Rounded.SkipPrevious, "Previous") }
                IconButton({ scope.launch { np.control(if (s.playing) NetControl.Pause else NetControl.Play) } }) {
                    AppIcon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.playing) "Pause" else "Play")
                }
                IconButton({ scope.launch { np.control(NetControl.Next) } }) { AppIcon(Icons.Rounded.SkipNext, "Next") }
            }
        } else NpHint("Only the host controls playback.")
        NpHint("Sync: ${s.rttMs} ms round trip, clock difference ${s.offsetMs} ms. Playback is corrected when it drifts by more than 300 ms.")
    }
    NpCard {
        Text("Listening (${s.guests.size + 1})", style = MaterialTheme.typography.titleMedium)
        Text("${s.hostName} (host)")
        s.guests.forEach { Text(it.name) }
    }
    OutlinedButton({ scope.launch { np.leave() } }, Modifier.fillMaxWidth()) { Text("Leave session") }
}

/** A small pill shown over the app while this device is in a network play session. Tap to open the session. */
@Composable
internal fun NetplayIndicator(backend: CoreBackend, modifier: Modifier, onOpen: () -> Unit) {
    val s by backend.services.netplay.session.collectAsState()
    val session = s ?: return
    Surface(modifier.clickable(onClick = onOpen), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Icons.Rounded.Devices, null)
            Text(
                if (session.host) "Hosting, ${session.guests.size} listening" else "Network play",
                Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
