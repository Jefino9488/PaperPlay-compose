package io.max.paperplay.core.recs

import io.max.paperplay.core.library.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Today's weather, as far as a mix cares. */
enum class WeatherKind { CLEAR, CLOUDY, RAIN, STORM, SNOW }

/** What a WMO weather code means for a mix. */
fun weatherKindOf(code: Int): WeatherKind? = when (code) {
    0, 1 -> WeatherKind.CLEAR
    2, 3, 45, 48 -> WeatherKind.CLOUDY
    in 51..67, in 80..82 -> WeatherKind.RAIN
    in 71..77, 85, 86 -> WeatherKind.SNOW
    in 95..99 -> WeatherKind.STORM
    else -> null
}

/** A mood a mix can be made for, placed on two axes: how positive (valence) and how intense (energy), -1 to 1. */
data class Mood(
    val id: String,
    val name: String,
    val valence: Double,
    val energy: Double,
    /** Words about this in a song count towards the mood. */
    val theme: String? = null,
    val wantsInstrumental: Boolean = false,
)

val moods = listOf(
    Mood("happy", "Happy", 0.7, 0.4, theme = "happy"),
    Mood("sad", "Sad", -0.7, -0.4, theme = "sad"),
    Mood("drive", "Drive", 0.2, 0.7, theme = "drive"),
    Mood("rain", "Rain", -0.2, -0.4, theme = "rain"),
    Mood("chill", "Chill", 0.3, -0.5),
    Mood("focus", "Focus", 0.0, -0.6, wantsInstrumental = true),
    Mood("party", "Party", 0.6, 0.9, theme = "party"),
    Mood("night", "Late night", -0.1, -0.2, theme = "night"),
    Mood("love", "Love", 0.5, -0.1, theme = "love"),
    Mood("workout", "Workout", 0.2, 0.95),
)

private val genreFeel = linkedMapOf(
    "metal" to (-0.2 to 0.9),
    "punk" to (0.0 to 0.85),
    "hard rock" to (0.0 to 0.8),
    "rock" to (0.1 to 0.6),
    "alternative" to (-0.1 to 0.3),
    "indie" to (0.0 to 0.1),
    "j-pop" to (0.3 to 0.4),
    "k-pop" to (0.4 to 0.6),
    "anime" to (0.2 to 0.5),
    "pop" to (0.4 to 0.4),
    "dance" to (0.6 to 0.8),
    "house" to (0.5 to 0.8),
    "techno" to (0.2 to 0.9),
    "edm" to (0.5 to 0.85),
    "electronic" to (0.2 to 0.6),
    "hip-hop" to (0.1 to 0.6),
    "hip hop" to (0.1 to 0.6),
    "rap" to (0.1 to 0.65),
    "r&b" to (0.2 to -0.1),
    "soul" to (0.2 to -0.1),
    "singer/songwriter" to (-0.1 to -0.4),
    "folk" to (0.0 to -0.4),
    "acoustic" to (0.1 to -0.5),
    "downtempo" to (0.0 to -0.7),
    "ambient" to (0.0 to -0.8),
    "lo-fi" to (0.1 to -0.7),
    "classical" to (0.0 to -0.6),
    "jazz" to (0.2 to -0.4),
    "blues" to (-0.3 to -0.2),
    "soundtrack" to (-0.1 to -0.4),
    "reggae" to (0.5 to -0.1),
    "latin" to (0.5 to 0.6),
    "country" to (0.2 to 0.0),
    "bollywood" to (0.3 to 0.2),
    "tamil" to (0.3 to 0.3),
    "regional indian" to (0.3 to 0.2),
)

private val positive = setOf(
    "happy", "happiness", "joy", "smile", "smiling", "laugh", "laughing", "sunshine", "sunny", "celebrate", "beautiful",
    "wonderful", "alive", "free", "freedom", "shine", "shining", "bright", "dream", "dreams", "hope", "best", "good",
    "great", "fun", "dance", "dancing", "lucky", "golden", "khushi", "muskaan", "hasna", "sapna", "sapne",
)
private val negative = setOf(
    "sad", "cry", "crying", "cried", "tears", "tear", "alone", "lonely", "broken", "hurt", "hurts", "pain", "goodbye",
    "die", "dead", "dying", "death", "lost", "miss", "missing", "gone", "empty", "hate", "fear", "afraid", "sorry",
    "bleed", "grave", "regret", "never", "cold", "numb", "darkness", "dard", "tanha", "tanhai", "aansu", "judaai",
    "bewafa", "gham", "alvida",
)
private val positiveScript = listOf("幸せ", "笑顔", "笑", "喜び", "嬉しい", "楽しい", "希望", "輝", "खुशी", "मुस्कान", "சந்தோஷ")
private val negativeScript = listOf(
    "涙", "悲し", "寂し", "淋し", "泣", "痛", "苦し", "孤独", "さよなら", "サヨナラ", "別れ", "死", "दर्द", "आँसू", "तन्हा", "கண்ணீர்", "சோக",
)

