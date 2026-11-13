package io.max.paperplay.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.state.SongActions
import io.max.paperplay.ui.state.SongUi

/** One song in a list. Tap plays (or toggles while selecting), long-press starts selecting. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: SongUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    selected: Boolean = false,
    coverUriOverride: String? = null,
    actions: SongActions? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val containerColor = when {
        selected -> scheme.primaryContainer
        current -> scheme.secondaryContainer.copy(alpha = 0.7f)
        else -> scheme.surfaceContainerLow
    }
    val effectiveCoverUri = coverUriOverride ?: song.coverUri
    val coverKey = song.album.ifBlank { song.title.ifBlank { song.id } }

    BoxWithConstraints(modifier.fillMaxWidth()) {
    val wide = maxWidth >= 420.dp
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = io.max.paperplay.ui.app.appShape(16.dp),
        color = containerColor,
        tonalElevation = if (current) 3.dp else 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    AppIcon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = scheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            } else {
                CoverImage(effectiveCoverUri, coverKey, size = 48.dp, radius = 10.dp, coverId = song.id)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (current) scheme.primary else scheme.onSurface,
                )
                val detail = remember(song.sizeBytes, song.durationSec, wide) {
                    val kbps = if (song.durationSec > 0) song.sizeBytes * 8 / song.durationSec / 1000 else 0L
                    listOfNotNull(
                        if (wide && kbps > 0) "$kbps kbps" else null,
                        if (wide && song.sizeBytes > 0) "${song.sizeBytes / 1_000_000} MB" else null,
                    ).joinToString(" · ")
                }
                val format = if (isHiRes(song)) "Hi-Res" else song.qualityLabel?.substringBefore(' ')?.takeIf { it.isNotBlank() }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = song.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (format != null) FormatChip(format)
                    if (song.pinned || song.offline) {
                        AppIcon(
                            imageVector = if (song.pinned) Icons.Rounded.PushPin else Icons.Rounded.DownloadDone,
                            contentDescription = if (song.pinned) "Pinned" else "Offline",
                            tint = scheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (detail.isNotEmpty()) Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (song.favorite) {
                AppIcon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = "Favorite",
                    tint = scheme.primary,
                    modifier = Modifier.size(16.dp).padding(end = 4.dp),
                )
            }
            if (actions != null) SongMenu(song, actions) else Box(Modifier.size(8.dp))
        }
    }
    }
}

/** Lossless with more than 16 bits or a rate above 48 kHz, read from labels like "FLAC 24/96". */
/** The one chip a song earns for its format: "Hi-Res", or the first word of its quality label such as FLAC or MP3. */
internal fun formatChipOf(song: SongUi): String? =
    if (isHiRes(song)) "Hi-Res" else song.qualityLabel?.substringBefore(' ')?.takeIf { it.isNotBlank() }

private fun isHiRes(song: SongUi): Boolean {
    if (!song.lossless) return false
    val m = Regex("""(\d+)/([\d.]+)""").find(song.qualityLabel.orEmpty()) ?: return false
    return m.groupValues[1].toInt() > 16 || (m.groupValues[2].toDoubleOrNull() ?: 0.0) > 48
}

private val DOLBY = mapOf(
    "AC3" to "Dolby Digital", "EAC3" to "Dolby Digital Plus", "EC3" to "Dolby Digital Plus",
    "AC4" to "Dolby AC-4", "TRUEHD" to "Dolby TrueHD", "MLP" to "Dolby TrueHD",
)

private enum class BadgeIcon { Dolby, Wave, None }

/** The icon and words for a song's format: Dolby, lossless and hi-res get a glyph, lossy formats just their label. */
private fun badgeOf(song: SongUi): Pair<BadgeIcon, String>? {
    val label = song.qualityLabel?.takeIf { it.isNotBlank() } ?: return if (song.lossless) BadgeIcon.Wave to "Lossless" else null
    val codec = label.substringBefore(' ').uppercase()
    DOLBY[codec]?.let { return BadgeIcon.Dolby to it }
    if (isHiRes(song)) return BadgeIcon.Wave to "$codec Hi-Res Lossless"
    if (song.lossless) return BadgeIcon.Wave to "$codec Lossless"
    return BadgeIcon.None to label
}

