package io.max.paperplay.core.filing

import io.max.paperplay.core.library.PostCopy
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.recs.analyzeSong
import io.max.paperplay.core.recs.comparableText
import io.max.paperplay.core.recs.firstArtist
import io.max.paperplay.core.recs.moodFit
import io.max.paperplay.core.recs.moods
import io.max.paperplay.core.recs.moodsForListName
import kotlin.math.abs

enum class LyricScript(val label: String, val topicWords: List<String>) {
    Japanese("Japanese", listOf("japan", "j-pop", "jpop")),
    Devanagari("Devanagari", listOf("bollywood", "hindi")),
    Tamil("Tamil", listOf("tamil", "kollywood")),
    Telugu("Telugu", listOf("tollywood", "telugu")),
    Malayalam("Malayalam", listOf("mollywood", "malayalam")),
}

private fun scriptOfRune(c: Int): LyricScript? = when (c) {
    in 0x3040..0x30FF -> LyricScript.Japanese
    in 0x0900..0x097F -> LyricScript.Devanagari
    in 0x0B80..0x0BFF -> LyricScript.Tamil
    in 0x0C00..0x0C7F -> LyricScript.Telugu
    in 0x0D00..0x0D7F -> LyricScript.Malayalam
    else -> null
}

private fun isLetter(c: Int) = c in 0x41..0x5A || c in 0x61..0x7A || c in 0x4E00..0x9FFF || scriptOfRune(c) != null

private fun dominantScript(text: String, minChars: Int, minShare: Double): LyricScript? {
    val counts = HashMap<LyricScript, Int>()
    var letters = 0
    // All the scripts counted are in the basic plane, so UTF-16 units are enough.
    for (ch in text) {
        val c = ch.code
        if (!isLetter(c)) continue
        letters++
        scriptOfRune(c)?.let { counts[it] = (counts[it] ?: 0) + 1 }
    }
    val top = counts.entries.maxByOrNull { it.value } ?: return null
    return if (top.value >= minChars && top.value.toDouble() / letters >= minShare) top.key else null
}

/** The script a song is written in, from its lyrics or else its title and artist. */
fun scriptOf(s: Song): LyricScript? {
    s.lyrics?.let { l -> dominantScript(l, 20, 0.2)?.let { return it } }
    return dominantScript("${s.title} ${s.artist}", 2, 0.4)
}

enum class TopicConfidence { High, Medium }

class TopicSuggestion(
    val song: Song,
    /** Null when the evidence is too weak to suggest anything. */
    val topic: TopicChoice?,
    val confidence: TopicConfidence?,
    val reason: String,
    /** Moving it would file the same song twice, so it can't be moved from here. */
    val blocked: Boolean = false,
)

private val genreTopicWords = mapOf(
    "phonk" to listOf("phonk"), "bollywood" to listOf("bollywood"), "hindi" to listOf("bollywood"),
    "tollywood" to listOf("tollywood"), "telugu" to listOf("tollywood"),
    "mollywood" to listOf("mollywood"), "malayalam" to listOf("mollywood"),
    "kollywood" to listOf("kollywood", "tamil"), "tamil" to listOf("kollywood", "tamil"),
    "j-pop" to listOf("japan"), "j-rock" to listOf("japan"), "japanese" to listOf("japan"),
)

/** Topics named for a sound or a place, not a feeling: an intense song isn't phonk. */
private val notMoodTopics = listOf("phonk", "lofi", "lo-fi")

private const val HIGH = 3.0
private const val MEDIUM = 1.5
private const val TOO_CLOSE = 0.7
private const val MOST_OF = 0.6
private const val ENOUGH_TO_LEARN = 3
private const val MOOD_FIT_NEEDED = 0.7

private fun mainTopic(counts: Map<Long, Int>): Map.Entry<Long, Int>? {
    val top = counts.entries.maxByOrNull { it.value } ?: return null
    val total = counts.values.sum()
    return if (top.value.toDouble() / total >= MOST_OF) top else null
}

private fun artistKey(s: Song): String =
    if (s.artist == "Unknown artist") "" else comparableText(firstArtist(s.artist))

private class Evidence(
    val artists: Map<String, Map<Long, Int>>,
    val scripts: Map<LyricScript, Map<Long, Int>>,
    val genres: Map<String, Map<Long, Int>>,
)

/**
 * Suggests a topic for each song in General, from what's already filed elsewhere. Songs in other topics
 * are only read for evidence. A weak or evenly split case gets no suggestion. [incoming] are songs from
 * elsewhere, judged by what is filed in [songs].
 */
