package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.app.TaskItem
import io.max.paperplay.core.app.TaskKind
import io.max.paperplay.core.app.TaskQueue
import io.max.paperplay.core.app.TaskState
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.nowMoment

/** Running tasks with progress and a stop button, then the history with a clear button. */
@Composable
fun TasksScreen(queue: TaskQueue, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val running by queue.running.collectAsState()
    val history by queue.history.collectAsState()
    val clear: @Composable () -> Unit = {
        IconButton({ queue.clearHistory() }, enabled = history.isNotEmpty()) { AppIcon(Icons.Rounded.DeleteSweep, "Clear history") }
    }
    Column(modifier) {
        if (onBack != null) BackTopBar("Tasks", onBack, actions = clear) else TopBar("Tasks", actions = clear)
        if (running.isEmpty() && history.isEmpty()) {
            Text("Nothing running", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(running, key = { "r${it.id}" }) { t -> RunningRow(t) { queue.stop(t.id) } }
            if (history.isNotEmpty()) {
                item(key = "h") {
                    Text("History", Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
                }
                items(history, key = { "h${it.id}" }) { t -> HistoryRow(t) { queue.removeFromHistory(t.id) } }
            }
        }
    }
}

private fun iconFor(kind: TaskKind): ImageVector = when (kind) {
    TaskKind.Scan -> Icons.Rounded.Sync
    TaskKind.Index -> Icons.Rounded.ManageSearch
    TaskKind.Sync -> Icons.Rounded.Sync
    TaskKind.Covers -> Icons.Rounded.Image
    TaskKind.Info -> Icons.Rounded.Lyrics
    TaskKind.Upload -> Icons.Rounded.Upload
    TaskKind.Other -> Icons.Rounded.TaskAlt
}

@Composable
private fun RunningRow(t: TaskItem, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(iconFor(t.kind), null)
        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge)
            val line = if (t.total > 0) "${t.done} of ${t.total}" else t.detail
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val f = if (t.total > 0) t.done.toFloat() / t.total else t.fraction
            if (f != null) LinearProgressIndicator(progress = { f.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (t.stoppable) IconButton(onStop) { AppIcon(Icons.Rounded.Close, "Stop") }
    }
}

@Composable
private fun HistoryRow(t: TaskItem, onRemove: () -> Unit) {
    val state = when (t.state) {
        TaskState.Failed -> "failed"
        TaskState.Stopped -> "stopped"
        else -> if (t.total > 0 || t.done > 0) "${t.done} done" else "done"
    }
    val parts = listOfNotNull(t.detail.takeIf { it.isNotBlank() && t.state != TaskState.Done }, state, ago(nowMoment().epochMs - t.endedAt))
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(iconFor(t.kind), null)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge)
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onRemove) { AppIcon(Icons.Rounded.Close, "Remove") }
    }
}

private fun ago(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / (60 * 24)} d ago"
    }
}

@Preview
@Composable
private fun RunningRowPreview() {
    val sampleTask = io.max.paperplay.core.app.TaskItem(
        id = 1, kind = io.max.paperplay.core.app.TaskKind.Index, title = "Indexing chats",
        done = 3, total = 10, detail = "Reading 3 of 10", startedAt = 0, state = io.max.paperplay.core.app.TaskState.Running,
    )
    RunningRow(t = sampleTask, onStop = {})
}

@Preview(name = "History row", showBackground = true)
@Composable
private fun HistoryRowPreview() = io.max.paperplay.ui.PreviewFrame {
    HistoryRow(
        t = io.max.paperplay.core.app.TaskItem(
            id = 2, kind = io.max.paperplay.core.app.TaskKind.Index, title = "Indexing chats",
            done = 10, total = 10, detail = "", startedAt = 0, state = io.max.paperplay.core.app.TaskState.Done,
        ),
        onRemove = {},
    )
}
