package io.max.paperplay.core.dupes

import io.max.paperplay.core.library.Song

enum class DeleteStatus { Queued, Deleting, Done, Failed, Skipped }

/**
 * One song in a bulk delete, kept so the Tasks page can show how far it got. A duplicate job
 * deletes one repeated post ([postChat]/[postMessage]) of [song], apart from the post at [keepChat]/[keepMessage].
 */
data class DeleteJob(
    val song: Song,
    val postChat: Long? = null,
    val postMessage: Long? = null,
    val keepChat: Long? = null,
    val keepMessage: Long? = null,
    val status: DeleteStatus = DeleteStatus.Queued,
    val error: String? = null,
) {
    val isDuplicate get() = postMessage != null

    /** Stable key: a duplicate's song also has other posts in the list. */
    val key get() = if (isDuplicate) "${postChat}_$postMessage" else song.unique_id
}
