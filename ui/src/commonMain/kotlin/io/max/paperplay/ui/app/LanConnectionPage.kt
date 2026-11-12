package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.sync.AddressState
import io.max.paperplay.core.sync.ConnectResult
import io.max.paperplay.core.sync.DiscoveredDevice
import io.max.paperplay.core.sync.KnownAddress
import io.max.paperplay.core.sync.LanDevice
import io.max.paperplay.core.sync.LanFailure
import io.max.paperplay.core.sync.LanSync
import io.max.paperplay.core.sync.PairedDevice
import io.max.paperplay.core.sync.PairingPin
import io.max.paperplay.core.sync.SyncResult
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

private fun nowMs() = Clock.System.now().toEpochMilliseconds()

/** Everything the Connection page shows about local network sync. [available] is false until sync has been turned on once. */
data class LanUi(
    val available: Boolean = false,
    val listening: Boolean = false,
    val port: Int = 47872,
    val name: String = "",
    val addresses: List<String> = emptyList(),
    val connected: List<LanDevice> = emptyList(),
    val saved: List<KnownAddress> = emptyList(),
    val paired: List<PairedDevice> = emptyList(),
    val discovered: List<DiscoveredDevice> = emptyList(),
    val pin: PairingPin? = null,
    val passwordSet: Boolean = false,
    val onlyPaired: Boolean = false,
    val tokenMode: Boolean = false,
    val discoveryOn: Boolean = true,
    val scanning: Boolean = false,
    val error: String? = null,
)

/** A result to show as text. */
data class LanOutcome(val ok: Boolean, val text: String)

/** What the page can do. Every default does nothing, so previews can use `LanActions()`. */
class LanActions(
    val setOn: (Boolean) -> Unit = {},
    val setName: (String) -> Unit = {},
    val setDiscovery: (Boolean) -> Unit = {},
    val setOnlyPaired: (Boolean) -> Unit = {},
    val setTokenMode: (Boolean) -> Unit = {},
    val showPin: () -> Unit = {},
    val hidePin: () -> Unit = {},
    val setPassword: (String?) -> Unit = {},
    val resetCode: () -> Unit = {},
    val unpairAll: () -> Unit = {},
    val unpair: (String) -> Unit = {},
    val removeAddress: (String) -> Unit = {},
    val disconnect: (String) -> Unit = {},
    val addAddress: suspend (String) -> Boolean = { true },
    val connectPaired: suspend (String) -> LanOutcome = { LanOutcome(true, "Connected") },
    val connectCode: suspend (String, Int, String) -> LanOutcome = { _, _, _ -> LanOutcome(true, "Connected") },
    val connectToken: suspend (String, Int) -> LanOutcome = { _, _ -> LanOutcome(true, "Connected") },
    val syncNow: suspend (String) -> LanOutcome = { LanOutcome(true, "Synced") },
    /** Returns how many devices answered. */
    val scan: suspend () -> Int = { 0 },
)

private fun failureText(f: LanFailure?) = when (f) {
    LanFailure.Unreachable -> "Could not reach it"
    LanFailure.Protocol -> "It did not answer like a PaperPlay device"
    LanFailure.WrongCode -> "Wrong code or password"
    LanFailure.LockedOut -> "Too many wrong codes. Try again in a few minutes"
    LanFailure.NotPaired -> "That device does not accept this one. Link it with a code first"
    LanFailure.BadProof -> "The other device could not prove it knows the code"
    LanFailure.NotReady -> "Not ready. Check that sync is on"
    null -> "Failed"
}

private fun ConnectResult.outcome() = if (ok) LanOutcome(true, "Connected to ${device?.name ?: "the device"}") else LanOutcome(false, failureText(failure))
private fun SyncResult.outcome() =
    if (ok) LanOutcome(true, "Synced. Changed here: $songsChanged songs, $playlistsChanged playlists") else LanOutcome(false, failureText(failure))

private fun resultText(r: AddressState?) = when (r) {
    null -> "Not tried yet"
    AddressState.Connected -> "Connected"
    AddressState.Trying -> "Trying now"
    AddressState.Unreachable -> "Could not reach it"
    AddressState.Refused -> "Refused the link"
}

