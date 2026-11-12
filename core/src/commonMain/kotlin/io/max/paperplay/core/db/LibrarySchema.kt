package io.max.paperplay.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/** Drift's `schemaVersion`, stored in SQLite's `user_version`. Bump together with db.dart. */
const val DRIFT_SCHEMA_VERSION = 15L

/** Fresh files get the SQLDelight DDL; files from the Flutter app are upgraded with Drift's steps. */
object LibrarySchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version = DRIFT_SCHEMA_VERSION

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = LibraryDatabase.Schema.create(driver)

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: app.cash.sqldelight.db.AfterVersion,
    ): QueryResult.Value<Unit> {
        fun sql(s: String) = driver.execute(null, s, 0)
        fun add(table: String, col: String, def: String) = sql("ALTER TABLE $table ADD COLUMN \"$col\" $def")
        val bool = "INTEGER NOT NULL DEFAULT 0 CHECK (\"%s\" IN (0, 1))"
        val int0 = "INTEGER NOT NULL DEFAULT 0"
        val from = oldVersion
        val retry = "UPDATE songs SET enrich_state = 0 WHERE enrich_state = 2 AND user_edited = 0"
        if (from < 2) add("songs", "release_id", "TEXT")
        if (from < 3) sql(retry)
        if (from < 4) add("library_chats", "use_bot", bool.format("use_bot"))
        if (from < 5) {
            add("songs", "cover_url", "TEXT")
            add("songs", "genre", "TEXT")
            add("songs", "year", "INTEGER")
            add("songs", "metadata_source", "TEXT")
            add("library_chats", "walk_done", bool.format("walk_done"))
            sql("UPDATE library_chats SET walk_done = 1 WHERE last_scanned_message_id IS NOT NULL")
            sql(retry)
        }
        if (from < 6) {
            add("songs", "favorite", bool.format("favorite"))
            add("songs", "cover_hi_res", bool.format("cover_hi_res"))
        }
        if (from < 7) {
            add("songs", "tags_at", int0)
            add("songs", "favorite_at", int0)
            add("playlists", "uuid", "TEXT NOT NULL DEFAULT ''")
            add("playlists", "modified_at", int0)
            add("playlists", "deleted_at", "INTEGER")
            add("playlists", "source_url", "TEXT")
            add("playlist_songs", "removed_at", "INTEGER")
            sql(
                "CREATE TABLE IF NOT EXISTS missing_tracks (\"playlist_id\" INTEGER NOT NULL REFERENCES playlists (id) ON DELETE CASCADE, " +
                    "\"track_key\" TEXT NOT NULL, \"title\" TEXT NOT NULL, \"artist\" TEXT NOT NULL, " +
                    "\"duration_sec\" INTEGER NOT NULL DEFAULT 0, \"position\" INTEGER NOT NULL DEFAULT 0, " +
                    "\"source_added_at\" INTEGER, PRIMARY KEY (\"playlist_id\", \"track_key\"))",
            )
            sql("UPDATE songs SET tags_at = 1 WHERE tags_at = 0 AND (user_edited = 1 OR enrich_state <> 0)")
            sql("UPDATE songs SET favorite_at = 1 WHERE favorite_at = 0 AND favorite = 1")
            sql("UPDATE playlists SET uuid = 'legacy-' || id || '-' || name WHERE uuid = ''")
        }
        if (from < 8) add("songs", "cover_at", int0)
        if (from < 9) {
            add("songs", "instrumental", bool.format("instrumental"))
            add("songs", "mood_scanned_at", int0)
            add("songs", "plays", int0)
            add("songs", "skips", int0)
            sql(
                "CREATE TABLE IF NOT EXISTS mood_votes (\"chat_id\" INTEGER NOT NULL, \"message_id\" INTEGER NOT NULL, " +
                    "\"mood\" TEXT NOT NULL, \"vote\" INTEGER NOT NULL, \"at\" INTEGER NOT NULL, " +
                    "PRIMARY KEY (\"chat_id\", \"message_id\", \"mood\"))",
            )
        }
        if (from < 10) {
            add("songs", "topic_id", "INTEGER")
            sql(
                "CREATE TABLE IF NOT EXISTS forum_topics (\"chat_id\" INTEGER NOT NULL, \"topic_id\" INTEGER NOT NULL, " +
                    "\"name\" TEXT NOT NULL, PRIMARY KEY (\"chat_id\", \"topic_id\"))",
            )
            sql(
                "CREATE TABLE IF NOT EXISTS post_copies (\"chat_id\" INTEGER NOT NULL, \"message_id\" INTEGER NOT NULL, " +
                    "\"unique_id\" TEXT NOT NULL, \"file_name\" TEXT NOT NULL, \"size\" INTEGER NOT NULL, " +
                    "\"topic_id\" INTEGER, \"date\" INTEGER NOT NULL, PRIMARY KEY (\"chat_id\", \"message_id\"))",
            )
            // Drift also clears learned rows and writes a taste_settings pref with resetAt = now;
            // the pref is JSON owned by taste.dart, which is not ported yet.
            sql("UPDATE songs SET plays = 0, skips = 0 WHERE plays <> 0 OR skips <> 0")
            sql("DELETE FROM mood_votes")
        }
        if (from < 11) {
            add("songs", "sample_rate", "INTEGER")
            add("songs", "bit_depth", "INTEGER")
            add("songs", "codec", "TEXT")
        }
        if (from < 12) {
            sql("$retry AND duration_sec <= 0")
            sql("DELETE FROM prefs WHERE key = 'covers_none_found'")
        }
        if (from < 13) {
            sql(
                "CREATE TABLE IF NOT EXISTS listen_stats (\"origin\" TEXT NOT NULL, \"day\" TEXT NOT NULL, " +
                    "\"track_key\" TEXT NOT NULL, \"ms\" INTEGER NOT NULL DEFAULT 0, \"plays\" INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY (\"origin\", \"day\", \"track_key\"))",
            )
        }
        if (from < 14) {
            add("playlist_songs", "source_added_at", "INTEGER")
            if (from >= 7) add("missing_tracks", "source_added_at", "INTEGER")
        }
        if (from < 15) {
            add("playlist_songs", "position", int0)
            // Keep the order playlists already had: rank by added_at within each playlist.
            sql(
                "UPDATE playlist_songs SET position = (SELECT COUNT(*) FROM playlist_songs AS o WHERE o.playlist_id = playlist_songs.playlist_id " +
                    "AND (o.added_at < playlist_songs.added_at OR (o.added_at = playlist_songs.added_at AND o.rowid <= playlist_songs.rowid)))",
            )
        }
        return QueryResult.Unit
    }
}