fun suggestTopics(
    songs: List<Song>,
    topics: List<TopicChoice>,
    copies: List<PostCopy> = emptyList(),
    incoming: List<Song>? = null,
): List<TopicSuggestion> {
    val targets = topics.filter { !it.isGeneral }
    // Without other topics this isn't a sorted forum: a plain group's posts all count as General.
    if (targets.isEmpty()) return emptyList()
    val general = incoming ?: songs.filter { it.topic_id == null || it.topic_id == GENERAL_TOPIC_ID }
    val names = targets.associateBy { it.id }
    val artists = HashMap<String, HashMap<Long, Int>>()
    val scripts = HashMap<LyricScript, HashMap<Long, Int>>()
    val genres = HashMap<String, HashMap<Long, Int>>()
    fun <K> bump(m: HashMap<K, HashMap<Long, Int>>, k: K, t: Long) {
        val inner = m.getOrPut(k) { HashMap() }
        inner[t] = (inner[t] ?: 0) + 1
    }
    for (s in songs) {
        val t = s.topic_id ?: continue
        if (t !in names) continue
        artistKey(s).takeIf { it.isNotEmpty() }?.let { bump(artists, it, t) }
        scriptOf(s)?.let { bump(scripts, it, t) }
        s.genre?.lowercase()?.takeIf { it.isNotEmpty() }?.let { bump(genres, it, t) }
    }
    val copyTopics = HashMap<String, MutableSet<Long>>()
    for (c in copies) {
        val t = c.topic_id
        if (t != null && t != GENERAL_TOPIC_ID) copyTopics.getOrPut(c.unique_id) { LinkedHashSet() }.add(t)
    }
    val ev = Evidence(artists, scripts, genres)
    return general.map { s ->
        val other = copyTopics[s.unique_id]
        if (other != null) {
            TopicSuggestion(s, null, null, "Also posted in ${names[other.first()]?.name ?: "another topic"}. See Find duplicates.", blocked = true)
        } else suggest(s, targets, names, ev)
    }
}

private fun suggest(s: Song, targets: List<TopicChoice>, names: Map<Long, TopicChoice>, ev: Evidence): TopicSuggestion {
    val scores = HashMap<Long, Double>()
    val reasons = HashMap<Long, MutableList<String>>()
    fun add(topic: Long, score: Double, reason: String) {
        scores[topic] = (scores[topic] ?: 0.0) + score
        reasons.getOrPut(topic) { mutableListOf() }.add(reason)
    }
    fun named(words: List<String>): TopicChoice? =
        targets.firstOrNull { t -> val n = t.name.lowercase(); words.any { n.contains(it) } }

    mainTopic(ev.artists[artistKey(s)] ?: emptyMap())?.let { a ->
        val n = a.value
        add(
            a.key, if (n >= 3) 3.0 else if (n == 2) 2.5 else 1.5,
            "$n song${if (n == 1) "" else "s"} by ${firstArtist(s.artist)} ${if (n == 1) "is" else "are"} in ${names.getValue(a.key).name}",
        )
    }
    scriptOf(s)?.let { script ->
        val learned = mainTopic(ev.scripts[script] ?: emptyMap())
        val topic = if (learned != null && learned.value >= ENOUGH_TO_LEARN) learned.key else named(script.topicWords)?.id
        if (topic != null) add(topic, 2.0, "${script.label} lyrics or title")
    }
    val genre = s.genre?.lowercase()
    if (!genre.isNullOrEmpty()) {
        var byName: TopicChoice? = null
        for ((k, words) in genreTopicWords) if (genre.contains(k)) byName = named(words) ?: byName
        val learned = mainTopic(ev.genres[genre] ?: emptyMap())
        if (byName != null) add(byName.id, 1.5, "Genre is ${s.genre}")
        else if (learned != null && learned.value >= ENOUGH_TO_LEARN) add(learned.key, 1.0, "Genre is ${s.genre}")
    }
    val traits = analyzeSong(s)
    if (traits.confidence >= 0.5) {
        for (t in targets) {
            val lower = t.name.lowercase()
            if (notMoodTopics.any { lower.contains(it) }) continue
            for (id in moodsForListName(t.name)) {
                val mood = moods.first { it.id == id }
                if (abs(mood.valence) >= 0.5 && !traits.hasSentiment) continue
                if (moodFit(traits, mood) >= MOOD_FIT_NEEDED) {
                    add(t.id, MEDIUM, "Lyrics and genre feel like ${mood.name.lowercase()}")
                    break
                }
            }
        }
    }
    if (scores.isEmpty()) return TopicSuggestion(s, null, null, "Not enough to go on")
    val ranked = scores.entries.sortedByDescending { it.value }
    val best = ranked.first()
    if (best.value < MEDIUM) return TopicSuggestion(s, null, null, "Not enough to go on")
    if (ranked.size > 1 && ranked[1].value >= best.value * TOO_CLOSE) {
        return TopicSuggestion(s, null, null, "Could be ${names.getValue(best.key).name} or ${names.getValue(ranked[1].key).name}")
    }
    return TopicSuggestion(
        s, names.getValue(best.key), if (best.value >= HIGH) TopicConfidence.High else TopicConfidence.Medium,
        reasons.getValue(best.key).joinToString("; "),
    )
}