private val themeWords = linkedMapOf(
    "happy" to setOf("happy", "sunshine", "smile", "joy", "celebrate", "khushi"),
    "sad" to setOf("cry", "tears", "lonely", "alone", "goodbye", "broken", "dard", "tanha", "aansu"),
    "rain" to setOf("rain", "raining", "rainy", "rainfall", "storm", "thunder", "umbrella", "baarish", "barish", "barsaat", "sawan", "monsoon"),
    "night" to setOf("night", "nights", "midnight", "moon", "moonlight", "stars", "dark", "raat", "chaand", "chand"),
    "drive" to setOf("drive", "driving", "road", "highway", "car", "ride", "riding", "wheel", "fast", "speed", "run", "running", "gaadi"),
    "party" to setOf("party", "club", "dance", "dancing", "dancefloor", "tonight", "drink", "shots", "nachna", "naach"),
    "love" to setOf("love", "lover", "loving", "baby", "darling", "heart", "kiss", "pyaar", "pyar", "ishq", "mohabbat", "dil", "sanam", "jaan"),
)
private val themeScript = linkedMapOf(
    "rain" to listOf("雨", "बारिश", "மழை"),
    "night" to listOf("夜", "月", "星", "रात", "இரவு"),
    "love" to listOf("愛", "恋", "好き", "प्यार", "इश्क", "காதல்"),
    "sad" to listOf("涙", "悲し", "दर्द"),
    "happy" to listOf("幸せ", "笑顔", "खुशी"),
    "drive" to listOf("走", "道"),
    "party" to listOf("踊", "नाच"),
)

private val wordRe = Regex("[a-z']+")
private val lrcStamp = Regex("""\[\d+:\d+(?:\.\d+)?\]""")
private val latinOrSpace = Regex("""[a-z\s\p{P}\d]""")

/** What a song's metadata says about how it feels. */
class SongTraits(
    var valence: Double,
    var energy: Double,
    /** From 0, knowing nothing, to 1. */
    var confidence: Double,
    val themes: Map<String, Double>,
    val instrumental: Boolean,
    /** Its words actually said something positive or negative; genre and artist only suggest a feeling. */
    val hasSentiment: Boolean = false,
)

private fun countOf(text: String, needle: String): Int {
    var n = 0
    var at = 0
    while (true) {
        at = text.indexOf(needle, at)
        if (at == -1) return n
        n++
        at += needle.length
    }
}

