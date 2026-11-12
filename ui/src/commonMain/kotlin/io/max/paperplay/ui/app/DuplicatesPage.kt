package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.app.TaskKind
import io.max.paperplay.core.app.TaskState
import io.max.paperplay.core.dupes.DefaultSongRules
import io.max.paperplay.core.dupes.DeleteStatus
import io.max.paperplay.core.dupes.DuplicateGroup
import io.max.paperplay.core.dupes.DuplicatePost
import io.max.paperplay.core.dupes.DuplicateSection
import io.max.paperplay.core.dupes.describeDuplicate
import io.max.paperplay.core.dupes.findDuplicates
import io.max.paperplay.core.dupes.sectionOf
import io.max.paperplay.core.dupes.survivorOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private typealias PostKey = Pair<Long, Long>

private val DuplicatePost.key: PostKey get() = chatId to messageId

private fun sizeText(bytes: Long) = "${(bytes * 10 / 1_048_576) / 10.0} MB"

private fun postsOf(g: DuplicateGroup) = listOf(g.keep) + g.others

/** The best copy of a group by quality (lossless, bit depth, rate, size); posts without a library row rank last. */
private fun bestQuality(g: DuplicateGroup): DuplicatePost = postsOf(g).reduce { a, b ->
    val sa = a.song
    val sb = b.song
    when {
        sa != null && sb != null -> if (DefaultSongRules.compareQuality(sb, sa) < 0) b else a
        sb == null -> a
        else -> b
    }
}

