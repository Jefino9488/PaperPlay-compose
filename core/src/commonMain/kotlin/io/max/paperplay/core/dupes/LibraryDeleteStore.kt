package io.max.paperplay.core.dupes

import io.max.paperplay.core.filing.GONE_KEY
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.PostCopy
import io.max.paperplay.core.library.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/** The delete queue's store, over the library database. The deletion record is a key -> time map in a preference. */
class LibraryDeleteStore(private val repo: LibraryRepo, @Suppress("unused") private val rules: SongRules) : DeleteStore {
    override suspend fun postCopiesOf(uniqueId: String): List<PostCopy> =
        repo.db.miscQueries.postCopiesOf(uniqueId).executeAsList()

    override suspend fun removePost(chatId: Long, messageId: Long) = repo.removePost(chatId, messageId)
    override suspend fun moveReferences(from: Song, to: Song) = repo.moveReferences(from, to)
    override suspend fun markSongGone(uniqueId: String) = mark(uniqueId)
    override suspend fun markSongKeyGone(key: String) = mark(key)
    override fun announceChange() {}

    private fun mark(key: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        val old = runCatching { Json.parseToJsonElement(repo.pref(GONE_KEY) ?: "{}").jsonObject }.getOrDefault(JsonObject(emptyMap()))
        repo.setPref(GONE_KEY, JsonObject(old + (key to JsonPrimitive(now))).toString())
    }
}
