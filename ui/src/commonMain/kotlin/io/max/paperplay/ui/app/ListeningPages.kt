package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.max.paperplay.player.EQ_BANDS_HZ
import kotlinx.coroutines.launch

private val eqPresets = linkedMapOf(
    "Flat" to listOf(0f, 0f, 0f, 0f, 0f),
    "Bass boost" to listOf(6f, 4f, 0f, 0f, 0f),
    "Treble boost" to listOf(0f, 0f, 0f, 3f, 6f),
    "Vocal" to listOf(-2f, 0f, 3f, 3f, 0f),
    "Rock" to listOf(4f, 2f, -1f, 2f, 4f),
    "Loudness" to listOf(5f, 2f, 0f, 2f, 5f),
)

private fun db(v: Float): String {
    val t = kotlin.math.round(v * 10) / 10f
    return if (t > 0) "+$t" else "$t"
}

private fun hz(f: Int) = if (f >= 1000) "${f / 1000.0} kHz".replace(".0 ", " ") else "$f Hz"

/** Settings > Listening > Equalizer. Five bands, plus a few presets. */
@Composable
internal fun EqualizerPage(backend: CoreBackend) {
    val on by backend.eqOn.collectAsState()
    val gains by backend.eqGains.collectAsState()
    SwitchRow("Equalizer", on, { backend.setEqualizer(on = it) }, Icons.Rounded.Equalizer, "Applies to this device only")
    SectionLabel("Presets")
    ChoiceRow(eqPresets.keys.toList(), eqPresets.entries.firstOrNull { e -> e.value.zip(gains).all { (a, b) -> kotlin.math.abs(a - b) < 0.05f } }?.key ?: "", { it }, { backend.setEqualizer(on = true, gains = eqPresets.getValue(it)) })
    SectionLabel("Bands")
    var drag by remember(gains) { mutableStateOf(gains) }
    EQ_BANDS_HZ.forEachIndexed { i, f ->
        SliderRow("${hz(f)}: ${db(drag[i])} dB", drag[i], -12f..12f, { v -> drag = drag.toMutableList().also { it[i] = v } },
            onDone = { backend.setEqualizer(on = true, gains = drag) })
    }
    Text("The system equalizer on a phone has few bands, so each takes the setting of the nearest one here.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Settings > Listening > ListenBrainz. */
@Composable
internal fun ListenBrainzPage(backend: CoreBackend) {
    val user by backend.lbUser.collectAsState()
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (user != null) {
            Text("Sending what you listen to as $user.", style = MaterialTheme.typography.titleMedium)
            Text("A song counts after half of it, or four minutes, whichever comes first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(backend::disconnectListenBrainz) { Text("Disconnect") }
        } else {
            Text(
                "Paste your user token from listenbrainz.org/settings. Titles and artists of songs you finish are sent to ListenBrainz.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("User token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Button({ busy = true; problem = null; scope.launch { problem = backend.connectListenBrainz(token); busy = false; if (problem == null) token = "" } }, enabled = token.isNotBlank() && !busy) {
                Text(if (busy) "Checking" else "Connect")
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
