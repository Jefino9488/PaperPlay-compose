package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import io.max.paperplay.ui.components.AppIcon
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Where playback can go. Only this PC is listed until LAN peers are wired in. */
class DeviceUi(val id: String, val name: String, val active: Boolean)

/** The Devices button for the desktop player bar, with its popup. */
@Composable
fun DevicePickerButton(devices: List<DeviceUi> = listOf(DeviceUi("this", "This PC", true)), modifier: Modifier = Modifier, onOpen: ((String) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton({ open = !open }) {
            AppIcon(Icons.Rounded.Devices, "Devices", Modifier.size(22.dp), tint = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) DevicePickerPopup(devices, onOpen) { open = false }
    }
}

private val aboveAnchor = object : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset((anchorBounds.right - popupContentSize.width).coerceAtLeast(0), (anchorBounds.top - popupContentSize.height - 8).coerceAtLeast(0))
}

@Composable
fun DevicePickerPopup(devices: List<DeviceUi>, onOpen: ((String) -> Unit)? = null, onDismiss: () -> Unit) {
    Popup(popupPositionProvider = aboveAnchor, onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp) {
            Column(Modifier.width(300.dp).padding(16.dp)) {
                Text("Devices", style = MaterialTheme.typography.titleMedium)
                devices.forEach { d ->
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(Icons.Rounded.Computer, null, tint = if (d.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(d.name, style = MaterialTheme.typography.bodyLarge, color = if (d.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            if (d.active) Text("Playing here", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (d.active) AppIcon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                LanDevicesSection(Modifier.padding(top = 16.dp))
                DevicesActions(onOpen, null, onDismiss, Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Preview(name = "Device picker", widthDp = 340, heightDp = 240)
@Composable
private fun DevicePickerPreview() = io.max.paperplay.ui.PreviewFrame {
    Column(Modifier.padding(8.dp)) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.width(300.dp).padding(16.dp)) {
                Text("Devices", style = MaterialTheme.typography.titleMedium)
                Text("This PC", Modifier.padding(top = 12.dp))
            }
        }
    }
}
