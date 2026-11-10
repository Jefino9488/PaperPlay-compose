package io.max.paperplay.core.dupes

import io.max.paperplay.core.library.PostCopy
import io.max.paperplay.core.library.Song
import kotlin.math.abs

const val GENERAL_TOPIC_ID = 1048576L

/** What the metadata and audio-info slices decide for the duplicate finder. */
interface SongRules {
    /** Codec/format name of the song, as shown (FLAC, MP3). */
    fun format(s: Song): String
    fun isLossless(format: String): Boolean

    /** Better first: higher tier, then more detail, then bitrate, then size. */
    fun compareQuality(a: Song, b: Song): Int

    /** comparableText(searchTitle(cleanTitle(title, artist))). */
    fun comparableTitle(s: Song): String

    /** comparableText(firstArtist(artist)). */
    fun comparableArtist(s: Song): String
}

enum class DuplicateKind {
    /** The identical file, posted more than once. */
    SameFile,

    /** Matching title, artist and length, in different files. Can occasionally be wrong. */
    LikelySameSong,
}

/** One post of a song in a chat. [song] is the library's row for it; null for a repeat the library doesn't list. */
data class DuplicatePost(
    val chatId: Long,
    val messageId: Long,
    val fileName: String,
    val size: Long,
    val topicId: Long?,
    val lossless: Boolean,
    val format: String,
    /** When it was posted, in seconds. */
    val date: Long,
    val song: Song? = null,
)

/** [keep] is the copy suggested to keep; [suggested] holds the message ids of [others] safe to suggest deleting. */
data class DuplicateGroup(
    val kind: DuplicateKind,
    val title: String,
    val artist: String,
    val keep: DuplicatePost,
    val others: List<DuplicatePost>,
    val suggested: Set<Long>,
)

enum class DuplicateSection { Extras, OtherTopics, Maybe }

fun sectionOf(g: DuplicateGroup): DuplicateSection = when {
    g.suggested.isNotEmpty() -> DuplicateSection.Extras
    g.kind == DuplicateKind.SameFile -> DuplicateSection.OtherTopics
    else -> DuplicateSection.Maybe
}

private fun List<String>.joinAnd() =
    if (size < 2) joinToString("") else "${dropLast(1).joinToString(", ")} and ${last()}"

/** Says in plain words why these posts count as repeats. */
fun describeDuplicate(g: DuplicateGroup, topicOf: (DuplicatePost) -> String?): String {
    val posts = listOf(g.keep) + g.others
    if (g.kind == DuplicateKind.SameFile) {
        val places = posts.mapNotNull(topicOf).distinct()
        if (places.size > 1) return "The exact same file was posted in ${places.joinAnd()}."
        val where = if (places.isEmpty()) "" else " in ${places.single()}"
        return "The exact same file was posted ${posts.size} times$where."
    }
    val lossy = g.others.filter { !it.lossless }.map { it.format }.distinct()
    if (g.keep.lossless && lossy.isNotEmpty()) {
        return "The same song in different quality: ${g.keep.format} is lossless, ${lossy.joinAnd()} isn't."
    }
    return "Same title, artist and length, but different files. They could be different versions."
}

/** The post that stays when the ticked ones go. Null if none would, which is never allowed. */
fun survivorOf(g: DuplicateGroup, isTicked: (DuplicatePost) -> Boolean): DuplicatePost? =
    (listOf(g.keep) + g.others).firstOrNull { !isTicked(it) }

/** Clearly worse than [keep]: lossy against lossless, or under two-thirds of the size in the same kind of format. */
private fun clearlyWorse(post: DuplicatePost, keep: DuplicatePost): Boolean {
    if (keep.lossless && !post.lossless) return true
    return keep.lossless == post.lossless && post.size * 3 < keep.size * 2
}

/** The same format at nearly the same size: a re-upload of the same track, so the older one can go. */
private fun reupload(post: DuplicatePost, keep: DuplicatePost) =
    post.format == keep.format && abs(post.size - keep.size) <= keep.size * 0.02