/** Settings > Devices > Connection, the local network part. Needs a backend; see [LanConnectionContent] for the screen itself. */
@Composable
internal fun LanConnectionPage(backend: CoreBackend) {
    var lan by remember { mutableStateOf(backend.lanSync) }
    val sync = lan
    if (sync == null) {
        LaunchedEffect(Unit) {
            while (backend.lanSync == null) delay(300)
            lan = backend.lanSync
        }
        LanConnectionContent(LanUi(), LanActions(setOn = { if (it) backend.setLanOn(true) }))
        return
    }
    val scope = rememberCoroutineScope()
    val ui = sync.collectUi()
    val actions = remember(sync) {
        LanActions(
            setOn = backend::setLanOn,
            setName = { scope.launch { sync.setDeviceName(it) } },
            setDiscovery = { scope.launch { sync.setDiscovery(it) } },
            setOnlyPaired = { scope.launch { sync.setOnlyPaired(it) } },
            setTokenMode = { scope.launch { sync.setTokenMode(it) } },
            showPin = { scope.launch { sync.showPin() } },
            hidePin = { scope.launch { sync.hidePin() } },
            setPassword = { scope.launch { sync.setPassword(it) } },
            resetCode = { scope.launch { sync.resetCode() } },
            unpairAll = { scope.launch { sync.unpairAll() } },
            unpair = { scope.launch { sync.unpair(it) } },
            removeAddress = { scope.launch { sync.removeAddress(it) } },
            disconnect = { scope.launch { sync.disconnect(it) } },
            addAddress = { sync.addAddress(it) },
            connectPaired = { sync.connect(it).outcome() },
            connectCode = { host, port, code -> sync.connectWithCode(host, port, code).outcome() },
            connectToken = { host, port -> sync.connectWithToken(host, port).outcome() },
            syncNow = { sync.syncNow(it).outcome() },
            scan = { sync.scanNetwork().size },
        )
    }
    LanConnectionContent(ui, actions)
}

@Composable
private fun LanSync.collectUi(): LanUi {
    val l = this
    val listening by l.listening.collectAsState()
    val port by l.port.collectAsState()
    val name by l.deviceName.collectAsState()
    val addresses by l.localAddresses.collectAsState()
    val connected by l.connected.collectAsState()
    val saved by l.saved.collectAsState()
    val paired by l.paired.collectAsState()
    val discovered by l.discovered.collectAsState()
    val pin by l.pin.collectAsState()
    val passwordSet by l.passwordSet.collectAsState()
    val onlyPaired by l.onlyPaired.collectAsState()
    val tokenMode by l.tokenMode.collectAsState()
    val discoveryOn by l.discoveryOn.collectAsState()
    val scanning by l.scanning.collectAsState()
    val error by l.lastError.collectAsState()
    return LanUi(
        true, listening, port, name, addresses, connected, saved, paired, discovered, pin, passwordSet,
        onlyPaired, tokenMode, discoveryOn, scanning, error,
    )
}

private sealed interface LanDialog {
    data object Password : LanDialog
    data object Name : LanDialog
    data object AddAddress : LanDialog
    data object ResetCode : LanDialog
    data object UnpairAll : LanDialog
    data class Code(val host: String, val port: Int, val name: String) : LanDialog
}