/** Reads a song's traits from its lyrics, title, genre and length. Plain text work, needs no audio. */
fun analyzeSong(s: Song): SongTraits {
    var valence = 0.0
    var energy = 0.0
    var weight = 0.0
    fun vote(v: Double, e: Double, w: Double) {
        valence += v * w
        energy += e * w
        weight += w
    }

    val genre = s.genre?.lowercase()
    if (genre != null) {
        for ((k, v) in genreFeel) {
            if (genre.contains(k)) {
                vote(v.first, v.second, 1.0)
                break
            }
        }
    }
    if (s.instrumental) vote(0.0, -0.3, 0.5)

    var hasSentiment = false
    val themes = HashMap<String, Double>()
    val title = s.title.lowercase()
    val titleWords = wordRe.findAll(title).map { it.value }.toSet()
    // A word in the title says more than one anywhere in the lyrics.
    for ((k, v) in themeWords) if (titleWords.any { it in v }) themes[k] = 1.0
    for ((k, v) in themeScript) if (v.any { title.contains(it) }) themes[k] = 1.0

    val raw = s.lyrics
    if (!raw.isNullOrEmpty()) {
        val text = raw.replace(lrcStamp, " ").lowercase()
        val words = wordRe.findAll(text).map { it.value }.toList()
        var pos = 0
        var neg = 0
        val hits = HashMap<String, Int>()
        for (w in words) {
            if (w in positive) pos++
            if (w in negative) neg++
            for ((k, v) in themeWords) if (w in v) hits[k] = (hits[k] ?: 0) + 1
        }
        for (w in positiveScript) pos += countOf(text, w)
        for (w in negativeScript) neg += countOf(text, w)
        for ((k, v) in themeScript) for (w in v) hits[k] = (hits[k] ?: 0) + countOf(text, w)
        // Scripts without spaces count characters, so a word is about two of them.
        val nonLatin = text.replace(latinOrSpace, "").length
        val tokens = words.size + nonLatin / 2.0
        if (tokens > 0) {
            if (pos + neg > 0) {
                // Leans to neutral when there's little evidence, however one-sided.
                val lean = (pos - neg).toDouble() / (pos + neg + 3)
                hasSentiment = pos + neg >= 3
                vote(lean.coerceIn(-1.0, 1.0), 0.0, 1.5)
            }
            // Words per second: fast, wordy songs tend to feel more intense.
            if (s.duration_sec > 0) {
                val rate = tokens / s.duration_sec
                vote(0.0, ((rate - 1.2) / 1.2).coerceIn(-1.0, 1.0), 0.8)
            }
            for ((k, h) in hits) {
                val share = h / sqrt(tokens)
                themes[k] = min(1.0, (themes[k] ?: 0.0) + share)
            }
        }
    }

    return SongTraits(
        if (weight == 0.0) 0.0 else valence / weight,
        if (weight == 0.0) 0.0 else energy / weight,
        min(1.0, weight / 2.5),
        themes,
        s.instrumental,
        hasSentiment,
    )
}

/** Fills gaps from a song's album and artist: songs by one artist tend to feel alike. */
private fun spreadTraits(songs: List<Song>, traits: Map<String, SongTraits>) {
    for (keyOf in listOf<(Song) -> String>({ firstArtist(it.artist) }, { it.album })) {
        val sums = HashMap<String, DoubleArray>()
        for (s in songs) {
            val k = keyOf(s)
            if (k.isEmpty()) continue
            val t = traits.getValue(s.unique_id)
            val sum = sums.getOrPut(k) { DoubleArray(3) }
            sum[0] += t.valence * t.confidence
            sum[1] += t.energy * t.confidence
            sum[2] += t.confidence
        }
        for (s in songs) {
            val sum = sums[keyOf(s)] ?: continue
            if (sum[2] == 0.0) continue
            val t = traits.getValue(s.unique_id)
            val pull = 0.5
            val total = t.confidence + pull
            t.valence = (t.valence * t.confidence + sum[0] / sum[2] * pull) / total
            t.energy = (t.energy * t.confidence + sum[1] / sum[2] * pull) / total
            t.confidence = min(1.0, t.confidence + pull * min(1.0, sum[2]) * 0.5)
        }
    }
}

/** How well a song suits a mood, before personal taste. Above about 0.5 is a fit. */
fun moodFit(t: SongTraits, m: Mood): Double {
    val dv = t.valence - m.valence
    val de = t.energy - m.energy
    val spread = 0.5
    val near = exp(-(dv * dv + de * de) / (2 * spread * spread))
    var fit = near * (0.3 + 0.7 * t.confidence)
    val themed = m.theme?.let { t.themes[it] }
    // Moods that are about a feeling need words that show it.
    if (abs(m.valence) >= 0.5 && !t.hasSentiment && themed == null) fit *= 0.4
    // Singing about it is strong evidence, but only if the song also feels close.
    if (m.theme != null) fit += 1.5 * (themed ?: 0.0) * max(0.15, near)
    if (m.wantsInstrumental) fit += if (t.instrumental) 0.5 else -0.2
    return fit
}

/** Stable across devices and runs, so one library gives the same mixes on every device on the same day. */
fun stableHash(text: String): Long {
    var h = 0x811c9dc5L
    for (c in text) h = ((h xor c.code.toLong()) * 0x01000193L) and 0x7fffffffL
    return h
}

class MoodVerdict(
    val uniqueId: String,
    val artist: String,
    val mood: String,
    /** 1 for "fits this mood", -1 for "doesn't". */
    val vote: Int,
)

/** A list of songs someone made: a playlist, a Telegram topic, or their favorites. */
class TasteList(val name: String, val songIds: Set<String>)

