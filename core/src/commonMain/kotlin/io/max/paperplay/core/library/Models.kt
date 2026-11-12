package io.max.paperplay.core.library

import io.max.paperplay.core.db.Forum_topics
import io.max.paperplay.core.db.Library_chats
import io.max.paperplay.core.db.Missing_tracks
import io.max.paperplay.core.db.Mood_votes
import io.max.paperplay.core.db.Playlist_songs
import io.max.paperplay.core.db.Playlists
import io.max.paperplay.core.db.Post_copies
import io.max.paperplay.core.db.Songs

// Row classes are the SQLDelight ones; the Dart names are kept for the library code.
typealias Song = Songs
typealias LibraryChat = Library_chats
typealias Playlist = Playlists
typealias PlaylistSong = Playlist_songs
typealias MissingTrack = Missing_tracks
typealias MoodVote = Mood_votes
typealias ForumTopic = Forum_topics
typealias PostCopy = Post_copies

object EnrichState {
    const val PENDING = 0L
    const val DONE = 1L
    const val NO_MATCH = 2L
}

data class ListenTotal(val key: String, val ms: Long, val plays: Long, val title: String?, val artist: String?)

/** Edit stamp: the clock, but always after [previous] so a local edit beats the version it replaces. */
fun laterStamp(now: Long, previous: Long): Long = if (now > previous) now else previous + 1

/** Id for a playlist from before sync; derived so two devices restored from one backup agree. */
fun legacyPlaylistUuid(id: Long, name: String): String = "legacy-$id-$name"

/** Random id for a new playlist or device. */
fun newUuid(): String = kotlin.random.Random.Default.nextBytes(16).joinToString("") {
    (it.toInt() and 0xff).toString(16).padStart(2, '0')
}

/** Settings tied to this device, which a backup must not move to another. */
val devicePrefs = listOf(
    "device_id", "listenbrainz_on", "last_session", "sort_songs", "sort_reverse", "sort_playlist", "norm_mode",
    "norm_preamp", "eq_on", "eq_gains", "listenbrainz_queue", "rate_filing", "rate_uploads", "rate_reading",
    "rate_lookups", "rate_covers", "rate_accountCopy", "lan_addresses", "lan_discovery", "theme_mode",
    "theme_amoled", "theme_accent", "now_playing_panel", "audio_device", "weather_city", "weather_lat",
    "weather_lon", "spotify_client_id", "upload_slower", "upload_parallel", "uploaded_files", "upload_skip_known",
    "topics_read", "floating_nav", "nav_radius", "nav_style", "glass_style", "profile_name", "profile_photo",
    "profile_name_at", "profile_photo_at", "file_here", "file_existing", "upload_ask", "keep_running", "export_lists", "lan_paired", "lan_name", "lan_port", "lan_on",
    "lan_require_pairing", "lan_token_mode",
)

const val DEFAULT_CACHE_LIMIT = 2L * 1024 * 1024 * 1024
