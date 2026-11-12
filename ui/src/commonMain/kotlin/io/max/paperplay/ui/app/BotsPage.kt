package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.telegram.PoolBot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

private fun status(b: PoolBot, now: Long): String {
    val wait = ((b.waitUntil - now) / 1000).coerceAtLeast(0)
    val base = when (b.state) { "working" -> "Working"; "error" -> b.error ?: "Not working"; else -> "Logging in" }
    val flood = if (wait > 0) ", waiting ${wait}s (Telegram rate limit)" else ""
    return "$base. Sent ${b.sent}, limited ${b.floods} times$flood"
}

/** Settings > Extra bots. More bots share the uploading; each must be an admin of the chat. */
@Composable
internal fun BotsPage(backend: CoreBackend) {
    val pool = backend.services.botPool
    val bots by pool.bots.collectAsState()
    val main by pool.mainBot.collectAsState()
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { pool.load(); while (true) { delay(1000); now = Clock.System.now().toEpochMilliseconds() } }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Extra bots share the uploading so it goes faster. Make each one an admin of your library chats.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Bot token from BotFather") }, singleLine = true)
        Button({
            busy = true
            scope.launch { message = pool.add(token) ?: "Added".also { token = "" }; busy = false }
        }, enabled = token.isNotBlank() && !busy) { Text(if (busy) "Checking" else "Add bot") }
        message?.let { Text(it) }
    }
    SectionLabel("Bots")
    SettingsRow("Main bot", subtitle = status(main, now))
    for (b in bots) {
        SettingsRow(b.username ?: "Bot ${b.id}", subtitle = status(b, now)) {
            OutlinedButton({ scope.launch { pool.remove(b.id) } }) { Text("Remove") }
        }
    }
    if (bots.isEmpty()) Text("No extra bots yet.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