/** A picture of someone's taste, from their lists alone. Songs in a list score highest, then same artists, then genres. */
class TasteModel private constructor(
    private val members: Set<String>,
    private val artists: Map<String, Int>,
    private val genres: Map<String, Int>,
) {
    val isEmpty: Boolean get() = members.isEmpty()
    operator fun contains(s: Song): Boolean = s.unique_id in members

    /** 1 with nothing to go on. Otherwise from about 0.4, a stranger, upwards. */
    fun scoreOf(s: Song): Double {
        if (members.isEmpty()) return 1.0
        var score = 0.4
        if (s.unique_id in members) score += 1
        score += 0.8 * min(1.0, (artists[firstArtist(s.artist)] ?: 0) / 3.0)
        val g = s.genre
        if (g != null && genres.isNotEmpty()) score += 0.4 * (genres[g] ?: 0) / genres.values.max()
        return score
    }

    companion object {
        fun build(lists: List<TasteList>, songs: List<Song>): TasteModel {
            val members = lists.flatMapTo(HashSet()) { it.songIds }
            val artists = HashMap<String, Int>()
            val genres = HashMap<String, Int>()
            for (s in songs) {
                if (s.unique_id !in members) continue
                val a = firstArtist(s.artist)
                artists[a] = (artists[a] ?: 0) + 1
                s.genre?.let { genres[it] = (genres[it] ?: 0) + 1 }
            }
            return TasteModel(members, artists, genres)
        }
    }
}

class DailyMix(val mood: Mood, val title: String, val songs: List<Song>)

private const val MIX_LENGTH = 30
private const val MIN_MIX = 10
private const val MAX_PER_ARTIST = 2
private const val MAX_PER_ARTIST_IN_OWN_LIST = 5
private const val FIT_FLOOR = 0.3

/** A song already in one mix is this much less likely in the next. */
private const val USED_PENALTY = 0.2

/** How much someone seems to like a song from how often they finish it rather than skip it. */
fun tasteOf(s: Song): Double = 0.5 + (s.plays + 1.0) / (s.plays + s.skips + 2.0)

/** Songs heard recently rest for a few days, so mixes stay fresh. */
fun freshnessOf(s: Song, now: Moment): Double {
    val played = s.last_played_at ?: return 1.0
    val days = ((now.epochMs - played) / 3_600_000L) / 24.0
    return if (days <= 0) 0.15 else min(1.0, 0.15 + 0.85 * days / 7)
}

/** Moods suited to right now first, from the weather and the time of day. */
fun contextOrder(now: Moment, weather: WeatherKind?): List<String> {
    val hour = now.hour
    val byWeather = when (weather) {
        WeatherKind.RAIN, WeatherKind.STORM -> listOf("rain", "chill", "sad")
        WeatherKind.CLEAR -> if (hour in 7..18) listOf("happy", "drive") else emptyList()
        WeatherKind.CLOUDY -> listOf("chill")
        WeatherKind.SNOW -> listOf("chill", "love")
        null -> emptyList()
    }
    val byHour = when {
        hour >= 22 || hour < 5 -> listOf("night", "chill", "sad")
        hour < 10 -> listOf("happy", "focus", "drive")
        hour < 17 -> listOf("focus", "happy", "workout")
        else -> listOf("drive", "party", "love")
    }
    return (byWeather + byHour + moods.map { it.id }).distinct()
}

private fun titleFor(m: Mood, weather: WeatherKind?): String =
    if (m.id == "rain" && (weather == WeatherKind.RAIN || weather == WeatherKind.STORM)) "Rainy day mix" else "${m.name} mix"

