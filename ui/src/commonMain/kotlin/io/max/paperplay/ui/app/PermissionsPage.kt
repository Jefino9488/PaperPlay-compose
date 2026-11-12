package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.LocalAppEnv
import io.max.paperplay.ui.PermissionHooks
import io.max.paperplay.ui.PermissionState
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.delay
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Settings > Permissions: what the app can and cannot do right now, with a button to fix each one. */
@Composable
fun PermissionsPage() {
    val hooks = LocalAppEnv.current?.permissions
    if (hooks == null) {
        Text("This device needs no extra permissions.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var items by remember { mutableStateOf(hooks.check()) }
    // The user can change a setting in another screen and come back, so the list is re-read while the page is open.
    LaunchedEffect(hooks) {
        while (true) { items = hooks.check(); delay(1000) }
    }
    PermissionsContent(items, hooks.request, hooks.openAppSettings)
}

@Composable
fun PermissionsContent(items: List<PermissionState>, onRequest: (String) -> Unit, onOpenSettings: () -> Unit) {
    val missing = items.count { !it.granted }
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (missing == 0) "Everything is allowed." else "$missing not allowed yet. The app still works, but the features below are limited.",
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (missing == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        items.forEach { PermissionRow(it) { onRequest(it.id) } }
        SettingsRow("App settings", icon = Icons.Rounded.Settings, subtitle = "Open this app's page in system settings", onClick = onOpenSettings)
    }
}

@Composable
private fun PermissionRow(p: PermissionState, onAct: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AppIcon(
            if (p.granted) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, if (p.granted) "Allowed" else "Not allowed",
            tint = if (p.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleMedium)
            Text(p.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p.detail.isNotEmpty()) Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!p.granted) FilledTonalButton(onAct) { Text(p.action) }
    }
}

@Preview
@Composable
private fun PermissionsPreview() = io.max.paperplay.ui.PreviewFrame {
    PermissionsContent(
        listOf(
            PermissionState("notifications", "Notifications", "Shows the playing song and scan progress", true),
            PermissionState("bluetooth", "Bluetooth devices", "Lets the app see headphones and speakers for media controls", false, "Bluetooth is off", "Allow"),
            PermissionState("battery", "Battery", "Stops Android from pausing the app in the background", false, action = "Open settings"),
        ),
        {}, {},
    )
}
