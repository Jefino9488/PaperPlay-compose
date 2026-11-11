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
import io.max.paperplay.core.account.asSong
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
    var autoAdd by remember { mutableStateOf(backend.pref("index_auto_add") == "true") }
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
    SwitchRow("Add new songs automatically", autoAdd, { autoAdd = it; backend.setPref("index_auto_add", "$it") }, Icons.Rounded.Add,
        "Adds songs of your main chat that the bot cannot see, such as posts by other bots, when the index refreshes")
    SwitchRow("Fetch songs automatically", autoCopy, { autoCopy = it; backend.setPref("index_auto_copy", "$it") }, Icons.Rounded.ContentCopy,
        "Copies songs found in the index into your main chat when a Spotify playlist still waits for them")
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ scope.launch { runCatching { backend.chatIndex.refresh() }.onFailure { message = it.message } } }, enabled = indexed.isNotEmpty() && progress == null) {
            Text("Refresh index now")
        }
        progress?.let { Text("Indexing ${it.chatTitle}: ${it.found} songs", color = MaterialTheme.colorScheme.primary) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    var search by remember { mutableStateOf("") }
    OutlinedTextField(
        search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        label = { Text("Search chats and channels") }, singleLine = true,
    )
    // Indexed chats are kept even when the account's chat list has not loaded yet.
    val all = (list.orEmpty() + indexed.filterKeys { id -> list.orEmpty().none { it.id == id } }.map { (id, title) -> ChatInfo(id, title, true) })
    val q = search.trim().lowercase()
    val shown = if (q.isEmpty()) all else all.filter { it.title.lowercase().contains(q) }
    val (done, rest) = shown.partition { it.id in indexed }

    SectionLabel("Indexed (${done.size})")
    if (done.isEmpty()) Text(if (q.isEmpty()) "Nothing indexed yet." else "No indexed chat matches.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (c in done) {
        SettingsRow(
            c.title, icon = if (c.isChannel) Icons.Rounded.Campaign else Icons.Rounded.Forum,
            subtitle = if (c.isChannel) "Channel, indexed" else "Group, indexed",
            onClick = { indexPageTitles["index:${c.id}"] = c.title; stack.add("index:${c.id}") },
        ) { AppIcon(Icons.Rounded.ChevronRight, null) }
    }

    SectionLabel("Your other chats and channels (${rest.size})")
    when {
        list == null && indexed.isEmpty() -> Text("Reading your chats", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        rest.isEmpty() -> Text(if (q.isEmpty()) "No other groups or channels." else "No other chat matches.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> for (c in rest) {
            SettingsRow(
                c.title, icon = if (c.isChannel) Icons.Rounded.Campaign else Icons.Rounded.Forum,
                subtitle = if (c.isChannel) "Channel" else "Group",
            ) {
                OutlinedButton({ scope.launch { runCatching { backend.chatIndex.indexChat(c) }.onFailure { message = it.message } } }, enabled = progress == null) { Text("Index") }
            }
        }
    }
}

private val reviewFilters = listOf("All", "New", "Better than yours", "Already have", "In your Spotify playlists")

private fun sizeText(b: Long) = if (b >= 1L shl 30) "${b / (1L shl 30)} GB" else "${b / (1L shl 20)} MB"

private fun formatOf(p: ChatPost) = io.max.paperplay.core.dupes.DefaultSongRules.format(
    p.asSong(),
)

/** The songs of one indexed chat, live while it is still being read. Nothing is picked until tapped. */
@Composable
internal fun ChatSongsPage(backend: CoreBackend, chatId: Long, stack: MutableList<String>) {
    val songs by remember(chatId) { backend.chatIndex.songs(chatId) }.collectAsState(emptyList())
    val progress by backend.chatIndex.progress.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    var selection by remember { mutableStateOf(IndexSelection()) }
    var limit by remember { mutableStateOf(100) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun inFilter(s: IndexedSong, f: Int) = when (f) {
        1 -> !s.inMainChat && !s.copied
        2 -> s.better
        3 -> s.inMainChat
        4 -> s.wanted
        else -> true
    }
    val counts = remember(songs) { reviewFilters.indices.map { f -> songs.count { inFilter(it, f) } } }
    val shown = remember(songs, query, filter) {
        val q = query.trim().lowercase()
        val found = songs.filter { inFilter(it, filter) && (q.isEmpty() || "${it.post.title} ${it.post.artist}".lowercase().contains(q)) }
        // Songs that are not in the library come first; each group keeps its newest-first order.
        found.sortedBy { it.inMainChat || it.copied }
    }
    val live = progress?.takeIf { it.chatId == chatId }

    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                if (live != null) "Indexing: ${live.found} songs read so far" else "${songs.size} songs",
                color = if (live != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton({ scope.launch { runCatching { backend.chatIndex.refresh() } } }, enabled = live == null) { AppIcon(Icons.Rounded.Refresh, "Refresh index") }
        }
        ChipRow(reviewFilters.mapIndexed { i, l -> "$l ${counts[i]}" }, filter, { filter = it; limit = 100 })
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ selection = IndexSelection(shown.map { it.post.messageId }.toSet()) }, enabled = shown.isNotEmpty()) { Text("Select all") }
            OutlinedButton({ selection = selection.clear() }, enabled = selection.picked.isNotEmpty()) { Text("Clear") }
        }
        Button(
            {
                val todo = if (selection.picked.isNotEmpty()) selection.toCopy(songs) else shown.filter { !it.inMainChat && !it.copied }.map { it.post }
                busy = true
                scope.launch {
                    todo.forEach { runCatching { backend.chatIndex.addToLibrary(it) } }
                    status = "Added ${todo.size} to your library. They play through your account."
                    selection = selection.clear()
                    busy = false
                }
            },
            enabled = !busy && (selection.picked.isNotEmpty() || shown.any { !it.inMainChat && !it.copied }),
        ) {
            val n = if (selection.picked.isNotEmpty()) selection.picked.size else shown.count { !it.inMainChat && !it.copied }
            Text(if (selection.picked.isNotEmpty()) "Add $n picked to library" else "Add all shown ($n new)")
        }
        OutlinedButton(
            {
                val todo = selection.toCopy(songs)
                busy = true
                scope.launch {
                    var failed = 0
                    var why: String? = null
                    todo.forEachIndexed { i, p ->
                        status = "Copying ${i + 1} of ${todo.size}"
                        runCatching { backend.chatIndex.copyToMain(p) }.onFailure { failed++; if (why == null) why = it.message }
                        delay(3_000)
                    }
                    status = if (failed == 0) "Copied ${todo.size}" else "Copied ${todo.size - failed}, $failed failed. ${why.orEmpty()}"
                    selection = selection.clear()
                    busy = false
                }
            },
            enabled = selection.picked.isNotEmpty() && !busy,
        ) { Text("Copy ${selection.picked.size} to main chat instead") }
        val missing = songs.count { !it.inMainChat && !it.copied }
        Text("$missing of ${songs.size} are not in your library", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton({
            val first = songs.firstOrNull { !it.inMainChat && !it.copied } ?: return@OutlinedButton
            scope.launch { status = "Asking the bot about \"${first.post.title}\"..."; status = backend.services.botCheck(first.post.chatId, first.post.messageId) }
        }, enabled = missing > 0) { Text("Check why the first missing song is missing") }
        status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedButton({ backend.chatIndex.forget(chatId); if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }) { Text("Forget this chat") }
    }

    for (s in shown.take(limit)) SongPostRow(s, s.post.messageId in selection.picked,
        onToggle = { selection = selection.toggle(s.post.messageId) },
        onCopy = { scope.launch { status = runCatching { backend.chatIndex.addToLibrary(s.post) }.fold({ "Added ${s.post.title} to your library" }, { "Could not add ${s.post.title}: ${it.message}" }) } })
    if (shown.size > limit) Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { OutlinedButton({ limit += 100 }) { Text("Show more") } }
}

@Composable
private fun SongPostRow(s: IndexedSong, picked: Boolean, onToggle: () -> Unit, onCopy: () -> Unit) {
    val label = when {
        s.copied -> "Copied"
        s.better -> "Better than yours"
        s.inMainChat -> "Already have"
        s.wanted -> "In your Spotify playlists"
        else -> null
    }
    SettingsRow(
        s.post.title.ifBlank { s.post.fileName }, onClick = onToggle,
        subtitle = listOfNotNull(s.post.artist.ifBlank { null }, formatOf(s.post), if (s.post.size > 0) sizeText(s.post.size) else null, label).joinToString(" \u00b7 "),
    ) {
        Checkbox(picked, { onToggle() })
        IconButton(onCopy, enabled = !s.copied && !s.inMainChat) { AppIcon(Icons.Rounded.Add, "Add to library") }
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
