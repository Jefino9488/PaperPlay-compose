package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.account.ChatInfo
import io.max.paperplay.core.account.RecoveryMode
import kotlinx.coroutines.launch

/**
 * Recover songs the bot cannot read. They sit in the main chat but were posted by another bot as itself, so Telegram
 * hides them from the main bot. Both ways use your own account to see them and post them again where the bot can read.
 */
@Composable
internal fun RecoverPage(backend: CoreBackend) {
    val loggedIn by backend.account.loggedIn.collectAsState()
    val indexed by backend.chatIndex.indexedChats.collectAsState()
    val indexing by backend.chatIndex.progress.collectAsState()
    val progress by backend.services.recovery.progress.collectAsState()
    val scope = rememberCoroutineScope()
    val main = backend.mainChatId()
    var count by remember { mutableStateOf<Int?>(null) }
    var deleteOriginals by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    val mainIndexed = main != null && main in indexed

    LaunchedEffect(mainIndexed, indexing == null, progress.running) {
        count = if (mainIndexed && indexing == null && !progress.running) runCatching { backend.services.recovery.candidates().size }.getOrNull() else null
    }

    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Some songs sit in your main chat but the bot cannot read them, because another bot posted them as itself. " +
                "Your own account can see them. Recovering posts each song again, so the bot reads it, and can then remove the old post.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            main == null -> Text("Choose a main chat under Channels and chats first.", color = MaterialTheme.colorScheme.error)
            !loggedIn -> Text("Log in with your own account first (Telegram > Your account).", color = MaterialTheme.colorScheme.error)
            !mainIndexed -> {
                Text("Step 1: your account reads the main chat once to see every song in it.")
                Button({
                    scope.launch {
                        runCatching { backend.chatIndex.indexChat(ChatInfo(main, backend.services.library.chat(main)?.title ?: "Main chat", true)) }
                            .onFailure { message = it.message }
                    }
                }, Modifier.fillMaxWidth(), enabled = indexing == null) { Text(if (indexing == null) "Read the main chat" else "Reading") }
                if (indexing != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            else -> {
                Text(
                    when (val n = count) {
                        null -> "Counting"
                        0 -> "The bot can read every song your account sees in the main chat."
                        else -> "$n songs in the main chat cannot be read by the bot."
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton({ scope.launch { runCatching { backend.chatIndex.refresh() } } }, Modifier.fillMaxWidth(), enabled = indexing == null && !progress.running) {
                    Text("Read new posts first")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(deleteOriginals, { deleteOriginals = it })
                    Text("Delete the old post once the new one is in", Modifier.weight(1f))
                }
                Text("Way 1: copy with your account", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Fast, nothing is downloaded. Your account copies each post into the main chat, so it appears as posted by you. " +
                        "Telegram refuses it when the chat blocks forwarding or saving, and your account needs the right to delete if you remove old posts.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button({ backend.services.recovery.start(RecoveryMode.CopyByAccount, deleteOriginals) }, Modifier.fillMaxWidth(), enabled = !progress.running && (count ?: 0) > 0) {
                    Text("Copy with my account")
                }
                Text("Way 2: your main bot posts them again", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Your account downloads each song and your main bot uploads it, so the new post is the bot's own. Slower and uses data, " +
                        "but works when copying is blocked. The other bots are not needed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button({ backend.services.recovery.start(RecoveryMode.ReuploadByBot, deleteOriginals) }, Modifier.fillMaxWidth(), enabled = !progress.running && (count ?: 0) > 0) {
                    Text("Download and upload with my bot")
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (progress.text.isNotEmpty()) {
            Text(progress.text, style = MaterialTheme.typography.titleSmall)
            if (progress.running) {
                if (progress.total > 0) LinearProgressIndicator({ (progress.done + progress.failed).toFloat() / progress.total }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton({ backend.services.recovery.cancel() }) { Text("Stop") }
            }
            if (progress.total > 0) Text("${progress.done} of ${progress.total} done, ${progress.failed} failed, ${progress.removed} old posts removed", style = MaterialTheme.typography.bodySmall)
            progress.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        Text(
            "It never deletes a post before its new copy exists. Sending is paced by the account copy speed in Telegram settings. " +
                "Songs are matched by file, then by title and artist, so a different recording of the same song is not mistaken for a copy.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
