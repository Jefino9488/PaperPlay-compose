package io.max.paperplay.core.integrations

// Title and artist text rules shared with the metadata matcher in the Flutter app.

internal fun comparableText(s: String): String = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")

private val artistSplit = Regex("""\s*(?:,|&|\bfeat\.?\s|\bft\.?\s)\s*""", RegexOption.IGNORE_CASE)

internal fun splitArtists(artist: String): List<String> = artist.split(artistSplit)

internal fun firstArtist(artist: String): String = splitArtists(artist).first().trim()

private val trackPrefix = Regex("""^\s*\d+\s*[|/]\s*\d+\s*-\s*""")
private val formatTag = Regex("""\s*[\[(](?:flac|mp3|m4a|aac|wav|alac|hi-?res|lossless|\d+\s*kbps)[\])]""", RegexOption.IGNORE_CASE)

/** Removes uploader junk: `1|5 - ` prefixes, `(FLAC)` tags, and the artist repeated at either end. */
internal fun cleanTitle(title: String, artist: String): String {
    var t = title.replaceFirst(trackPrefix, "").replace(formatTag, "").trim()
    val name = firstArtist(artist).lowercase()
    if (name.isNotEmpty()) {
        val last = t.lastIndexOf(" - ")
        if (last > 0 && t.substring(last + 3).lowercase().startsWith(name)) t = t.substring(0, last).trim()
        val first = t.indexOf(" - ")
        if (first > 0 && t.substring(0, first).lowercase().startsWith(name)) t = t.substring(first + 3).trim()
    }
    return t.ifEmpty { title }
}

private val searchNoise = Regex(
    """\s*[\[(](?:feat\.?|ft\.?|with |from |explicit|clean|official|lyric|audio|video|remaster)[^\])]*[\])]""",
    RegexOption.IGNORE_CASE,
)

/** Title for searching: drops `(feat. X)`, `[Explicit]`, `(From "Y")` and similar. Keeps `(Remix)`. */
internal fun searchTitle(title: String): String = title.replace(searchNoise, "").trim().ifEmpty { title }

private val extensionFormats = mapOf(
    "mp3" to "MP3", "mpga" to "MP3", "mp2" to "MP2", "flac" to "FLAC", "m4a" to "AAC", "m4b" to "AAC",
    "mp4" to "AAC", "aac" to "AAC", "alac" to "ALAC", "ogg" to "OGG", "oga" to "OGG", "opus" to "OPUS",
    "wav" to "WAV", "aif" to "AIFF", "aiff" to "AIFF", "aifc" to "AIFF", "ape" to "APE", "wv" to "WV",
    "tak" to "TAK", "tta" to "TTA", "dsf" to "DSD", "dff" to "DSD", "wma" to "WMA", "ac3" to "AC3",
    "eac3" to "EAC3", "ec3" to "EAC3", "mka" to "MKA",
)

private val mimeFormats = mapOf(
    "audio/mpeg" to "MP3", "audio/mp3" to "MP3", "audio/flac" to "FLAC", "audio/x-flac" to "FLAC",
    "audio/mp4" to "AAC", "audio/x-m4a" to "AAC", "audio/aac" to "AAC", "audio/ogg" to "OGG",
    "audio/opus" to "OPUS", "audio/wav" to "WAV", "audio/x-wav" to "WAV", "audio/aiff" to "AIFF",
    "audio/x-aiff" to "AIFF", "audio/x-ms-wma" to "WMA", "audio/ac3" to "AC3",
)

private val losslessFormats = setOf("FLAC", "WAV", "ALAC", "APE", "AIFF", "WV", "TAK", "TTA", "DSD")

/** Short format name from the file extension, else the MIME type, else the extension as it is. */
internal fun audioFormat(mime: String, fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    val extension = if (dot > 0 && fileName.length - dot <= 6) fileName.substring(dot + 1) else ""
    return extensionFormats[extension.lowercase()] ?: mimeFormats[mime.lowercase()]
        ?: if (extension.isEmpty()) "Audio" else extension.uppercase()
}

internal fun isLossless(mime: String, fileName: String): Boolean = audioFormat(mime, fileName) in losslessFormats
