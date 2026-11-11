package io.max.paperplay.ui.app

import io.max.paperplay.core.library.Song
import io.max.paperplay.core.recs.DailyMix
import io.max.paperplay.core.recs.moods

/** Today's daily mixes saved as song ids in a preference, so Home shows them at once on launch. */
internal object DailyMixCache {
    const val PREF = "daily_mix_cache"
    private const val FIELD = '\t'
    private const val ID = '\u001f'

    fun encode(key: String, mixes: List<DailyMix>): String =
        (listOf(key) + mixes.map { "${it.mood.id}$FIELD${it.title}$FIELD${it.songs.joinToString(ID.toString()) { s -> s.unique_id }}" })
            .joinToString("\n")

    /** The saved key and mixes, with songs that are gone from the library dropped. Null when nothing usable is saved. */
    fun decode(raw: String?, songs: Map<String, Song>): Pair<String, List<DailyMix>>? {
        if (raw.isNullOrBlank()) return null
        val lines = raw.split('\n')
        val mixes = lines.drop(1).mapNotNull { line ->
            val p = line.split(FIELD)
            if (p.size < 3) return@mapNotNull null
            val mood = moods.firstOrNull { it.id == p[0] } ?: return@mapNotNull null
            val list = p[2].split(ID).mapNotNull { songs[it] }
            if (list.isEmpty()) null else DailyMix(mood, p[1], list)
        }
        return lines[0] to mixes
    }
}