@Composable
internal fun LanConnectionContent(ui: LanUi, a: LanActions) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var note by remember { mutableStateOf<String?>(null) }
    var syncNotes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var syncing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dialog by remember { mutableStateOf<LanDialog?>(null) }
    val pairedIds = ui.paired.map { it.id }.toSet()

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        note?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary) }

        LanCard("Local network") {
            SwitchRow(
                "Sync with devices on this network", ui.listening, a.setOn, Icons.Rounded.Wifi,
                subtitle = ui.error ?: if (ui.listening) "On, listening on port ${ui.port}" else "Off",
            )
        }
        if (!ui.available) return@Column

        LanCard("Connected devices") {
            if (ui.connected.isEmpty()) {
                SettingsRow("None right now", icon = Icons.Rounded.Info, subtitle = "Devices that link to this one, or that you connect to, show up here")
            }
            for (d in ui.connected) {
                ConnectedRow(
                    d, syncNotes[d.id], d.id in syncing,
                    onSync = {
                        syncing = syncing + d.id
                        scope.launch {
                            val r = a.syncNow(d.id)
                            syncNotes = syncNotes + (d.id to r.text)
                            syncing = syncing - d.id
                        }
                    },
                    onDisconnect = { a.disconnect(d.id) },
                )
            }
        }

        LanCard("Pairing") {
            SwitchRow(
                "Only paired devices may link", ui.onlyPaired, a.setOnlyPaired, Icons.Rounded.Lock,
                subtitle = "No new device can link with a code or the bot token. Paired devices still can",
            )
            PinRow(ui.pin, a.showPin, a.hidePin)
            SettingsRow(
                "Set or change password", icon = Icons.Rounded.Key, onClick = { dialog = LanDialog.Password },
                subtitle = if (ui.passwordSet) "A password is set. A new device can use it instead of a PIN" else "No password set",
            )
            SettingsRow("Reset code", subtitle = "Makes the PIN and the password stop working. Paired devices stay paired") {
                OutlinedButton({ dialog = LanDialog.ResetCode }) { Text("Reset code") }
            }
            for (p in ui.paired) {
                SettingsRow(p.name, subtitle = "Paired") { TextButton({ a.unpair(p.id) }) { Text("Unpair") } }
            }
            SettingsRow("Unpair all", subtitle = if (ui.paired.isEmpty()) "No paired devices" else "${ui.paired.size} paired devices will need a code again") {
                OutlinedButton({ dialog = LanDialog.UnpairAll }, enabled = ui.paired.isNotEmpty()) { Text("Unpair all") }
            }
        }

        LanCard("Saved addresses") {
            if (ui.saved.isEmpty()) SettingsRow("No saved addresses", subtitle = "Add the address of a device to connect to it")
            for (k in ui.saved) {
                SavedRow(
                    k,
                    onConnect = {
                        val id = k.deviceId
                        if (id != null && id in pairedIds) {
                            scope.launch { note = a.connectPaired(id).text }
                        } else {
                            dialog = LanDialog.Code(k.host, k.port, k.name ?: k.host)
                        }
                    },
                    onDelete = { a.removeAddress(k.key) },
                )
            }
            OutlinedButton({ dialog = LanDialog.AddAddress }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                AppIcon(Icons.Rounded.Add, null, Modifier.padding(end = 8.dp))
                Text("Add address")
            }
        }

        LanCard("Finding devices") {
            SwitchRow(
                "Find devices automatically", ui.discoveryOn, a.setDiscovery, Icons.Rounded.Search,
                subtitle = "Announces this device and listens for others on the network",
            )
            SettingsRow(
                "Scan this network", icon = Icons.Rounded.Wifi, enabled = !ui.scanning,
                subtitle = if (ui.scanning) "Scanning this network"
                else "Tries every address once, for when a device moved and isn't announcing. Network monitoring on shared networks may notice this.",
                onClick = { scope.launch { val n = a.scan(); note = if (n == 0) "Scan finished. No devices answered" else "Scan finished. $n answered" } },
            ) { if (ui.scanning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            val connectedIds = ui.connected.map { it.id }.toSet()
            val found = ui.discovered.filter { it.id !in connectedIds }
            if (found.isEmpty()) SettingsRow("No devices found yet", subtitle = "Other PaperPlay devices on this network appear here")
            for (d in found) {
                SettingsRow(
                    d.name, icon = Icons.Rounded.Devices,
                    subtitle = "${d.host}:${d.port}. ${if (d.desktop) "PC" else "Phone"}${if (d.paired) ". Paired" else ""}",
                ) {
                    Button({
                        if (d.paired) scope.launch { note = a.connectPaired(d.id).text } else dialog = LanDialog.Code(d.host, d.port, d.name)
                    }) { Text("Connect") }
                }
            }
        }

        LanCard("This device") {
            SettingsRow("Device name", icon = Icons.Rounded.Devices, subtitle = ui.name.ifBlank { "Not set" }) {
                TextButton({ dialog = LanDialog.Name }) { Text("Edit") }
            }
            if (ui.addresses.isEmpty()) SettingsRow("No network address", subtitle = "Join a Wi-Fi or wired network to be found by other devices")
            for (addr in ui.addresses) {
                SettingsRow("$addr:${ui.port}", subtitle = "Type this on the other device") {
                    IconButton({ clipboard.setText(AnnotatedString("$addr:${ui.port}")); note = "Copied $addr:${ui.port}" }) {
                        AppIcon(Icons.Rounded.ContentCopy, "Copy address")
                    }
                }
            }
        }

        LanCard("Encryption") {
            SettingsRow("Always on", icon = Icons.Rounded.Lock, subtitle = "Every connection is encrypted and can't be turned off.")
            SwitchRow(
                "Only devices with my bot token", ui.tokenMode, a.setTokenMode,
                subtitle = "A device with the same bot token can link without a code. The token itself is never sent",
            )
            Text(
                "X25519 key exchange, AES-256-GCM. New keys for each connection.",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val removePassword: (@Composable () -> Unit)? =
        if (ui.passwordSet) ({ TextButton({ a.setPassword(null); dialog = null }) { Text("Remove password") } }) else null
    val useToken: (@Composable () -> Unit)? =
        if (ui.tokenMode) ({
            val c = dialog as? LanDialog.Code
            if (c != null) TextButton({ dialog = null; scope.launch { note = a.connectToken(c.host, c.port).text } }) { Text("Use the bot token instead") }
        }) else null
    when (val d = dialog) {
        null -> {}
        LanDialog.Name -> LanTextDialog("Device name", "Name", "Save", { dialog = null }, initial = ui.name) { a.setName(it); null }
        LanDialog.AddAddress -> LanTextDialog(
            "Add address", "Address", "Add", { dialog = null },
            message = "For example 192.168.1.5 or 192.168.1.5:47872",
        ) { if (a.addAddress(it)) null else "That is not an address" }
        LanDialog.Password -> LanTextDialog(
            "Password", "New password", "Save", { dialog = null }, secret = true,
            message = "At least 6 characters. A new device can enter it instead of a PIN.",
            extra = removePassword,
        ) { if (it.length < 6) "At least 6 characters" else { a.setPassword(it); null } }
        LanDialog.ResetCode -> ConfirmDialog(
            "Reset code", "The PIN and the password stop working and wrong code lockouts are cleared. Paired devices stay paired.", "Reset",
            { a.resetCode(); note = "Code reset" }, { dialog = null },
        )
        LanDialog.UnpairAll -> ConfirmDialog(
            "Unpair all", "Every paired device is forgotten and needs a code to link again.", "Unpair all",
            { a.unpairAll(); note = "All devices unpaired" }, { dialog = null },
        )
        is LanDialog.Code -> LanTextDialog(
            "Connect to ${d.name}", "PIN or password", "Connect", { dialog = null }, secret = true,
            message = "Enter the PIN shown on ${d.name}, or its password.",
            extra = useToken,
        ) { code ->
            val r = a.connectCode(d.host, d.port, code)
            if (r.ok) note = r.text
            if (r.ok) null else r.text
        }
    }
}

/** A titled rounded group of rows, the card style of the settings pages. */
@Composable
internal fun LanCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(vertical = 8.dp),
    ) {
        Text(
            title, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

/** "Show a PIN" row; while a PIN is live it shows the digits, the seconds left and a Hide button. */
@Composable
internal fun PinRow(pin: PairingPin?, onShow: () -> Unit, onHide: () -> Unit) {
    var now by remember { mutableStateOf(nowMs()) }
    LaunchedEffect(pin) {
        while (pin != null) { now = nowMs(); delay(1000) }
    }
    val left = pin?.let { ((it.expiresAt - now) / 1000).coerceAtLeast(0) }
    if (pin == null || left == null || left == 0L) {
        SettingsRow("Show a PIN", icon = Icons.Rounded.Key, subtitle = "A 6 digit code for a new device. It works once, for two minutes", onClick = onShow)
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pin.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineMedium)
                Text("Enter it on the other device. Expires in $left s", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onHide) { Text("Hide") }
        }
    }
}

@Composable
internal fun ConnectedRow(d: LanDevice, syncNote: String?, syncing: Boolean, onSync: () -> Unit, onDisconnect: () -> Unit) {
    Column {
        SettingsRow(
            d.name, icon = Icons.Rounded.Devices,
            subtitle = "${d.host}:${d.port}. ${if (d.desktop) "PC" else "Phone"}. ${if (d.paired) "Paired" else "Linked with the bot token"}",
        )
        Row(Modifier.padding(start = 56.dp, end = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onSync, enabled = !syncing) { Text(if (syncing) "Syncing" else "Sync now") }
            TextButton(onDisconnect) { Text("Disconnect") }
        }
        syncNote?.let { Text(it, Modifier.padding(start = 56.dp, end = 20.dp, top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun SavedRow(k: KnownAddress, onConnect: () -> Unit, onDelete: () -> Unit) {
    Column {
        SettingsRow(k.name ?: k.host, subtitle = "${k.host}:${k.port}. ${resultText(k.lastResult)}")
        Row(Modifier.padding(start = 20.dp, end = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onConnect) { Text("Connect") }
            OutlinedButton(onDelete) { Text("Delete") }
        }
    }
}

/** One text field in a dialog. [onSubmit] returns an error to show, or null to close. */
@Composable
internal fun LanTextDialog(
    title: String, label: String, action: String, onDismiss: () -> Unit,
    initial: String = "", secret: Boolean = false, message: String? = null, extra: (@Composable () -> Unit)? = null,
    onSubmit: suspend (String) -> String?,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = overlayRim(RoundedCornerShape(28.dp)), shape = RoundedCornerShape(28.dp), containerColor = overlayColor(),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(
                    text, { text = it; error = null }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, isError = error != null,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton({
                busy = true
                scope.launch {
                    val e = onSubmit(if (secret) text else text.trim())
                    busy = false
                    if (e == null) onDismiss() else error = e
                }
            }, enabled = text.isNotBlank() && !busy) { Text(if (busy) "Working" else action) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private val sampleDevice = LanDevice("a", "Living room PC", true, "192.168.1.20", 47872, true, 0)

private fun sampleUi() = LanUi(
    available = true, listening = true, name = "Pixel", addresses = listOf("192.168.1.42"),
    connected = listOf(sampleDevice),
    saved = listOf(
        KnownAddress("192.168.1.20", 47872, "Living room PC", "a", 0, AddressState.Connected, 0),
        KnownAddress("192.168.1.77", 47872, null, null, 0, null, 0),
    ),
    paired = listOf(PairedDevice("a", "Living room PC", "key", 0)),
    discovered = listOf(DiscoveredDevice("b", "Laptop", true, "192.168.1.31", 47872, 0, false)),
    pin = PairingPin("482913", nowMs() + 90_000),
    passwordSet = true,
)

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Connection", widthDp = 390, heightDp = 1800, showBackground = true)
@Composable
private fun LanConnectionPreview() = io.max.paperplay.ui.PreviewFrame { LanConnectionContent(sampleUi(), LanActions()) }

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Connection off", widthDp = 390, heightDp = 300, showBackground = true)
@Composable
private fun LanConnectionOffPreview() = io.max.paperplay.ui.PreviewFrame { LanConnectionContent(LanUi(), LanActions()) }

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Connection empty", widthDp = 390, heightDp = 1400, showBackground = true)
@Composable
private fun LanConnectionEmptyPreview() = io.max.paperplay.ui.PreviewFrame { LanConnectionContent(LanUi(available = true, name = "Pixel", scanning = true), LanActions()) }

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Lan card", widthDp = 390, heightDp = 200, showBackground = true)
@Composable
private fun LanCardPreview() = io.max.paperplay.ui.PreviewFrame { LanCard("Card") { SettingsRow("Row", subtitle = "Subtitle") } }

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Pin row", widthDp = 390, heightDp = 200, showBackground = true)
@Composable
private fun PinRowPreview() = io.max.paperplay.ui.PreviewFrame {
    Column {
        PinRow(PairingPin("482913", nowMs() + 90_000), {}, {})
        PinRow(null, {}, {})
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Connected row", widthDp = 390, heightDp = 220, showBackground = true)
@Composable
private fun ConnectedRowPreview() = io.max.paperplay.ui.PreviewFrame { ConnectedRow(sampleDevice, "Synced. Changed here: 3 songs, 0 playlists", false, {}, {}) }

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Saved row", widthDp = 390, heightDp = 200, showBackground = true)
@Composable
private fun SavedRowPreview() = io.max.paperplay.ui.PreviewFrame { SavedRow(KnownAddress("192.168.1.77", 47872), {}, {}) }

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Text dialog", widthDp = 390, heightDp = 400, showBackground = true)
@Composable
private fun LanTextDialogPreview() = io.max.paperplay.ui.PreviewFrame {
    LanTextDialog("Add address", "Address", "Add", {}, message = "For example 192.168.1.5") { null }
}