/** Settings > Library > Find duplicates. Tick the posts to delete; nothing goes before the confirmation. */
@Composable
internal fun DuplicatesPage(backend: CoreBackend) {
    var groups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var ticked by remember { mutableStateOf(setOf<PostKey>()) }
    var confirm by remember { mutableStateOf(false) }
    val topicNames = remember { backend.taste.value.topicNames }
    val queue = backend.services.deleteQueue
    val jobs by queue.jobs.collectAsState()
    val main = backend.mainChatId()
    val scope = backend.services.scope
    val active = jobs.any { it.status == DeleteStatus.Queued || it.status == DeleteStatus.Deleting }

    LaunchedEffect(reload) {
        val repo = backend.services.library.repo
        suspend fun find() = withContext(Dispatchers.Default) {
            findDuplicates(
                repo.db.songsQueries.allSongs().executeAsList(), repo.db.filingQueries.allPostCopies().executeAsList(),
                DefaultSongRules, onlyChat = main,
            )
        }
        var found = find()
        // A post Telegram no longer has would be listed as a repeat forever; drop what is gone and list again.
        if (found.isNotEmpty() && queue.dropGonePosts(found.flatMap { g -> postsOf(g).map { it.key } }) > 0) found = find()
        groups = found
        ticked = emptySet()
    }

    fun jobOf(p: DuplicatePost) = jobs.lastOrNull { it.isDuplicate && it.postChat == p.chatId && it.postMessage == p.messageId }
    fun busy(p: DuplicatePost) = jobOf(p)?.status.let { it == DeleteStatus.Queued || it == DeleteStatus.Deleting || it == DeleteStatus.Done }

    /** Sets the ticked posts of a group to [wanted], unless that would tick every post: one always survives. */
    fun tick(g: DuplicateGroup, wanted: Collection<DuplicatePost>) {
        val keys = wanted.filter { !busy(it) }.map { it.key }.toSet()
        val next = (ticked - postsOf(g).map { it.key }.toSet()) + keys
        if (survivorOf(g) { it.key in next } != null) ticked = next
    }

    val found = groups
    when {
        found == null -> Text("Looking for repeats", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        found.isEmpty() -> Text("No repeated songs in your main chat.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            Text(
                "${found.size} songs appear more than once in your main chat. Tick the posts to delete from Telegram. One copy of every song always stays.",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { found.forEach { g -> tick(g, g.others.filter { it.messageId in g.suggested }) } }, enabled = !active) { Text("Tick extras") }
                OutlinedButton(onClick = {
                    // Keep the highest quality copy (FLAC over MP3) of every group; tick the rest.
                    found.forEach { g ->
                        val best = bestQuality(g)
                        tick(g, postsOf(g).filter { it.key != best.key })
                    }
                }, enabled = !active) { Text("Keep best quality") }
                TextButton(onClick = { ticked = emptySet() }, enabled = ticked.isNotEmpty()) { Text("Clear") }
            }
            if (jobs.isNotEmpty()) {
                val done = jobs.count { it.status == DeleteStatus.Done }
                val failed = jobs.count { it.status == DeleteStatus.Failed }
                val skipped = jobs.count { it.status == DeleteStatus.Skipped }
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Deleting: $done of ${jobs.size} done" + (if (failed > 0) ", $failed failed" else "") + (if (skipped > 0) ", $skipped kept" else ""),
                        Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (active) TextButton(onClick = { queue.cancel() }) { Text("Stop") }
                    else TextButton(onClick = { reload++ }) { Text("Refresh") }
                }
                jobs.firstOrNull { it.status == DeleteStatus.Failed }?.error?.let {
                    Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
                }
            }
            val labels = mapOf(
                DuplicateSection.Extras to "Extra copies",
                DuplicateSection.OtherTopics to "Also in other topics",
                DuplicateSection.Maybe to "Might be the same song",
            )
            for (section in DuplicateSection.entries) {
                val inSection = found.filter { sectionOf(it) == section }
                if (inSection.isEmpty()) continue
                SectionLabel("${labels.getValue(section)} (${inSection.size})")
                for (g in inSection) {
                    SettingsRow(
                        "${g.title} - ${g.artist}", icon = Icons.Rounded.ContentCopy,
                        subtitle = describeDuplicate(g) { p -> p.topicId?.let { topicNames["topic:${p.chatId}:$it"] } },
                    )
                    for (p in postsOf(g)) {
                        val job = jobOf(p)
                        val state = when (job?.status) {
                            DeleteStatus.Queued -> "Waiting"
                            DeleteStatus.Deleting -> "Deleting"
                            DeleteStatus.Done -> "Deleted"
                            DeleteStatus.Failed -> job?.error ?: "Failed"
                            DeleteStatus.Skipped -> job?.error ?: "Kept"
                            null -> null
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(p.key in ticked, { on ->
                                if (on) tick(g, postsOf(g).filter { it.key in ticked || it.key == p.key })
                                else ticked = ticked - p.key
                            }, enabled = !busy(p) && !active)
                            Text(
                                "${p.format}, ${sizeText(p.size)}" + (state?.let { " - $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (job?.status == DeleteStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Button(
                onClick = { confirm = true }, enabled = ticked.isNotEmpty() && !active && main != null,
                modifier = Modifier.padding(20.dp),
            ) { Text("Delete ${ticked.size} posts") }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${ticked.size} posts?") },
            text = { Text("They are removed from Telegram for everyone and cannot be brought back. Playlists and favorites move to the copy that stays.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val pairs = groups.orEmpty().flatMap { g ->
                        val keep = survivorOf(g) { it.key in ticked } ?: return@flatMap emptyList()
                        postsOf(g).filter { it.key in ticked }.map { it to keep }
                    }
                    ticked = emptySet()
                    if (pairs.isEmpty()) return@TextButton
                    val tasks = backend.tasks
                    val id = tasks.start(TaskKind.Other, "Deleting duplicates", pairs.size, "Starting", onStop = { queue.cancel() })
                    queue.deleteDuplicates(pairs)
                    scope.launch {
                        val watch = launch {
                            queue.jobs.collect { l ->
                                tasks.update(id, done = l.count { it.status != DeleteStatus.Queued && it.status != DeleteStatus.Deleting })
                            }
                        }
                        queue.awaitIdle()
                        watch.cancel()
                        val l = queue.jobs.value
                        val failed = l.count { it.status == DeleteStatus.Failed }
                        tasks.finish(
                            id, if (failed > 0 && failed == l.size) TaskState.Failed else TaskState.Done,
                            "${l.count { it.status == DeleteStatus.Done }} deleted" + if (failed > 0) ", $failed failed" else "",
                        )
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
