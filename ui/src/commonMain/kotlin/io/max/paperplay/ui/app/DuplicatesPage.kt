package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.dupes.DefaultSongRules
import io.max.paperplay.core.dupes.DuplicateGroup
import io.max.paperplay.core.dupes.DuplicateSection
import io.max.paperplay.core.dupes.describeDuplicate
import io.max.paperplay.core.dupes.findDuplicates
import io.max.paperplay.core.dupes.sectionOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings > Library > Find duplicates. Lists repeats in the main chat; nothing is deleted from here. */
@Composable
internal fun DuplicatesPage(backend: CoreBackend) {
    var groups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    val topicNames = remember { backend.taste.value.topicNames }
    LaunchedEffect(Unit) {
        groups = withContext(Dispatchers.Default) {
            val repo = backend.services.library.repo
            val songs = repo.db.songsQueries.allSongs().executeAsList()
            val copies = repo.db.filingQueries.allPostCopies().executeAsList()
            findDuplicates(songs, copies, DefaultSongRules, onlyChat = backend.mainChatId())
        }
    }
    val found = groups
    when {
        found == null -> Text("Looking for repeats", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        found.isEmpty() -> Text("No repeated songs in your main chat.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            Text(
                "${found.size} songs appear more than once in your main chat. This list only shows them. Deleting is not built in this app yet.",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    Column {
                        SettingsRow(
                            "${g.title} - ${g.artist}", icon = Icons.Rounded.ContentCopy,
                            subtitle = describeDuplicate(g) { p -> p.topicId?.let { topicNames["topic:${p.chatId}:$it"] } },
                        )
                    }
                }
            }
        }
    }
}
