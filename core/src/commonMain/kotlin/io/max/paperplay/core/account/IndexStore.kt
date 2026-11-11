package io.max.paperplay.core.account

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** What is saved about one indexed chat. [complete] is false until a first full read has finished. */
data class IndexedChat(val id: Long, val title: String, val newestMessageId: Long, val lastRefreshAt: Long, val complete: Boolean)

/**
 * The saved index: posts of the account's chats, per-chat state and which songs were copied.
 * Plain SQL on the library file, created when first used, so the library schema version is untouched.
 * Logging out of the account never touches it.
 */
class IndexStore(private val driver: SqlDriver) {
    /** Counts every write, for flows that re-read. */
    val changes = MutableStateFlow(0L)

    init {
        exec("CREATE TABLE IF NOT EXISTS index_chats (chat_id INTEGER NOT NULL PRIMARY KEY, title TEXT NOT NULL, newest_message_id INTEGER NOT NULL DEFAULT 0, last_refresh_at INTEGER NOT NULL DEFAULT 0, complete INTEGER NOT NULL DEFAULT 0)")
        exec(
            "CREATE TABLE IF NOT EXISTS index_posts (chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL, unique_id TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, " +
                "duration_sec INTEGER NOT NULL, size INTEGER NOT NULL, mime TEXT NOT NULL, file_name TEXT NOT NULL, date INTEGER NOT NULL, PRIMARY KEY (chat_id, message_id))",
        )
        exec("CREATE INDEX IF NOT EXISTS index_posts_unique ON index_posts (unique_id)")
        exec("CREATE TABLE IF NOT EXISTS index_copied (unique_id TEXT NOT NULL PRIMARY KEY, chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL, copied_at INTEGER NOT NULL)")
    }

    private fun exec(sql: String, params: Int = 0, binders: (SqlPreparedStatement.() -> Unit)? = null) {
        driver.execute(null, sql, params, binders)
    }

    private fun <T> query(sql: String, params: Int = 0, binders: (SqlPreparedStatement.() -> Unit)? = null, read: (SqlCursor) -> T): List<T> =
        driver.executeQuery(
            null, sql,
            { c ->
                val rows = mutableListOf<T>()
                while (c.next().value) rows += read(c)
                QueryResult.Value(rows)
            },
            params, binders,
        ).value

    private fun touch() = changes.update { it + 1 }

    fun chats(): List<IndexedChat> = query("SELECT chat_id, title, newest_message_id, last_refresh_at, complete FROM index_chats ORDER BY title") {
        IndexedChat(it.getLong(0)!!, it.getString(1)!!, it.getLong(2)!!, it.getLong(3)!!, it.getLong(4) == 1L)
    }

    fun chat(id: Long): IndexedChat? = chats().firstOrNull { it.id == id }

    fun saveChat(id: Long, title: String) {
        exec("INSERT INTO index_chats (chat_id, title) VALUES (?, ?) ON CONFLICT(chat_id) DO UPDATE SET title = excluded.title", 2) { bindLong(0, id); bindString(1, title) }
        touch()
    }

    fun setState(id: Long, newest: Long, refreshedAt: Long, complete: Boolean) {
        exec("UPDATE index_chats SET newest_message_id = MAX(newest_message_id, ?), last_refresh_at = ?, complete = ? WHERE chat_id = ?", 4) {
            bindLong(0, newest); bindLong(1, refreshedAt); bindLong(2, if (complete) 1 else 0); bindLong(3, id)
        }
        touch()
    }

    fun upsertPosts(posts: List<ChatPost>) {
        for (p in posts) {
            exec(
                "INSERT OR REPLACE INTO index_posts (chat_id, message_id, unique_id, title, artist, duration_sec, size, mime, file_name, date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", 10,
            ) {
                bindLong(0, p.chatId); bindLong(1, p.messageId); bindString(2, p.uniqueId); bindString(3, p.title); bindString(4, p.artist)
                bindLong(5, p.durationSec.toLong()); bindLong(6, p.size); bindString(7, p.mime); bindString(8, p.fileName); bindLong(9, p.date)
            }
        }
        touch()
    }

    private fun post(c: SqlCursor) = ChatPost(
        c.getLong(0)!!, c.getLong(1)!!, c.getString(2)!!, c.getString(3)!!, c.getString(4)!!,
        c.getLong(5)!!.toInt(), c.getLong(6)!!, c.getString(7)!!, c.getString(8)!!, c.getLong(9)!!,
    )

    private val postColumns = "chat_id, message_id, unique_id, title, artist, duration_sec, size, mime, file_name, date"

    fun posts(chatId: Long): List<ChatPost> =
        query("SELECT $postColumns FROM index_posts WHERE chat_id = ? ORDER BY message_id DESC", 1, { bindLong(0, chatId) }, ::post)

    fun allPosts(): List<ChatPost> = query("SELECT $postColumns FROM index_posts ORDER BY date DESC", read = ::post)

    fun count(chatId: Long): Int = query("SELECT COUNT(*) FROM index_posts WHERE chat_id = ?", 1, { bindLong(0, chatId) }) { it.getLong(0)!!.toInt() }.first()

    fun deleteChat(id: Long) {
        exec("DELETE FROM index_posts WHERE chat_id = ?", 1) { bindLong(0, id) }
        exec("DELETE FROM index_chats WHERE chat_id = ?", 1) { bindLong(0, id) }
        touch()
    }

    fun markCopied(p: ChatPost, at: Long) {
        exec("INSERT OR REPLACE INTO index_copied (unique_id, chat_id, message_id, copied_at) VALUES (?, ?, ?, ?)", 4) {
            bindString(0, p.uniqueId); bindLong(1, p.chatId); bindLong(2, p.messageId); bindLong(3, at)
        }
        touch()
    }

    fun clearCopied() {
        exec("DELETE FROM index_copied")
        touch()
    }

    /** Unique ids of songs copied to the main chat from here. */
    fun copiedIds(): Set<String> = query("SELECT unique_id FROM index_copied") { it.getString(0)!! }.toSet()

    data class Copied(val uniqueId: String, val chatId: Long, val messageId: Long, val at: Long)

    fun copiedRows(): List<Copied> = query("SELECT unique_id, chat_id, message_id, copied_at FROM index_copied") {
        Copied(it.getString(0)!!, it.getLong(1)!!, it.getLong(2)!!, it.getLong(3)!!)
    }
}
