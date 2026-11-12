package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.backup.Fs
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.localTimeLabel
import io.max.paperplay.ui.nowMoment

/** Settings > Telegram > Activity log: everything the app logged this run, with search, filters, copy and save. */
@Composable
internal fun LogPage(backend: CoreBackend) {
    val lines by backend.log.collectAsState()
    val env = io.max.paperplay.ui.LocalAppEnv.current
    var crash by remember { mutableStateOf(env?.lastCrash?.invoke()) }
    var note by remember { mutableStateOf<String?>(null) }
    val dataDir = backend.services.platform.dataDir
    LogBody(
        lines, crash, note,
        onSaveLog = { text -> saveText(dataDir, "paperplay-log", text) { note = it } },
        onSaveCrash = { crash?.let { text -> saveText(dataDir, "paperplay-crash", text) { note = it } } },
        onClearCrash = { env?.clearCrash?.invoke(); crash = null },
        onNote = { note = it },
        onClear = backend::clearLog,
    )
}

internal fun saveText(dataDir: String, name: String, text: String, done: (String) -> Unit) {
    val pick = BackupFiles.pickDestination
    if (text.isEmpty()) { done("Nothing to save"); return }
    if (pick == null) { done("Saving a file is not supported here"); return }
    val m = nowMoment()
    fun two(n: Int) = n.toString().padStart(2, '0')
    val file = "$name-${m.year}-${two(m.month)}-${two(m.day)}.txt"
    val tmp = dataDir.trimEnd('/', '\\') + "/log-tmp.txt"
    Fs.write(tmp, text.encodeToByteArray())
    pick(file) { dest ->
        val ok = dest?.save(tmp)
        Fs.deleteRecursively(tmp)
        done(if (dest == null) "Not saved" else if (ok == true) "Saved $file" else "Could not save the file")
    }
}

private fun lineText(l: LogLine) = "${localTimeLabel(l.at)} · ${l.source}${if (l.error) " · error" else ""}  ${l.message}"

@Composable
internal fun LogBody(
    lines: List<LogLine>,
    crash: String?,
    note: String?,
    onSaveLog: (String) -> Unit,
    onSaveCrash: () -> Unit,
    onClearCrash: () -> Unit,
    onNote: (String) -> Unit,
    onClear: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var problemsOnly by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val q = query.trim().lowercase()
    val sources = remember(lines) { lines.map { it.source }.distinct().sorted() }
    val shown = remember(lines, q, problemsOnly, source) {
        lines.asReversed().filter { (!problemsOnly || it.error) && (source == null || it.source == source) && (q.isEmpty() || it.message.lowercase().contains(q)) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            IconButton({
                // The newest lines, oldest first; the file-size checks would bury everything else.
                val recent = shown.filterNot { it.message.startsWith("getFileDownloadedPrefixSize") }.take(1500).reversed()
                clipboard.setText(AnnotatedString(recent.joinToString("\n", transform = ::lineText)))
                onNote("Copied the latest ${recent.size} lines")
            }, enabled = shown.isNotEmpty()) { AppIcon(Icons.Rounded.ContentCopy, "Copy what is shown") }
            IconButton({ onSaveLog(lines.joinToString("\n", transform = ::lineText)) }, enabled = lines.isNotEmpty()) { AppIcon(Icons.Rounded.Download, "Save the whole log as a text file") }
            IconButton(onSaveCrash, enabled = crash != null) { AppIcon(Icons.Rounded.BugReport, "Save the crash log as a text file") }
            IconButton(onClear, enabled = lines.isNotEmpty()) { AppIcon(Icons.Rounded.DeleteOutline, "Clear the log") }
        }
        PillField(query, { query = it }, "Search the log", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(problemsOnly, { problemsOnly = !problemsOnly }, { Text("Problems only") }) }
            items(sources, key = { it }) { s -> FilterChip(source == s, { source = if (source == s) null else s }, { Text(s) }) }
        }
        crash?.let { text ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("The app crashed last time", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                Text(text.take(600), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClearCrash) { Text("Clear crash") }
            }
        }
        Text(
            note ?: "${shown.size} of ${lines.size} lines",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
        )
        if (shown.isEmpty()) {
            Text(
                if (lines.isEmpty()) "Nothing yet. Connecting, scanning and problems show here." else "Nothing to show",
                Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { l ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            "${localTimeLabel(l.at)} · ${l.source}${if (l.error) " · error" else ""}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(l.message, style = MaterialTheme.typography.bodySmall, color = if (l.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

private val previewLines = listOf(
    LogLine(1_700_000_000_000, "telegram", "Ready"),
    LogLine(1_700_000_010_000, "scanner", "Somg Playlist: 3269 song posts read, 516 new, 40 ids held nothing"),
    LogLine(1_700_000_020_000, "covers", "Deezer did not answer", error = true),
    LogLine(1_700_000_030_000, "sound", "Output: This phone"),
    LogLine(1_700_000_040_000, "sync", "Looking for a saved device that moved"),
)

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Log", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun LogBodyPreview() = io.max.paperplay.ui.PreviewFrame {
    LogBody(previewLines, null, null, {}, {}, {}, {}, {})
}

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Log empty", widthDp = 390, heightDp = 500, showBackground = true)
@Composable
private fun LogBodyEmptyPreview() = io.max.paperplay.ui.PreviewFrame {
    LogBody(emptyList(), null, null, {}, {}, {}, {}, {})
}

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Log crash", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun LogBodyCrashPreview() = io.max.paperplay.ui.PreviewFrame {
    LogBody(previewLines, "main: java.lang.IllegalStateException: example", "Copied the latest 5 lines", {}, {}, {}, {}, {})
}
