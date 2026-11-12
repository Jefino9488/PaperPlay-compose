package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.core.filing.MoveJob
import io.max.paperplay.core.filing.MoveStatus
import io.max.paperplay.core.filing.TopicChoice
import io.max.paperplay.core.filing.TopicConfidence
import io.max.paperplay.core.filing.TopicSuggestion
import io.max.paperplay.core.filing.suggestTopics
import io.max.paperplay.core.filing.topicChoices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private typealias Key = Pair<Long, Long>

private class SortData(val rows: List<TopicSuggestion>, val topics: Map<Long, List<TopicChoice>>)

/** Asks which topic. [onPick] gets null if the owner backed out. */
@Composable
internal fun TopicPickerDialog(title: String, topics: List<TopicChoice>, onPick: (TopicChoice?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onPick(null) },
        title = { Text(title) },
        text = {
            Column {
                for (t in topics) TextButton({ onPick(t) }, Modifier.fillMaxWidth()) { Text(t.name, Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ onPick(null) }) { Text("Cancel") } },
    )
}

/** Settings > Library > Sort General. Nothing moves until songs are ticked and the move is confirmed. */
@Composable
internal fun SortPage(backend: CoreBackend) {
    val mover = backend.services.topicMover
    val jobs by mover.jobs.collectAsState()
    val paused by mover.paused.collectAsState()
    val problem by mover.problem.collectAsState()
    var data by remember { mutableStateOf<SortData?>(null) }
    val chosen = remember { mutableStateMapOf<Key, TopicChoice>() }
    val ticked = remember { mutableStateMapOf<Key, Boolean>() }
    var changing by remember { mutableStateOf<TopicSuggestion?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val finished = jobs.count { it.status == MoveStatus.Done }

    LaunchedEffect(finished) {
        val loaded = withContext(Dispatchers.Default) {
            val repo = backend.services.library.repo
            val songs = repo.db.songsQueries.allSongs().executeAsList()
            val copies = repo.db.filingQueries.allPostCopies().executeAsList()
            val topicRows = repo.db.miscQueries.topics().executeAsList()
            val names = backend.taste.value.topicNames
            val rows = ArrayList<TopicSuggestion>()
            val topics = HashMap<Long, List<TopicChoice>>()
            for (chat in songs.map { it.chat_id }.toSet()) {
                val choices = topicChoices(topicRows, chat, names)
                val found = suggestTopics(songs.filter { it.chat_id == chat }, choices, copies.filter { it.chat_id == chat })
                if (found.isEmpty()) continue
                topics[chat] = choices
                rows += found
            }
            fun rank(r: TopicSuggestion) = if (r.blocked) 3 else r.confidence?.ordinal ?: 2
            rows.sortWith(compareBy({ rank(it) }, { it.song.title }))
            SortData(rows, topics)
        }
        val keys = loaded.rows.map { it.song.chat_id to it.song.message_id }.toSet()
        ticked.keys.retainAll(keys)
        chosen.keys.retainAll(keys)
        for (r in loaded.rows) r.topic?.let { chosen.getOrPut(r.song.chat_id to r.song.message_id) { it } }
        data = loaded
    }

    val d = data
    if (d == null) {
        Text("Looking at General", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val waiting = jobs.filter { it.status == MoveStatus.Queued || it.status == MoveStatus.Moving }.map { it.key }.toSet()
    val high = d.rows.count { it.confidence == TopicConfidence.High && !it.blocked }
    fun keyOf(r: TopicSuggestion) = r.song.chat_id to r.song.message_id

    if (jobs.isNotEmpty()) {
        val settled = jobs.count { it.status != MoveStatus.Queued && it.status != MoveStatus.Moving }
        SectionLabel("Moved $finished, $settled of ${jobs.size} finished")
        if (mover.active) LinearProgressIndicator(
            progress = { if (jobs.isEmpty()) 0f else settled.toFloat() / jobs.size },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        if (paused && problem == null) Text("Paused", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        problem?.let {
            Text("$it. Stopped. Resume carries on with the next song.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
        }
        for (j in jobs.filter { it.status == MoveStatus.Skipped || (it.status == MoveStatus.Done && it.note != null) }) {
            SettingsRow(j.song.title, subtitle = j.note ?: "Skipped")
        }
        if (mover.active || paused) Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ mover.setPaused(!paused) }) { Text(if (paused) "Resume" else "Pause after this song") }
            OutlinedButton({ mover.cancel() }) { Text("Cancel the rest") }
        }
    }
    if (d.rows.isEmpty()) {
        Text("No songs to sort in General.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(
        "${d.rows.size} songs in General. Nothing is ticked for you: tick what is right, change a topic if it is wrong, then confirm once. " +
            "Moving needs the bot to be an admin that can post and delete messages.",
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (high > 0) OutlinedButton({
            for (r in d.rows) if (r.confidence == TopicConfidence.High && !r.blocked) ticked[keyOf(r)] = true
        }) { Text("Tick $high high") }
        val count = ticked.count { it.value }
        Button({ confirming = true }, enabled = count > 0 && !mover.active) {
            AppIcon(Icons.Rounded.DriveFileMove, null)
            Text("Move $count", Modifier.padding(start = 8.dp))
        }
    }
    for (r in d.rows) {
        val key = keyOf(r)
        val topic = chosen[key]
        val busy = key in waiting
        val level = when (r.confidence) { TopicConfidence.High -> "High"; TopicConfidence.Medium -> "Medium"; null -> null }
        val detail = listOfNotNull(level, r.reason, if (topic != null && topic.id != r.topic?.id) "You chose ${topic.name}" else null).joinToString(" - ")
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(ticked[key] == true, { ticked[key] = it }, enabled = !r.blocked && topic != null && !busy)
            Column(Modifier.weight(1f)) {
                Text("${r.song.title} - ${r.song.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (busy) "Queued to move" else detail, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!r.blocked && !busy) TextButton({ changing = r }) { Text(topic?.name ?: "Choose") }
        }
    }

    changing?.let { r ->
        val choices = d.topics[r.song.chat_id].orEmpty().filter { !it.isGeneral }
        TopicPickerDialog("Move \"${r.song.title}\" to", choices) { picked ->
            changing = null
            if (picked != null) { chosen[keyOf(r)] = picked; ticked[keyOf(r)] = true }
        }
    }
    if (confirming) {
        val moves = d.rows.filter { ticked[keyOf(it)] == true && chosen[keyOf(it)] != null }
            .map { MoveJob(it.song, chosen.getValue(keyOf(it)).id, chosen.getValue(keyOf(it)).name) }
        val breakdown = moves.groupingBy { it.topicName }.eachCount().entries.joinToString("\n") { "${it.key}: ${it.value}" }
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Move ${moves.size} song${if (moves.size == 1) "" else "s"}?") },
            text = {
                Text(
                    "$breakdown\n\nTelegram cannot move posts, so each song is reposted in its new topic (no re-upload) and the General post is deleted for good. " +
                        "If a repost fails, the original stays and the queue stops.\n\nPlaylists, favorites, tags, covers and lyrics stay with the song. " +
                        "It is slow on purpose so the bot is not limited. Keep Paper Play open.",
                )
            },
            confirmButton = {
                TextButton({ confirming = false; ticked.clear(); mover.start(moves) }, enabled = moves.isNotEmpty()) { Text("Move") }
            },
            dismissButton = { TextButton({ confirming = false }) { Text("Cancel") } },
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun TopicPickerDialogPhonePreview() {
    val sampleTopics = listOf(TopicChoice(1, "Rock"), TopicChoice(2, "Pop"))
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) {
            TopicPickerDialog(title = "Move song to", topics = sampleTopics, onPick = {})
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun TopicPickerDialogDesktopPreview() {
    val sampleTopics = listOf(TopicChoice(1, "Rock"), TopicChoice(2, "Pop"))
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.padding(32.dp)) {
            TopicPickerDialog(title = "Move song to", topics = sampleTopics, onPick = {})
        }
    }
}
