package io.max.paperplay.ui.state

/** Plain state the screens render from. The app layer maps its services onto these. */
data class SongUi(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val genre: String? = null,
    val coverUri: String? = null,
    val durationSec: Int = 0,
    val sizeBytes: Long = 0,
    val year: Int? = null,
    val plays: Int = 0,
    val addedAt: Long = 0,
    /** Short quality text such as "FLAC 24/96"; null hides it. */
    val qualityLabel: String? = null,
    /** Higher is better; used by the Quality sort. */
    val qualityRank: Int = 0,
    val lossless: Boolean = false,
    val favorite: Boolean = false,
    val pinned: Boolean = false,
    val offline: Boolean = false,
)

enum class RepeatUi { Off, All, One }

data class PlaybackUiState(
    val current: SongUi? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    /** 0 to 1. */
    val volume: Float = 1f,
    val shuffle: Boolean = false,
    val repeat: RepeatUi = RepeatUi.Off,
    val queue: List<SongUi> = emptyList(),
    val queueIndex: Int = -1,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedProgress: Float get() = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

interface PlaybackActions {
    fun togglePlay()
    fun next()
    fun previous()
    /** [fraction] is 0 to 1 of the song length. */
    fun seekTo(fraction: Float)
    fun setVolume(volume: Float)
    fun toggleShuffle()
    fun cycleRepeat()
    fun playQueueIndex(index: Int)
    fun removeFromQueue(index: Int)
}

/** Actions on a single song, from its row menu. */
interface SongActions {
    fun play(songs: List<SongUi>, index: Int)
    fun playNext(song: SongUi)
    fun setFavorite(song: SongUi, favorite: Boolean)
    fun setPinned(song: SongUi, pinned: Boolean)
    fun addToPlaylist(song: SongUi)
}

/** Actions on all songs picked in multi-select. */
interface SelectionActions {
    fun playNext(ids: Set<String>)
    fun setFavorite(ids: Set<String>, favorite: Boolean)
    fun pin(ids: Set<String>)
    fun addToPlaylist(ids: Set<String>)
    fun deleteFromTelegram(ids: Set<String>)
}

/** Loading and failure are part of the state, so a screen has one thing to render. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Error(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

data class LibraryUi(
    val songs: Load<List<SongUi>> = Load.Loading,
    val albums: List<GroupUi> = emptyList(),
    val artists: List<GroupUi> = emptyList(),
    val playlists: List<GroupUi> = emptyList(),
)

/** An album, artist or playlist entry. [coverUris] feed the mosaic. */
data class GroupUi(val id: String, val name: String, val subtitle: String = "", val coverUris: List<String?> = emptyList())

enum class TaskStatus { Running, Queued, Done, Failed }

data class TaskUi(
    val id: String,
    val title: String,
    val detail: String = "",
    /** 0 to 1, or null when the size is not known. */
    val progress: Float? = null,
    val status: TaskStatus = TaskStatus.Running,
)

data class TasksUi(val tasks: List<TaskUi> = emptyList())

interface TaskActions {
    fun cancel(id: String)
    fun retry(id: String)
    fun clearFinished()
}