/** Format badge in the style of Apple's "Dolby Atmos" line: a small glyph then the name, no box. */
@Composable
fun AudioBadge(song: SongUi, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)) {
    val (icon, text) = badgeOf(song) ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (icon) {
            BadgeIcon.Dolby -> androidx.compose.foundation.Canvas(Modifier.size(14.dp, 10.dp)) {
                val w = size.width; val h = size.height; val gap = w * 0.06f; val half = (w - gap) / 2
                fun d(left: Boolean) = androidx.compose.ui.graphics.Path().apply {
                    val x0 = if (left) 0f else half + gap
                    val flat = if (left) x0 + half else x0
                    val curve = if (left) x0 else x0 + half
                    moveTo(flat, 0f)
                    cubicTo(curve, 0f, curve, h, flat, h)
                    close()
                }
                drawPath(d(true), color); drawPath(d(false), color)
            }
            BadgeIcon.Wave -> androidx.compose.foundation.Canvas(Modifier.size(14.dp, 10.dp)) {
                val bars = floatArrayOf(0.4f, 0.8f, 1f, 0.65f, 0.35f)
                val bw = size.width / (bars.size * 2 - 1)
                bars.forEachIndexed { i, f ->
                    val bh = size.height * f
                    drawRoundRect(color, androidx.compose.ui.geometry.Offset(i * 2 * bw, (size.height - bh) / 2), androidx.compose.ui.geometry.Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(bw / 2))
                }
            }
            BadgeIcon.None -> {}
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun FormatChip(text: String) {
    Box(
        modifier = Modifier
            .height(16.dp)
            .clip(io.max.paperplay.ui.app.appShape(5.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun SongMenu(song: SongUi, actions: SongActions, icon: ImageVector = Icons.Rounded.MoreVert, onOutputInfo: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    if (info) androidx.compose.material3.AlertDialog(
        onDismissRequest = { info = false },
        title = { Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            val kbps = if (song.durationSec > 0) song.sizeBytes * 8 / song.durationSec / 1000 else 0L
            val rows = listOf(
                "Artist" to song.artist, "Album" to song.album, "Genre" to song.genre.orEmpty(),
                "Year" to (song.year?.toString() ?: ""),
                "Length" to "${song.durationSec / 60}:${(song.durationSec % 60).toString().padStart(2, '0')}",
                "Format" to song.qualityLabel.orEmpty(),
                "Bitrate" to if (kbps > 0) "$kbps kbps" else "",
                "Size" to if (song.sizeBytes > 0) "${song.sizeBytes / 100_000 / 10.0} MB" else "",
                "Plays" to song.plays.toString(),
            ).filter { it.second.isNotBlank() }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rows.forEach { (k, v) ->
                    Row { Text(k, Modifier.weight(0.35f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, Modifier.weight(0.65f)) }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton({ info = false }) { Text("Close") } },
    )
    if (confirmDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete from Telegram?") },
        text = { Text("\"${song.title}\" is removed from your main chat and from this library. Repeats of the same file in that chat go too. This cannot be undone.") },
        confirmButton = { androidx.compose.material3.TextButton({ confirmDelete = false; actions.deleteFromTelegram(song) }) { Text("Delete") } },
        dismissButton = { androidx.compose.material3.TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
    Box {
        IconButton({ open = true }) { AppIcon(icon, "Song options") }
        if (open) DropdownMenu(
            expanded = true,
            onDismissRequest = { open = false },
            modifier = io.max.paperplay.ui.app.overlayRim(RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            containerColor = io.max.paperplay.ui.app.overlayColor(),
        ) {
            @Composable
            fun item(text: String, run: () -> Unit) = DropdownMenuItem(
                text = { Text(text) },
                onClick = { open = false; run() },
            )
            item("Play next") { actions.playNext(song) }
            item(if (song.favorite) "Remove from favorites" else "Add to favorites") { actions.setFavorite(song, !song.favorite) }
            item(if (song.pinned) "Unpin" else "Pin for offline") { actions.setPinned(song, !song.pinned) }
            item("Add to playlist") { actions.addToPlaylist(song) }
            actions.telegramLink(song)?.let { link ->
                item("Open in Telegram") { runCatching { uri.openUri(link) }.onFailure { actions.notice("Could not open Telegram.") } }
            }
            onOutputInfo?.let { show -> item("Output info") { show() } }
            item("Song info") { info = true }
            item("Find metadata") { actions.findMetadata(song) }
            item("Edit tags") { actions.editTags(song) }
            item("Change cover") { actions.changeCover(song) }
            actions.removeLabel?.let { label -> item(label) { actions.remove(song) } }
            item("Delete from Telegram") { confirmDelete = true }
        }
    }
}

private val previewSong = SongUi(
    id = "1",
    title = "Bohemian Rhapsody",
    artist = "Queen",
    album = "A Night at the Opera",
    durationSec = 354,
    sizeBytes = 12_500_000,
    qualityLabel = "320 kbps",
    favorite = true,
    pinned = true,
    offline = true,
)

@Preview
@Composable
private fun SongRowNormalPreview() {
    SongRow(song = previewSong, onClick = {}, onLongClick = {})
}

@Preview
@Composable
private fun SongRowCurrentPreview() {
    SongRow(song = previewSong, onClick = {}, onLongClick = {}, current = true)
}

@Preview
@Composable
private fun SongRowSelectedPreview() {
    SongRow(song = previewSong, onClick = {}, onLongClick = {}, selected = true)
}

@Preview
@Composable
private fun SongMenuPreview() {
    val dummyActions = object : SongActions {
        override fun play(songs: List<SongUi>, index: Int) {}
        override fun playNext(song: SongUi) {}
        override fun setFavorite(song: SongUi, favorite: Boolean) {}
        override fun setPinned(song: SongUi, pinned: Boolean) {}
        override fun addToPlaylist(song: SongUi) {}
    }
    SongMenu(song = previewSong, actions = dummyActions)
}


@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Badge", showBackground = true)
@Composable
private fun BadgePreview() = io.max.paperplay.ui.PreviewFrame { FormatChip("FLAC") }

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Row narrow hi-res", widthDp = 340, showBackground = true)
@Composable
private fun SongRowNarrowPreview() = io.max.paperplay.ui.PreviewFrame {
    SongRow(
        song = previewSong.copy(title = "Arz Kiya Hai (Slowed and Reverb Extended)", artist = "Afusic & AliSoomroMusic", qualityLabel = "FLAC 24/96", lossless = true, sizeBytes = 104_000_000, durationSec = 300),
        onClick = {}, onLongClick = {},
    )
}
