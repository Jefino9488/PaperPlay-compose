package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

/** What the repair page shows. [lines] is the running log; [summary] appears when the run ends. */
internal data class RepairUi(val running: Boolean = false, val lines: List<String> = emptyList(), val summary: List<String> = emptyList(), val gone: Int = 0)

/**
 * One button that tidies the library without deleting or moving anything: reads every chat again (adds missing
 * songs, drops ones whose post is gone), then starts the info and cover lookups. Moving and deleting stay on their
 * own pages because they need a yes from the owner.
 */
@Composable
internal fun RepairPage(backend: CoreBackend, stack: MutableList<String>) {
    var ui by remember { mutableStateOf(RepairUi()) }
    RepairContent(
        ui,
        onRun = {
            ui = RepairUi(running = true)
            backend.services.scope.launch { runRepair(backend) { ui = it(ui) } }
        },
        onOpen = { stack.add(it) },
        onRemoveGone = { backend.services.scanner.removeGone(); ui = ui.copy(gone = 0) },
    )
}

private suspend fun runRepair(backend: CoreBackend, update: ((RepairUi) -> RepairUi) -> Unit) {
    fun log(line: String) = update { it.copy(lines = it.lines + line) }
    val services = backend.services
    try {
        val before = services.library.allSongs().first().size
        val chats = services.library.chatList()
        if (chats.isEmpty()) log("No chats in the library yet. Add one under Channels.")
        services.scanner.retryEmptyIds = true
        try {
            for (chat in chats) {
                log("Reading ${chat.title}")
                val ok = services.scanner.rescanChat(chat)
                log(if (ok) services.scanner.report.value ?: "${chat.title}: done" else "${chat.title}: could not read it completely (is the bot connected and an admin?)")
            }
        } finally {
            services.scanner.retryEmptyIds = false
        }
        log("Starting info and cover lookups")
        services.enricher.kick()
        services.covers.kick()
        val songs = services.library.allSongs().first()
        val blank = { s: String? -> s.isNullOrBlank() || s == "Unknown artist" }
        update {
            it.copy(
                running = false,
                gone = services.scanner.gone.value.size,
                summary = listOf(
                    "Songs: $before before, ${songs.size} now",
                    "Without an artist: ${songs.count { s -> blank(s.artist) }}",
                    "Without an album: ${songs.count { s -> s.album.isBlank() }}",
                    "Without a cover: ${songs.count { s -> s.cover_path.isNullOrEmpty() && s.cover_url.isNullOrEmpty() }}",
                    "Lookups keep running in the background; check again in a few minutes.",
                ),
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        update { it.copy(running = false, lines = it.lines + "Stopped: ${e.message ?: e::class.simpleName}") }
    }
}

@Composable
internal fun RepairContent(ui: RepairUi, onRun: () -> Unit, onOpen: (String) -> Unit, onRemoveGone: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    if (confirm) ConfirmDialog(
        "Remove ${ui.gone} songs from the app?",
        "Telegram said their posts are not there. A bot cannot see posts another bot made as itself, so check first. Nothing is deleted from Telegram, and a rescan adds them back if they exist.",
        "Remove", onRemoveGone, { confirm = false },
    )
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Reads every chat again, adds songs that were missed, removes songs whose post is gone, and starts the info and cover lookups. Nothing is moved or deleted from Telegram.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onRun, Modifier.fillMaxWidth(), enabled = !ui.running) { Text(if (ui.running) "Repairing" else "Repair library") }
        if (ui.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (ui.summary.isNotEmpty()) {
            Text("Result", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            ui.summary.forEach { Text(it) }
        }
        if (ui.gone > 0) {
            Text("${ui.gone} songs could not be found on Telegram. They were kept.", color = MaterialTheme.colorScheme.error)
            OutlinedButton({ confirm = true }, Modifier.fillMaxWidth()) { Text("Remove them from the app") }
        }
        Text("Needs your yes first", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        OutlinedButton({ onOpen("dupes") }, Modifier.fillMaxWidth()) { Text("Find duplicates") }
        OutlinedButton({ onOpen("sort") }, Modifier.fillMaxWidth()) { Text("Sort General") }
        OutlinedButton({ onOpen("missing") }, Modifier.fillMaxWidth()) { Text("Song info left") }
        Text(
            "A bot cannot read songs that another bot posted as itself. Use Recover songs in the Library settings: your account posts them again so the bot can read them.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Repair idle", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun RepairIdlePreview() = io.max.paperplay.ui.PreviewFrame { RepairContent(RepairUi(), {}, {}, {}) }

@Preview(name = "Repair done", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun RepairDonePreview() = io.max.paperplay.ui.PreviewFrame {
    RepairContent(
        RepairUi(
            lines = listOf("Reading My Music", "My Music: 3269 song posts read up to message 41233, 516 new, 12 found on a second ask, 40 ids held nothing", "Starting info and cover lookups"),
            summary = listOf("Songs: 2753 before, 3269 now", "Without an artist: 4", "Without an album: 31", "Without a cover: 210", "Lookups keep running in the background; check again in a few minutes."),
        gone = 12), {}, {}, {},
    )
}

@Preview(name = "Repair running", widthDp = 390, heightDp = 500, showBackground = true)
@Composable
private fun RepairRunningPreview() = io.max.paperplay.ui.PreviewFrame { RepairContent(RepairUi(running = true, lines = listOf("Reading My Music")), {}, {}, {}) }
