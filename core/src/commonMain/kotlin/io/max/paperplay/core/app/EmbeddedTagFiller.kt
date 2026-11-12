package io.max.paperplay.core.app

import io.max.paperplay.core.backup.Fs
import io.max.paperplay.core.metadata.TagSource
import io.max.paperplay.core.metadata.readEmbeddedTags

/** Reads tags and cover from a downloaded audio file and fills what the song row lacks. Never overwrites user edits. */
class EmbeddedTagFiller(
    private val dataDir: String,
    private val repo: LibraryRepository,
    private val clock: () -> Long,
) {
    private val tried = HashSet<String>()

    fun apply(uniqueId: String, path: String) {
        runCatching {
            if (!synchronized(tried) { tried.add(uniqueId) }) return
            val song = repo.songByUniqueId(uniqueId) ?: return
            val len = fileLength(path)
            if (len <= 0) return
            val tags = readEmbeddedTags(TagSource(len) { off, n -> readRange(path, off, n) }) ?: return
            val free = !song.user_edited
            // A song named only from its file name has no artist; embedded tags are better than that name.
            val title = tags.title?.takeIf { free && song.artist.isBlank() && it != song.title }
            val artist = (tags.artist ?: tags.albumArtist)?.takeIf { free && song.artist.isBlank() }
            val album = tags.album?.takeIf { free && song.album.isBlank() }
            val cover = tags.cover?.takeIf { song.cover_path.isNullOrEmpty() || !Fs.exists(song.cover_path!!) }
                ?.let { saveCover(uniqueId, it) }
            if (title == null && artist == null && album == null && cover == null && (tags.year == null || song.year != null) &&
                (tags.genre == null || !song.genre.isNullOrBlank())
            ) return
            repo.fillEmbeddedTags(uniqueId, title, artist, album, tags.year?.toLong(), tags.genre, cover)
        }
    }

    private fun saveCover(uid: String, bytes: ByteArray): String? {
        val ext = when {
            bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
            bytes.size > 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "webp"
            else -> "jpg"
        }
        val dir = "${dataDir.trimEnd('/')}/covers"
        val path = "$dir/${uid.replace(Regex("[^A-Za-z0-9._-]"), "_")}_${clock()}.$ext"
        return runCatching { Fs.mkdirs(dir); Fs.write(path, bytes); path }.getOrNull()
    }
}
