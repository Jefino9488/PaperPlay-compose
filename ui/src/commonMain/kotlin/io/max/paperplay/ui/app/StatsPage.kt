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
import io.max.paperplay.core.library.ListenTotal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun time(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

/** Settings > Listening > Listening statistics: time listened, top artists and songs. */
@Composable
internal fun StatsPage(backend: CoreBackend) {
    val spans = listOf("7 days" to 7, "30 days" to 30, "All time" to 0)
    var span by remember { mutableStateOf(1) }
    var data by remember { mutableStateOf<Pair<List<ListenTotal>, Map<String, Long>>?>(null) }
    LaunchedEffect(span) {
        data = null
        data = withContext(Dispatchers.Default) { backend.listenStats(spans[span].second) }
    }
    ChoiceRow(spans.map { it.first }, spans[span].first, { it }, { span = spans.indexOfFirst { s -> s.first == it } })
    val (songs, days) = data ?: run {
        Text("Reading", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    SettingsRow(time(days.values.sum()), icon = Icons.Rounded.BarChart, subtitle = "${days.size} days with music")
    if (songs.isEmpty()) {
        Text("Nothing yet. Play some songs.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val artists = songs.filter { !it.artist.isNullOrBlank() }.groupBy { it.artist!! }
        .mapValues { (_, v) -> v.sumOf { it.ms } }.entries.sortedByDescending { it.value }
    if (artists.isNotEmpty()) SectionLabel("Top artists")
    for (a in artists.take(5)) SettingsRow(a.key, trailing = { Text(time(a.value)) })
    SectionLabel("Top songs")
    for (s in songs.take(20)) {
        val plays = if (s.plays > 0) " · ${s.plays} plays" else ""
        Column { SettingsRow(s.title ?: "Unknown song", subtitle = "${s.artist.orEmpty()}$plays", trailing = { Text(time(s.ms)) }) }
    }
}
