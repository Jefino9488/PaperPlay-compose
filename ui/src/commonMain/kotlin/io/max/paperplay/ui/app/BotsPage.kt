package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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

/**
 * Settings > Telegram > Bots. The main bot's credentials and the extra bots that share uploads, in one place.
 * Each bot must be an admin of the chat it posts to.
 */
@Composable
internal fun BotsPage(backend: CoreBackend) {
    val pool = backend.services.botPool
    val bots by pool.bots.collectAsState()
    val main by pool.mainBot.collectAsState()
    var now by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { pool.load(); while (true) { delay(1000); now = Clock.System.now().toEpochMilliseconds() } }
    // The main bot may still be logging in; ask for its name and picture until it answers.
    LaunchedEffect(Unit) { repeat(20) { if (runCatching { pool.loadMainProfile() }.getOrDefault(false)) return@LaunchedEffect; delay(3000) } }
    val scope = rememberCoroutineScope()
    BotsContent(
        mainStatus = status(main, now), main = main, bots = bots.map { it to status(it, now) },
        onSaveCredentials = backend::saveCredentials,
        onAddBot = { token -> pool.add(token) },
        onRemove = { id -> scope.launch { pool.remove(id) } },
    )
}

@Composable
internal fun BotsContent(
    mainStatus: String,
    bots: List<Pair<PoolBot, String>>,
    onSaveCredentials: (apiId: String, apiHash: String, token: String) -> String?,
    onAddBot: suspend (token: String) -> String?,
    onRemove: (Long) -> Unit,
    main: PoolBot? = null,
) {
    val scope = rememberCoroutineScope()
    var editMain by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    SectionLabel("Main bot")
    BotRow(main?.name ?: "Main bot", main?.username, main?.photoPath, mainStatus) {
        TextButton({ editMain = !editMain }) { Text(if (editMain) "Close" else "Edit") }
    }
    if (editMain) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("API id, API hash and bot token. Saving reconnects the bot.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            CredentialsForm(onSaveCredentials, { editMain = false })
        }
    }

    SectionLabel("Extra bots")
    Text(
        "Extra bots share the uploading so it goes faster. Make each one an admin of your library chats.",
        Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for ((b, text) in bots) {
        BotRow(b.name ?: b.username ?: "Bot ${b.id}", if (b.name != null) b.username else null, b.photoPath, text) {
            OutlinedButton({ onRemove(b.id) }) { Text("Remove") }
        }
    }
    if (bots.isEmpty()) Text("No extra bots yet.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (adding) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Bot token from BotFather") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    busy = true
                    scope.launch {
                        val error = onAddBot(token.trim())
                        message = error ?: "Added"
                        if (error == null) { token = ""; adding = false }
                        busy = false
                    }
                }, enabled = token.isNotBlank() && !busy) { Text(if (busy) "Checking" else "Add") }
                OutlinedButton({ adding = false; token = ""; message = null }) { Text("Cancel") }
            }
        }
    } else {
        OutlinedButton({ adding = true; message = null }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Icon(Icons.Rounded.Add, null, Modifier.padding(end = 8.dp))
            Text("Add bot")
        }
    }
    message?.let { Text(it, Modifier.padding(horizontal = 20.dp)) }
}

/** One bot: its picture (or initials), name, @username, status and a button. */
@Composable
private fun BotRow(name: String, username: String?, photoPath: String?, status: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Avatar(photoPath, name, 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (username != null) Text(username, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Bots", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun BotsPreview() = io.max.paperplay.ui.PreviewFrame {
    Column {
        BotsContent(
            mainStatus = "Working. Sent 120, limited 2 times",
            bots = listOf(
                PoolBot(1, "uploader_one_bot", "working", sent = 40, floods = 1) to "Working. Sent 40, limited 1 times",
                PoolBot(2, "uploader_two_bot", "error", error = "Not an admin of the chat") to "Not an admin of the chat. Sent 0, limited 0 times",
            ),
            onSaveCredentials = { _, _, _ -> null }, onAddBot = { null }, onRemove = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Bots empty", widthDp = 390, heightDp = 500, showBackground = true)
@Composable
private fun BotsEmptyPreview() = io.max.paperplay.ui.PreviewFrame {
    Column { BotsContent("Working. Sent 0, limited 0 times", emptyList(), { _, _, _ -> null }, { null }, {}) }
}
