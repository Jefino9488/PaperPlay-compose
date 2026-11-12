package io.max.paperplay.core.db

import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.Song
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryDbTest {
    private var now = 1_000L
    private fun open() = libraryDriver().let { LibraryRepo(LibraryDatabase(it)) { ++now } }

    private fun song(chat: Long, msg: Long, uid: String, title: String = "t$msg", date: Long = msg) = Song(
        chat, msg, uid, "r$msg", title, "artist", "", 0, 100, "audio/mpeg", "$title.mp3", date,
        null, null, null, null, null, null, false, false, false, 0, null, 0, null, false, 0, 0, 0, false, 0, 0, 0,
        null, null, null, null,
    )

    private fun tmp() = File.createTempFile("lib", ".sqlite").also { it.delete(); it.deleteOnExit() }

    private fun userVersion(f: File): Int = DriverManager.getConnection("jdbc:sqlite:${f.path}").use { c ->
        c.createStatement().executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
    }

    @Test
    fun schemaVersionMatchesDrift() {
        val f = tmp()
        libraryDriver(f.path).close()
        assertEquals(15, userVersion(f)) // Drift schemaVersion in db.dart
        DriverManager.getConnection("jdbc:sqlite:${f.path}").use { c ->
            val names = c.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            assertEquals(
                listOf(
                    "forum_topics", "library_chats", "listen_stats", "missing_tracks", "mood_votes",
                    "playlist_songs", "playlists", "post_copies", "prefs", "songs",
                ),
                names,
            )
        }
    }

    @Test
    fun songsChatsAndPlaylists() {
        val repo = open()
        val q = repo.db.songsQueries
        q.insertSong(song(1, 10, "u1", date = 5))
        q.insertSong(song(1, 11, "u2", date = 9))
        q.insertSong(song(2, 20, "u3", date = 7))
        assertEquals(listOf(11L, 20L, 10L), q.allSongs().executeAsList().map { it.message_id })
        assertEquals(listOf(10L, 11L), q.songsInChat(1).executeAsList().map { it.message_id })
        assertEquals(3, q.libraryStats().executeAsOne().total)

        val pl = repo.createPlaylist("mix")
        val s11 = q.song(1, 11).executeAsOne()
        repo.addToPlaylist(pl, q.song(1, 10).executeAsOne())
        repo.addToPlaylist(pl, s11)
        assertEquals(listOf(10L, 11L), repo.db.playlistsQueries.playlistSongs(pl).executeAsList().map { it.message_id })
        repo.removeFromPlaylist(pl, s11)
        assertEquals(1, repo.db.playlistsQueries.playlistSongs(pl).executeAsList().size)
        repo.deletePlaylist(pl)
        assertTrue(repo.db.playlistsQueries.livePlaylists().executeAsList().isEmpty())
    }

    @Test
    fun duplicatePostsAndRemovePost() {
        val repo = open()
        val q = repo.db.songsQueries
        assertTrue(repo.upsertBotSong(song(1, 10, "same")))
        assertFalse(repo.upsertBotSong(song(2, 50, "same", date = 3))) // repeat of the file: a copy row
        assertEquals(1, q.allSongs().executeAsList().size)
        assertEquals(listOf(50L), repo.db.miscQueries.postCopiesOf("same").executeAsList().map { it.message_id })

        val pl = repo.createPlaylist("p")
        repo.addToPlaylist(pl, q.song(1, 10).executeAsOne())
        repo.removePost(1, 10) // the copy takes over, playlist follows
        val s = q.songByUniqueId("same").executeAsOne()
        assertEquals(2L to 50L, s.chat_id to s.message_id)
        assertTrue(repo.db.miscQueries.postCopiesOf("same").executeAsList().isEmpty())
        assertEquals(listOf(50L), repo.db.playlistsQueries.playlistSongs(pl).executeAsList().map { it.message_id })
        repo.removePost(2, 50) // last post: song goes
        assertNull(q.songByUniqueId("same").executeAsOneOrNull())
    }

    @Test
    fun listenStatsAddAndMerge() {
        val repo = open()
        repo.addListen("me", "2026-01-01", "k", 100, 1)
        repo.addListen("me", "2026-01-01", "k", 50, 1)
        repo.mergeListen(listOf(listOf("other", "2026-01-01", "k", 70, 2), listOf("me", "x", "k", 1, 1)), "me")
        val t = repo.listenTotals().single()
        assertEquals(220L to 4L, t.ms to t.plays)
    }

    /** An old Drift file whose columns were added by ALTER TABLE opens, upgrades, and reads correctly. */
    @Test
    fun opensAndUpgradesOlderDriftFile() {
        val f = tmp()
        DriverManager.getConnection("jdbc:sqlite:${f.path}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    """CREATE TABLE songs (chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL, unique_id TEXT NOT NULL,
                    remote_id TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL DEFAULT '',
                    duration_sec INTEGER NOT NULL DEFAULT 0, size INTEGER NOT NULL, mime TEXT NOT NULL, file_name TEXT NOT NULL,
                    date INTEGER NOT NULL, cover_path TEXT, enrich_state INTEGER NOT NULL DEFAULT 0, lyrics TEXT,
                    cached_bytes INTEGER NOT NULL DEFAULT 0, last_played_at INTEGER, pinned INTEGER NOT NULL DEFAULT 0,
                    user_edited INTEGER NOT NULL DEFAULT 0, release_id TEXT, PRIMARY KEY (chat_id, message_id), UNIQUE (unique_id))""",
                )
                st.executeUpdate("CREATE TABLE library_chats (id INTEGER NOT NULL, title TEXT NOT NULL, last_scanned_message_id INTEGER, PRIMARY KEY (id))")
                st.executeUpdate("CREATE TABLE playlists (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
                st.executeUpdate(
                    "CREATE TABLE playlist_songs (playlist_id INTEGER NOT NULL REFERENCES playlists (id) ON DELETE CASCADE, " +
                        "chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY (playlist_id, chat_id, message_id))",
                )
                st.executeUpdate("CREATE TABLE prefs (key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (key))")
                st.executeUpdate("INSERT INTO songs (chat_id, message_id, unique_id, remote_id, title, artist, size, mime, file_name, date, enrich_state) VALUES (1, 2, 'u', 'r', 'Old', 'A', 5, 'audio/mpeg', 'f.mp3', 3, 2)")
                st.executeUpdate("INSERT INTO library_chats VALUES (1, 'c', 7)")
                st.executeUpdate("INSERT INTO playlists (name) VALUES ('legacy')")
                st.executeUpdate("PRAGMA user_version = 3")
            }
        }
        val db = LibraryDatabase(libraryDriver(f.path))
        val s = db.songsQueries.allSongs().executeAsList().single()
        assertEquals("Old", s.title)
        assertEquals(0L, s.enrich_state) // v3 step: earlier non-matches retried
        assertEquals(1L, db.miscQueries.chats().executeAsOne().let { if (it.walk_done) 1L else 0L })
        assertEquals("legacy-1-legacy", db.playlistsQueries.livePlaylists().executeAsOne().uuid)
        assertNotNull(db.miscQueries.chatTitle(1).executeAsOneOrNull())
        db.songsQueries.setFavorite(true, 5, 1, 2)
        assertTrue(db.songsQueries.favorites().executeAsOne().favorite)
        assertEquals(15, userVersion(f))
    }
}
