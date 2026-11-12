package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview

enum class StandbyWidget(val key: String, val label: String, val default: Boolean) {
    Clock("clock", "Clock and date", true),
    Song("song", "Title and artist", true),
    Cover("cover", "Small cover", false),
    Progress("progress", "Progress bar", true),
    Controls("controls", "Play, pause and skip", true),
    Battery("battery", "Battery level", true),
    Next("next", "Next song", false),
    Lyrics("lyrics", "Lyrics line", false),
    Weather("weather", "Weather line", false),
}

/** Standby settings, kept under standby_* keys. Created by [UiPrefs] and shared with the screen and sheet. */
class StandbyPrefs(private val read: (String) -> String?, private val write: (String, String) -> Unit) {
    var style by mutableStateOf(StandbyStyle.entries.firstOrNull { it.name.lowercase() == read("standby_style") } ?: StandbyStyle.Bars)
        private set
    /** "cover", "accent" or "custom". */
    var color by mutableStateOf(read("standby_color") ?: "cover")
        private set
    var customColor by mutableStateOf(read("standby_custom")?.toIntOrNull() ?: 0xFF5CC8FF.toInt())
        private set
    var hour24 by mutableStateOf(read("standby_24h") != "false")
        private set
    /** 0.5 to 1.5 times the base clock size. */
    var clockSize by mutableStateOf(read("standby_clock_size")?.toFloatOrNull() ?: 1f)
        private set
    /** Window brightness 0.02 to 1; 1 keeps the system level. */
    var dim by mutableStateOf(read("standby_dim")?.toFloatOrNull() ?: 0.1f)
        private set
    var pixelShift by mutableStateOf(read("standby_shift") != "false")
        private set
    private val widgets = mutableStateOf(StandbyWidget.entries.filter { read("standby_w_${it.key}")?.let { v -> v == "true" } ?: it.default }.toSet())

    fun shows(w: StandbyWidget) = w in widgets.value
    fun putWidget(w: StandbyWidget, on: Boolean) { widgets.value = if (on) widgets.value + w else widgets.value - w; write("standby_w_${w.key}", "$on") }
    fun putStyle(s: StandbyStyle) { style = s; write("standby_style", s.name.lowercase()) }
    fun putColor(id: String) { color = id; write("standby_color", id) }
    fun putCustomColor(argb: Int) { customColor = argb; write("standby_custom", "$argb") }
    fun putHour24(on: Boolean) { hour24 = on; write("standby_24h", "$on") }
    fun putClockSize(v: Float) { clockSize = v; write("standby_clock_size", "$v") }
    fun putDim(v: Float) { dim = v; write("standby_dim", "$v") }
    fun putPixelShift(on: Boolean) { pixelShift = on; write("standby_shift", "$on") }
}

private val customChoices = listOf(0xFF5CC8FF, 0xFFFF8A5C, 0xFF7BE495, 0xFFE58CFF, 0xFFFFD166, 0xFFFFFFFF).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StandbyCustomiseSheet(p: StandbyPrefs, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Standby mode", style = MaterialTheme.typography.titleLarge)
            Text("Animation", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StandbyStyle.entries.forEach { FilterChip(p.style == it, { p.putStyle(it) }, { Text(it.label) }) }
            }
            Text("Colour", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("cover" to "Cover", "accent" to "Accent", "custom" to "Custom").forEach { (id, label) ->
                    FilterChip(p.color == id, { p.putColor(id) }, { Text(label) })
                }
            }
            if (p.color == "custom") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                customChoices.forEach { c ->
                    FilterChip(p.customColor == c, { p.putCustomColor(c) }, { Text("") }, leadingIcon = {
                        androidx.compose.foundation.layout.Box(Modifier.size(18.dp).background(Color(c), CircleShape))
                    })
                }
            }
            Text("Widgets", style = MaterialTheme.typography.titleSmall)
            StandbyWidget.entries.forEach { w -> ToggleLine(w.label, p.shows(w)) { p.putWidget(w, it) } }
            ToggleLine("24 hour clock", p.hour24, p::putHour24)
            ToggleLine("Shift the screen slightly to avoid burn-in", p.pixelShift, p::putPixelShift)
            Text("Clock size", style = MaterialTheme.typography.titleSmall)
            Slider(p.clockSize, p::putClockSize, valueRange = 0.5f..1.5f)
            Text("Brightness (right end keeps the system level)", style = MaterialTheme.typography.titleSmall)
            Slider(p.dim, p::putDim, valueRange = 0.02f..1f)
        }
    }
}

@Composable
private fun ToggleLine(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(on, onChange)
    }
}

@Preview(name = "Standby customise", widthDp = 390, heightDp = 700)
@Composable
private fun StandbyCustomisePreview() = io.max.paperplay.ui.PreviewFrame(dark = true) {
    StandbyCustomiseSheet(StandbyPrefs({ null }, { _, _ -> }), onDismiss = {})
}
