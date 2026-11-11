package io.max.paperplay.ui.app

import io.max.paperplay.ui.components.AppIcon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Page title with a back arrow. */
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { AppIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text(title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.headlineMedium, maxLines = 1)
        actions()
    }
}

/** One rounded card per category on the Settings list. */
@Composable
fun SettingsCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, null)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppIcon(Icons.Rounded.ChevronRight, null)
    }
}

/** A row inside a sub-page: optional icon, title, optional subtitle, optional trailing content. */
@Composable
fun SettingsRow(
    title: String, modifier: Modifier = Modifier, icon: ImageVector? = null, subtitle: String? = null,
    onClick: (() -> Unit)? = null, enabled: Boolean = true, trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) AppIcon(icon, null, Modifier.padding(end = 16.dp), tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
fun NavRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: () -> Unit) =
    SettingsRow(title, icon = icon, subtitle = subtitle, onClick = onClick) { AppIcon(Icons.Rounded.ChevronRight, null) }

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector? = null, subtitle: String? = null) =
    SettingsRow(title, icon = icon, subtitle = subtitle, onClick = { onChange(!checked) }) { Switch(checked, onChange) }

@Composable
fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, steps: Int = 0, hint: String? = null, onDone: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value, onChange, valueRange = range, steps = steps, onValueChangeFinished = onDone)
    }
}

@Composable
fun SectionLabel(text: String) =
    Text(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) =
    ChipRow(options.map(label), options.indexOf(selected).coerceAtLeast(0), { onSelect(options[it]) }, modifier)

@Composable
fun NotBuilt(what: String) {
    Column(Modifier.padding(20.dp)) {
        Text(what, style = MaterialTheme.typography.titleMedium)
        Text("Not built in this app yet.", Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = overlayRim(RoundedCornerShape(28.dp)), shape = RoundedCornerShape(28.dp), containerColor = overlayColor(),
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton({ onDismiss(); onConfirm() }) { Text(action) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** A round colour swatch for accent choices. */
@Composable
fun Swatch(color: androidx.compose.ui.graphics.Color, selected: Boolean, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(color)
                .then(if (selected) Modifier.padding(0.dp) else Modifier),
            contentAlignment = Alignment.Center,
        ) { if (selected) AppIcon(Icons.Rounded.Check, null, tint = androidx.compose.ui.graphics.Color.White) }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun SettingsColumn(content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) { content() }

@Preview
@Composable
private fun BackTopBarPreview() {
    BackTopBar(title = "Appearance", onBack = {})
}

@Preview
@Composable
private fun SettingsCardPreview() {
    SettingsCard(icon = Icons.Rounded.Check, title = "Appearance", subtitle = "Theme, colors, display", onClick = {})
}

@Preview
@Composable
private fun SettingsRowPreview() {
    SettingsRow(title = "General Settings", subtitle = "Manage general configuration", icon = Icons.Rounded.Check)
}

@Preview
@Composable
private fun NavRowPreview() {
    NavRow(title = "Channels", subtitle = "See and manage Telegram channels", onClick = {})
}

@Preview
@Composable
private fun SwitchRowPreview() {
    SwitchRow(title = "True Black", checked = true, onChange = {})
}

@Preview
@Composable
private fun SliderRowPreview() {
    SliderRow(title = "Blur: 16", value = 16f, range = 0f..40f, onChange = {})
}

@Preview
@Composable
private fun SectionLabelPreview() {
    SectionLabel(text = "Theme Customization")
}

@Preview
@Composable
private fun ChoiceRowPreview() {
    ChoiceRow(options = listOf("Dark", "Light", "System"), selected = "Dark", label = { it }, onSelect = {})
}

@Preview
@Composable
private fun NotBuiltPreview() {
    NotBuilt(what = "Spotify Import")
}

@Preview
@Composable
private fun ConfirmDialogPreview() {
    ConfirmDialog(title = "Log out?", body = "The bot credentials will be cleared.", action = "Log out", onConfirm = {}, onDismiss = {})
}

@Preview
@Composable
private fun SwatchPreview() {
    Swatch(color = androidx.compose.ui.graphics.Color.Blue, selected = true, label = "Blue", onClick = {})
}

@Preview
@Composable
private fun SettingsColumnPreview() {
    SettingsColumn {
        Text("Item 1")
        Text("Item 2")
    }
}

