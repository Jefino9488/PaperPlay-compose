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
    val durationMs: Long = 0,
    /** 0 to 1. */
    val volume: Float = 1f,
    val shuffle: Boolean = false,
    val repeat: RepeatUi = RepeatUi.Off,
    val queue: List<SongUi> = emptyList(),
    val queueIndex: Int = -1,
)

/** The part of playback that changes every tick. Keep readers of it small and late. */
data class PositionUi(val positionMs: Long = 0, val bufferedMs: Long = 0) {
    fun progress(durationMs: Long): Float = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    fun bufferedProgress(durationMs: Long): Float = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
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
    /** Menu text for removing the song from the list it is shown in, or null when the list has no such action. */
    val removeLabel: String? get() = null
    fun remove(song: SongUi) {}
    /** A tg:// link to the song's post, or null when it has none. */
    fun telegramLink(song: SongUi): String? = null
    /** A short message for things not built yet. */
    fun notice(text: String) {}
    /** Looks the song up online again; says what happened through [notice]. */
    fun findMetadata(song: SongUi) { notice("Finding metadata is not available here.") }
    /** Opens the tag editor. */
    fun editTags(song: SongUi) { notice("Editing tags is not available here.") }
    /** Opens the cover chooser. */
    fun changeCover(song: SongUi) { notice("Changing a cover is not available here.") }
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
/** [owner] is set for an album split off by artist; see core splitAlbum. */
data class GroupUi(val id: String, val name: String, val subtitle: String = "", val coverUris: List<String?> = emptyList(), val owner: String? = null)

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

/** A group of songs opened from Home or Search: a mix, a generated tile, a genre, or an album or artist on a shelf. */
data class CollectionUi(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val coverUri: String? = null,
    val songs: List<SongUi> = emptyList(),
    val coverUris: List<String?> = emptyList(),
)

enum class ShelfKindUi { Songs, Albums, Artists }

/** One row of the Home page. */
data class ShelfUi(val title: String, val kind: ShelfKindUi, val songs: List<SongUi> = emptyList(), val groups: List<CollectionUi> = emptyList())

/** The Home page plan for the day. [mixes] are the daily mood mixes; their [CollectionUi.id] is the mood id. */
data class HomeUi(val tiles: List<CollectionUi> = emptyList(), val shelves: List<ShelfUi> = emptyList(), val mixes: List<CollectionUi> = emptyList())
