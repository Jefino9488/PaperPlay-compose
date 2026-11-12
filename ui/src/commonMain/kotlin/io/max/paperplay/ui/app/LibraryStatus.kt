package io.max.paperplay.ui.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.max.paperplay.core.library.EnrichState

/**
 * What the library holds and what is still being worked on: total songs, how many have info, no match, covers,
 * and how many lack something (opens the list). Shown at the top of the Library settings.
 */
@Composable
internal fun LibraryStatusRows(backend: CoreBackend, onOpenMissing: () -> Unit) {
    val all by backend.services.library.allSongs().collectAsState(emptyList())
    val taste by backend.taste.collectAsState()
    val enriching by backend.services.enricher.running.collectAsState()
    val coversQueued by backend.services.covers.queued.collectAsState()
    val moodRunning = backend.services.moodScanner.scanned
    val stats = remember(all, taste.lookups) {
        val songs = all.distinctBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
        Stats(
            total = all.size,
            matched = all.count { it.enrich_state == EnrichState.DONE },
            noMatch = all.count { it.enrich_state == EnrichState.NO_MATCH },
            waiting = all.count { it.enrich_state == EnrichState.PENDING },
            covers = all.count { it.cover_path != null },
            left = songs.count { missingOf(it, taste.lookups).isNotEmpty() },
            of = songs.size,
        )
    }
    val busy = listOfNotNull(
        if (enriching) "looking up song info" else null,
        if (coversQueued > 0) "loading $coversQueued covers" else null,
    )
    SettingsRow(
        "${stats.total} songs", icon = Icons.Rounded.LibraryMusic,
        subtitle = "Song info: ${stats.matched} found, ${stats.noMatch} with no match, ${stats.waiting} waiting",
    )
    SettingsRow("Covers: ${stats.covers} of ${stats.total}", icon = Icons.Rounded.Image, subtitle = "Covers load as songs appear on screen, then in the background")
    SettingsRow(
        "Running now", icon = Icons.Rounded.Sync,
        subtitle = if (busy.isEmpty()) "Nothing. $moodRunning songs scanned for lyrics and genre" else busy.joinToString(", ").replaceFirstChar { it.uppercase() },
    )
    NavRow(
        "Song info left", if (stats.left == 0) "Every song is complete" else "${stats.left} of ${stats.of} songs lack something. Tap to see which, and what.",
        Icons.Rounded.Rule, onOpenMissing,
    )
}

private class Stats(val total: Int, val matched: Int, val noMatch: Int, val waiting: Int, val covers: Int, val left: Int, val of: Int)