/** Orders songs so each is close in feel to the last. Starts from the calmest, so a mix builds as it goes. */
private fun sequence(songs: List<Song>, traits: Map<String, SongTraits>): List<Song> {
    val left = songs.sortedBy { traits.getValue(it.unique_id).energy }.toMutableList()
    val ordered = mutableListOf(left.removeAt(0))
    while (left.isNotEmpty()) {
        val last = traits.getValue(ordered.last().unique_id)
        var best = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (i in left.indices) {
            val t = traits.getValue(left[i].unique_id)
            val d = (t.valence - last.valence).pow(2) + (t.energy - last.energy).pow(2)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        ordered.add(left.removeAt(best))
    }
    return ordered
}

object DailyMixes {
    /**
     * Today's mixes, most fitting first. For each mood: score every song by fit, taste and freshness, draw a weighted
     * random sample, keep artists varied (at most 2 per artist, 5 from the mood's own list), then order it so each song
     * flows into the next. Runs on [Dispatchers.Default] and stops when the caller is cancelled.
     */
    suspend fun build(
        songs: List<Song>,
        now: Moment,
        votes: List<MoodVerdict> = emptyList(),
        weather: WeatherKind? = null,
        lists: List<TasteList> = emptyList(),
        options: TasteSettings = TasteSettings(),
    ): List<DailyMix> = withContext(Dispatchers.Default) {
        val traits = HashMap<String, SongTraits>()
        for ((i, s) in songs.withIndex()) {
            if (i % 256 == 0) currentCoroutineContext().ensureActive()
            traits[s.unique_id] = analyzeSong(s)
        }
        val taste = TasteModel.build(lists, songs)
        // Someone's own "Sad Songs" says more about what they find sad than any lyric.
        val namedFor = HashMap<String, MutableSet<String>>()
        for (l in lists) for (m in moodsForListName(l.name)) namedFor.getOrPut(m) { HashSet() }.addAll(l.songIds)
        spreadTraits(songs, traits)

        val songVotes = HashMap<String, Int>()
        val artistVotes = HashMap<String, Int>()
        for (v in votes) {
            songVotes["${v.mood}:${v.uniqueId}"] = v.vote
            val key = "${v.mood}:${firstArtist(v.artist)}"
            artistVotes[key] = (artistVotes[key] ?: 0) + v.vote
        }

        val day = "${now.year}-${now.month}-${now.day}"
        val mixes = mutableListOf<DailyMix>()
        val used = HashSet<String>()
        for (mood in moods) {
            currentCoroutineContext().ensureActive()
            val random = Random(stableHash("$day:${mood.id}"))
            val drawn = ArrayList<Pair<Double, Song>>()
            val named = namedFor[mood.id].orEmpty()
            for ((i, s) in songs.withIndex()) {
                if (i % 256 == 0) currentCoroutineContext().ensureActive()
                if (options.onlyFromLists && !taste.isEmpty && s !in taste) continue
                val vote = songVotes["${mood.id}:${s.unique_id}"] ?: 0
                if (vote < 0) continue
                var fit = moodFit(traits.getValue(s.unique_id), mood)
                if (vote > 0) fit += 1
                if (s.unique_id in named) fit += 1.5
                // A verdict on one song says a little about the artist's others.
                fit += 0.1 * (artistVotes["${mood.id}:${firstArtist(s.artist)}"] ?: 0).coerceIn(-3, 3)
                if (fit < FIT_FLOOR) continue
                // A high power makes a clearly better fit much likelier, not just slightly.
                val weight = fit.pow(4) *
                    taste.scoreOf(s) *
                    (if (options.learnFromPlays) tasteOf(s) else 1.0) *
                    (if (options.restRecent) freshnessOf(s, now) else 1.0) *
                    (if (s.unique_id in used) USED_PENALTY else 1.0)
                // Efraimidis-Spirakis weighted sampling: the largest keys are the sample.
                drawn.add(random.nextDouble().pow(1 / weight) to s)
            }
            drawn.sortByDescending { it.first }

            val perArtist = HashMap<String, Int>()
            val titles = HashSet<String>()
            val picked = ArrayList<Song>()
            for ((_, s) in drawn) {
                val artist = firstArtist(s.artist)
                // Someone's own list for this mood can lean on its artists more: that's their choice.
                val cap = if (s.unique_id in named) MAX_PER_ARTIST_IN_OWN_LIST else MAX_PER_ARTIST
                if ((perArtist[artist] ?: 0) >= cap) continue
                // Two files of one song shouldn't both be in a mix.
                if (!titles.add("${comparableText(searchTitle(s.title))}|$artist")) continue
                perArtist[artist] = (perArtist[artist] ?: 0) + 1
                picked.add(s)
                if (picked.size == MIX_LENGTH) break
            }
            // Too few real fits makes a bad mix, so it's better to offer none.
            if (picked.size < MIN_MIX) continue
            used.addAll(picked.map { it.unique_id })
            mixes.add(DailyMix(mood, titleFor(mood, weather), sequence(picked, traits)))
        }
        val rank = contextOrder(now, weather).withIndex().associate { it.value to it.index }
        mixes.sortedBy { rank[it.mood.id] ?: 99 }
    }
}
