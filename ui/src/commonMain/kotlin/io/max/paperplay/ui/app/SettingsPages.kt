package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.recs.TasteSettings
import io.max.paperplay.core.recs.WeatherPlace
import kotlinx.coroutines.launch

/** Settings > Mixes > Mixes and learning. */
@Composable
internal fun MixLearnPage(backend: CoreBackend, onClear: () -> Unit) {
    val t by backend.taste.collectAsState()
    SectionLabel("Mixes")
    SwitchRow("Show daily mixes on Home", t.showMixes, { v -> backend.updateTaste { it.copy(showMixes = v) } }, Icons.Rounded.AutoAwesome)
    SwitchRow("Only use songs from the lists below", t.onlyFromLists, { v -> backend.updateTaste { it.copy(onlyFromLists = v) } },
        subtitle = "When off, mixes can also bring in other songs you might like")
    SwitchRow("Rest songs you just heard", t.restRecent, { v -> backend.updateTaste { it.copy(restRecent = v) } },
        subtitle = "They come back over about a week")
    SectionLabel("Learn your taste from")
    SwitchRow("Favorites", t.learnsFrom(TasteSettings.FAVORITES_KEY), { v -> backend.updateTaste { it.copy(sources = it.sources + (TasteSettings.FAVORITES_KEY to v)) } }, Icons.Rounded.Favorite)
    for ((key, name) in remember { backend.playlistKeys() }) {
        SwitchRow(name, t.learnsFrom(key), { v -> backend.updateTaste { it.copy(sources = it.sources + (key to v)) } }, Icons.Rounded.QueueMusic, "Playlist")
    }
    SwitchRow("What I play and skip", t.learnFromPlays, { v -> backend.updateTaste { it.copy(learnFromPlays = v) } },
        subtitle = "Off by default, so mixes only learn from your lists")
    SectionLabel("Song information")
    SwitchRow("Look up lyrics and genres", t.lookups, { v -> backend.updateTaste { it.copy(lookups = v) } },
        subtitle = "Looks up about one song a second in the background")
    SectionLabel("Learned data")
    SettingsRow("Clear what mixes have learned", icon = Icons.Rounded.DeleteSweep, onClick = onClear,
        subtitle = "Play and skip counts, and the \"doesn't fit this mix\" choices")
}

/** Settings > Mixes > Weather. Only the chosen city's coordinates are sent, to Open-Meteo. */
@Composable
internal fun WeatherPage(backend: CoreBackend) {
    val place by backend.weatherPlace.collectAsState()
    val kind by backend.weather.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WeatherPlace>?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Weather mixes need a city. Only its coordinates are sent, to Open-Meteo, at most once an hour. Nothing is sent until you pick one.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        place?.let { p ->
            SettingsRow(p.name, icon = Icons.Rounded.Cloud, subtitle = kind?.let { "Right now: ${it.name.lowercase()}" } ?: "Checking the weather")
            OutlinedButton({ backend.setPlace(null) }) { Text("Turn weather off") }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("City") }, singleLine = true)
        Button({ busy = true; scope.launch { results = backend.searchPlaces(query); busy = false } }, enabled = query.isNotBlank() && !busy) {
            Text(if (busy) "Searching" else "Search")
        }
        results?.let { found ->
            if (found.isEmpty()) Text("No city found. Try just the city name.", color = MaterialTheme.colorScheme.error)
            found.forEach { p -> NavRow(p.name, icon = Icons.Rounded.Place) { backend.setPlace(p); results = null; query = "" } }
        }
    }
}

/** Settings > Devices > Connection. */
@Composable
internal fun ConnectionPage(conn: ConnectionUi, backend: CoreBackend) {
    val state = when {
        conn.ready -> "Connected as your bot"
        conn.state.isEmpty() -> "Not connected"
        else -> "Connecting (${conn.state.removePrefix("authorizationState")})"
    }
    SettingsRow("Telegram", icon = Icons.Rounded.Send, subtitle = state)
    if (conn.error != null) SettingsRow("Last problem", icon = Icons.Rounded.ErrorOutline, subtitle = conn.error)
    SettingsRow("Channels", icon = Icons.Rounded.Forum, subtitle = "${conn.chats.size} added")
    SettingsRow("Other devices", icon = Icons.Rounded.Devices, subtitle = "Not built yet: syncing between devices", enabled = false)
    if (!conn.ready) Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { OutlinedButton(backend::connect) { Text("Reconnect") } }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun ConnectionPagePhonePreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.size(390.dp, 300.dp)) {
            SettingsRow("Telegram", icon = Icons.Rounded.Send, subtitle = "Connected as your bot")
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun ConnectionPageDesktopPreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.size(1024.dp, 300.dp)) {
            SettingsRow("Telegram", icon = Icons.Rounded.Send, subtitle = "Connected as your bot")
        }
    }
}
