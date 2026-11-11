package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
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
import io.max.paperplay.core.account.ChatInfo
import io.max.paperplay.core.account.ChatPost
import io.max.paperplay.core.account.IndexSelection
import io.max.paperplay.core.account.IndexedSong
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Titles of the open chat pages ("index:<id>") for the settings header. */
internal val indexPageTitles = mutableMapOf<String, String>()

internal fun settingsPageTitle(id: String?): String? = id?.let { subTitles[it] ?: indexPageTitles[it] }

/** Settings > Telegram > Chats and channels: your own chats, what is indexed, and the auto options. */
@Composable
internal fun IndexPage(backend: CoreBackend, stack: MutableList<String>) {
    val loggedIn by backend.account.loggedIn.collectAsState()
    val indexed by backend.chatIndex.indexedChats.collectAsState()
    val progress by backend.chatIndex.progress.collectAsState()
    val scope = rememberCoroutineScope()
    val list = backend.myChats.collectAsState().value
    var auto by remember { mutableStateOf(backend.pref("index_auto") == "true") }
    var autoCopy by remember { mutableStateOf(backend.pref("index_auto_copy") == "true") }
    var message by remember { mutableStateOf<String?>(null) }

    if (!loggedIn) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Log in with your own account to see your chats and channels here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ stack.add("account") }) { Text("Log in") }
        }
        return
    }
    LaunchedEffect(loggedIn) { runCatching { backend.refreshChats() } }

    SectionLabel("Automatic")
    SwitchRow("Index automatically", auto, { auto = it; backend.setPref("index_auto", "$it") }, Icons.Rounded.Sync,
        "Reads only newer posts of indexed chats when the app starts and every few hours")
    SwitchRow("Fetch songs automatically", autoCopy, { autoCopy = it; backend.setPref("index_auto_copy", "$it") }, Icons.Rounded.ContentCopy,
        "Copies songs found in the index into your main chat when a Spotify playlist still waits for them")
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ scope.launch { runCatching { backend.chatIndex.refresh() }.onFailure { message = it.message } } }, enabled = indexed.isNotEmpty() && progress == null) {
            Text("Refresh index now")
        }
        progress?.let { Text("Indexing ${it.chatTitle}: ${it.found} songs", color = MaterialTheme.colorScheme.primary) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    SectionLabel("Your chats and channels")
    when {
        list == null -> Text("Reading your chats", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.isEmpty() -> Text("No groups or channels found.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> for (c in list) {
            val isIndexed = c.id in indexed
            SettingsRow(
                c.title, icon = if (c.isChannel) Icons.Rounded.Campaign else Icons.Rounded.Forum,
                subtitle = if (isIndexed) "Indexed" else if (c.isChannel) "Channel" else "Group",
                onClick = if (isIndexed) { { indexPageTitles["index:${c.id}"] = c.title; stack.add("index:${c.id}") } } else null,
            ) {
                if (isIndexed) AppIcon(Icons.Rounded.ChevronRight, null)
                else OutlinedButton({ scope.launch { runCatching { backend.chatIndex.indexChat(c) }.onFailure { message = it.message } } }, enabled = progress == null) { Text("Index") }
            }
        }
    }
}

/** The songs of one indexed chat, live while it is still being read. Nothing is picked until tapped. */
@Composable
internal fun ChatSongsPage(backend: CoreBackend, chatId: Long, stack: MutableList<String>) {
    val songs by remember(chatId) { backend.chatIndex.songs(chatId) }.collectAsState(emptyList())
    val progress by backend.chatIndex.progress.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(IndexSelection()) }
    var limit by remember { mutableStateOf(100) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val shown = remember(songs, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) songs else songs.filter { "${it.post.title} ${it.post.artist}".lowercase().contains(q) }
    }
    val live = progress?.takeIf { it.chatId == chatId }

    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (live != null) "Indexing: ${live.found} songs read so far" else "${songs.size} songs",
            color = if (live != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ selection = selection.pickNew(shown) }) { Text("Pick new") }
            OutlinedButton({ selection = selection.clear() }, enabled = selection.picked.isNotEmpty()) { Text("Clear") }
        }
        Button(
            {
                val todo = selection.toCopy(songs)
                busy = true
                scope.launch {
                    var failed = 0
                    todo.forEachIndexed { i, p ->
                        status = "Copying ${i + 1} of ${todo.size}"
                        if (runCatching { backend.chatIndex.copyToMain(p) }.isFailure) failed++
                        delay(3_000)
                    }
                    status = if (failed == 0) "Copied ${todo.size}" else "Copied ${todo.size - failed}, $failed failed"
                    selection = selection.clear()
                    busy = false
                }
            },
            enabled = selection.picked.isNotEmpty() && !busy,
        ) { Text("Copy ${selection.picked.size} to main chat") }
        status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedButton({ backend.chatIndex.forget(chatId); if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }) { Text("Forget this chat") }
    }

    for (s in shown.take(limit)) SongPostRow(backend, s, s.post.messageId in selection.picked,
        onToggle = { selection = selection.toggle(s.post.messageId) },
        onCopy = { scope.launch { status = if (runCatching { backend.chatIndex.copyToMain(s.post) }.isSuccess) "Copying ${s.post.title}" else "Could not copy ${s.post.title}" } })
    if (shown.size > limit) Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { OutlinedButton({ limit += 100 }) { Text("Show more") } }
}

@Composable
private fun SongPostRow(backend: CoreBackend, s: IndexedSong, picked: Boolean, onToggle: () -> Unit, onCopy: () -> Unit) {
    val label = when {
        s.copied -> "Copied"
        s.inMainChat -> "In your library"
        else -> null
    }
    SettingsRow(
        s.post.title.ifBlank { s.post.fileName }, onClick = onToggle,
        subtitle = listOfNotNull(s.post.artist.ifBlank { null }, label).joinToString(" · ").ifEmpty { null },
    ) {
        Checkbox(picked, { onToggle() })
        IconButton(onCopy, enabled = !s.copied && !s.inMainChat) { AppIcon(Icons.Rounded.Add, "Copy to main chat") }
    }
}

/** For a track a Spotify playlist still waits for: the best song the index has, with a plus button to copy it. */
@Composable
internal fun IndexMatchRow(backend: CoreBackend, title: String, artist: String, durationSec: Int) {
    val scope = rememberCoroutineScope()
    var post by remember(title, artist) { mutableStateOf<ChatPost?>(null) }
    var state by remember(title, artist) { mutableStateOf<String?>(null) }
    LaunchedEffect(title, artist) {
        post = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { backend.chatIndex.matchesFor(title, artist, durationSec).firstOrNull() }.getOrNull()
        }
    }
    val p = post ?: return
    val chat = backend.chatIndex.indexedChats.value[p.chatId] ?: "your chats"
    Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(state ?: "In $chat: ${p.title} - ${p.artist}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (state == null) IconButton({ scope.launch { state = if (runCatching { backend.chatIndex.copyToMain(p) }.isSuccess) "Copying" else "Could not copy" } }) {
            AppIcon(Icons.Rounded.Add, "Copy to main chat")
        }
    }
}
