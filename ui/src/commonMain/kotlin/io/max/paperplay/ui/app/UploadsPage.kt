package io.max.paperplay.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.app.UploadJob
import io.max.paperplay.core.app.UploadStatus
import io.max.paperplay.ui.LocalAppEnv
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.flow.MutableStateFlow

/** Files handed to the app from outside (the Android share sheet). The uploads page queues them and clears this. */
object IncomingShares {
    val files = MutableStateFlow<List<String>>(emptyList())
}

private data class UploadTarget(val chatId: Long, val chatTitle: String, val topicId: Long? = null, val topicName: String? = null)

/** The uploads hub: where songs go, picking files, and the queue with retry. */
@Composable
internal fun UploadsPage(backend: CoreBackend) {
    val env = LocalAppEnv.current
    val uploader = backend.services.uploader
    val jobs by uploader.jobs.collectAsState()
    val paused by uploader.isPaused.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val chats = remember { backend.services.library.chatList() }
    val topics = remember { backend.services.library.repo.db.miscQueries.topics().executeAsList() }
    var target by remember {
        val main = backend.mainChatId()
        val chat = chats.firstOrNull { it.id == main } ?: chats.firstOrNull()
        mutableStateOf(chat?.let { UploadTarget(it.id, it.title) })
    }

    fun queue(paths: List<String>, temporary: Boolean) {
        val t = target
        if (t == null) { message = "Choose where to upload first."; return }
        val r = uploader.enqueue(paths, t.chatId, t.chatTitle, t.topicId, t.topicName, temporary)
        message = when {
            r.queued == 0 && r.known == 0 -> "No audio files found."
            else -> buildString {
                append("Added ${r.queued} song${if (r.queued == 1) "" else "s"}.")
                if (r.known > 0) append(" ${r.known} already in your library.")
                if (r.notAudio > 0) append(" Left out ${r.notAudio} that are not audio.")
            }
        }
    }

    val shared by IncomingShares.files.collectAsState()
    LaunchedEffect(shared, target) {
        if (shared.isNotEmpty() && target != null) {
            val files = shared
            IncomingShares.files.value = emptyList()
            queue(files, temporary = true)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Upload to")
        SettingsRow(
            target?.chatTitle ?: "No chat chosen", icon = Icons.Rounded.Forum,
            subtitle = target?.topicName?.let { "Topic: $it" } ?: if (target == null) "Add a channel in Settings first" else "Tap to change",
            onClick = { choosing = true }, enabled = chats.isNotEmpty(),
        )
        val pick = env?.pickAudio
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { pick?.invoke { paths, temp -> queue(paths, temp) } }, enabled = pick != null && target != null) {
                AppIcon(Icons.Rounded.Upload, null, Modifier.size(18.dp))
                Text("Choose songs", Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = { uploader.setPaused(!paused) }, enabled = jobs.isNotEmpty()) { Text(if (paused) "Resume" else "Pause") }
        }
        message?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

        val failed = jobs.count { it.status == UploadStatus.Failed }
        val done = jobs.count { it.status == UploadStatus.Done }
        SectionLabel("Queue (${jobs.size})")
        if (jobs.isEmpty()) {
            Text("Nothing is uploading. Songs you choose, or share to Paper Play from another app, wait here.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(Modifier.padding(horizontal = 12.dp)) {
                if (failed > 0) TextButton({ uploader.retryAll() }) { Text("Retry $failed failed") }
                if (done > 0) TextButton({ uploader.clearFinished() }) { Text("Clear $done finished") }
            }
            if (paused) Text("Paused. Files being sent finish first.", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (job in jobs) JobRow(job, { uploader.retry(job.id) }, { uploader.remove(job.id) })
        }
    }

    if (choosing) TargetDialog(chats.map { it.id to it.title }, topics.map { Triple(it.chat_id, it.topic_id, it.name) }, target, { target = it; choosing = false }) { choosing = false }
}

@Composable
private fun JobRow(job: UploadJob, onRetry: () -> Unit, onRemove: () -> Unit) {
    val where = job.topicName?.let { "${job.chatTitle} / $it" } ?: job.chatTitle
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(job.name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
            val sub = when (job.status) {
                UploadStatus.Queued -> if (job.error != null) "Retrying, attempt ${job.attempts + 1}: ${job.error}" else "Waiting - $where"
                UploadStatus.Uploading -> where
                UploadStatus.Done -> "Uploaded to $where"
                UploadStatus.Failed -> "$where - ${job.error ?: "Failed"}"
            }
            Text(sub, maxLines = 2, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (job.status == UploadStatus.Uploading) LinearProgressIndicator(progress = { job.progress }, Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (job.status == UploadStatus.Failed) IconButton(onRetry) { AppIcon(Icons.Rounded.Refresh, "Retry") }
        if (job.status != UploadStatus.Uploading) IconButton(onRemove) { AppIcon(Icons.Rounded.Close, "Remove") }
    }
}

@Composable
private fun TargetDialog(
    chats: List<Pair<Long, String>>, topics: List<Triple<Long, Long, String>>, current: UploadTarget?,
    onPick: (UploadTarget) -> Unit, onDismiss: () -> Unit,
) {
    var chat by remember { mutableStateOf(chats.firstOrNull { it.first == current?.chatId } ?: chats.firstOrNull()) }
    val mine = topics.filter { it.first == chat?.first }
    var topic by remember { mutableStateOf(current?.topicId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload to") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                for (c in chats) {
                    Row(Modifier.fillMaxWidth().clickable { chat = c; topic = null }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(chat?.first == c.first, { chat = c; topic = null })
                        Text(c.second, maxLines = 1)
                    }
                }
                if (mine.isNotEmpty()) {
                    Text("Topic", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth().clickable { topic = null }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(topic == null, { topic = null })
                        Text("General")
                    }
                    for (t in mine) {
                        Row(Modifier.fillMaxWidth().clickable { topic = t.second }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(topic == t.second, { topic = t.second })
                            Text(t.third, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                chat?.let { c -> onPick(UploadTarget(c.first, c.second, topic, mine.firstOrNull { it.second == topic }?.third)) }
            }) { Text("Choose") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