/** A copy in General of a song already filed in a real topic: what's left when a move couldn't delete it. */
private fun leftoverInGeneral(keep: DuplicatePost, other: DuplicatePost) =
    other.topicId == GENERAL_TOPIC_ID && keep.topicId != null && keep.topicId != GENERAL_TOPIC_ID

private const val LENGTH_TOLERANCE = 3

private fun postOf(s: Song, rules: SongRules): DuplicatePost {
    val format = rules.format(s)
    return DuplicatePost(s.chat_id, s.message_id, s.file_name, s.size, s.topic_id, rules.isLossless(format), format, s.date, s)
}

/**
 * Finds repeats, from the library's [songs] and the [copies] it found beyond them.
 * A repeat in another topic isn't suggested for deletion: topics act as playlists.
 * With [onlyChat], only posts in that chat count.
 */
fun findDuplicates(songs: List<Song>, copies: List<PostCopy>, rules: SongRules, onlyChat: Long? = null): List<DuplicateGroup> {
    val groups = mutableListOf<DuplicateGroup>()
    val byUnique = songs.associateBy { it.unique_id }
    val copiesOf = LinkedHashMap<String, MutableList<PostCopy>>()
    for (c in copies) if (onlyChat == null || c.chat_id == onlyChat) copiesOf.getOrPut(c.unique_id) { mutableListOf() }.add(c)
    for ((uid, list) in copiesOf) {
        val song = byUnique[uid] ?: continue
        val listedHere = onlyChat == null || song.chat_id == onlyChat
        // Listed under a post elsewhere, so one of these has to be the one that stays.
        if (!listedHere && list.size < 2) continue
        val format = rules.format(song)
        fun of(c: PostCopy) = DuplicatePost(
            c.chat_id, c.message_id, c.file_name, c.size, c.topic_id, rules.isLossless(format), format, c.date,
        )
        val keep = if (listedHere) postOf(song, rules) else of(list.first())
        val others = (if (listedHere) list else list.drop(1)).map(::of)
        groups += DuplicateGroup(
            DuplicateKind.SameFile, song.title, song.artist, keep, others,
            others.filter { it.topicId == keep.topicId || leftoverInGeneral(keep, it) }.map { it.messageId }.toSet(),
        )
    }

    val byName = LinkedHashMap<String, MutableList<Song>>()
    for (s in songs) {
        if (s.duration_sec <= 0 || (onlyChat != null && s.chat_id != onlyChat)) continue
        val title = rules.comparableTitle(s)
        if (title.isEmpty()) continue
        byName.getOrPut("$title|${rules.comparableArtist(s)}") { mutableListOf() }.add(s)
    }
    for (same in byName.values) {
        if (same.size < 2) continue
        same.sortBy { it.duration_sec }
        fun flush(run: List<Song>) {
            if (run.size < 2) return
            // Best quality, then the larger file, then the newest post.
            val posts = run.map { postOf(it, rules) }.sortedWith { a, b ->
                val q = rules.compareQuality(a.song!!, b.song!!)
                if (q != 0) q else b.date.compareTo(a.date)
            }
            val keep = posts.first()
            val others = posts.drop(1)
            groups += DuplicateGroup(
                DuplicateKind.LikelySameSong, keep.song!!.title, keep.song.artist, keep, others,
                others.filter { it.topicId == keep.topicId && (clearlyWorse(it, keep) || reupload(it, keep)) }
                    .map { it.messageId }.toSet(),
            )
        }
        // Runs of similar length, so an album version and a long mix stay apart.
        var run = mutableListOf(same.first())
        for (s in same.drop(1)) {
            if (s.duration_sec - run.last().duration_sec <= LENGTH_TOLERANCE) {
                run.add(s)
            } else {
                flush(run)
                run = mutableListOf(s)
            }
        }
        flush(run)
    }
    return groups
}
