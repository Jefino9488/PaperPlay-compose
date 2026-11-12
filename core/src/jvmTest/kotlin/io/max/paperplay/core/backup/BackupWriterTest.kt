package io.max.paperplay.core.backup

import io.max.paperplay.core.db.libraryDriver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupWriterTest {
    private class MemSecrets : SecretStore {
        val map = mutableMapOf<String, ByteArray>()
        override fun put(key: String, value: ByteArray) { map[key] = value }
        override fun get(key: String) = map[key]
        override fun remove(key: String) { map.remove(key) }
    }

    private fun dir() = Files.createTempDirectory("writer").toFile().also { it.deleteOnExit() }
    private fun File.p() = path.replace('\\', '/')
    private fun target(d: File, secrets: SecretStore) =
        RestoreTarget("${d.p()}/library.sqlite", "${d.p()}/covers", "${d.p()}/profile", secrets, "${d.p()}/user_td") { libraryDriver(it) }

    private fun query(db: String, sql: String): List<List<String?>> = DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
        c.createStatement().executeQuery(sql).use { rs ->
            buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) }
        }
    }

    @Test
    fun writeThenRestoreRoundTrip() {
        val src = dir()
        val cover = File(src, "covers/c1.jpg").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(9, 9, 9)) }
        val photo = File(src, "profile/me.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(7)) }
        val driver = libraryDriver("${src.p()}/library.sqlite")
        fun sql(s: String) = driver.execute(null, s, 0)
        sql("INSERT INTO library_chats (id, title, use_bot) VALUES (1, 'Chat', 1)")
        sql(
            "INSERT INTO songs (chat_id, message_id, unique_id, remote_id, title, artist, size, mime, file_name, date, favorite, cover_path, cached_bytes, plays) " +
                "VALUES (1, 5, 'u5', 'r5', 'Song', 'Art', 10, 'audio/mpeg', 'f.mp3', 77, 1, '${cover.p()}', 99, 3)",
        )
        sql("INSERT INTO playlists (id, name, uuid, modified_at) VALUES (1, 'P', 'abc', 5)")
        sql("INSERT INTO playlist_songs (playlist_id, chat_id, message_id, added_at) VALUES (1, 1, 5, 3)")
        sql("INSERT INTO prefs (key, value) VALUES ('theme_mode', 'dark'), ('cache_limit', '5'), ('device_id', 'SRC'), ('profile_photo', '${photo.p()}'), ('user_session', '1')")
        val sealed = sealBytes("""{"apiId":42,"apiHash":"h","botToken":"123:tok"}""".toByteArray(), "pw")

        val zipPath = "${src.p()}/out.zip"
        var summary: BackupSummary? = null
        writeZip(zipPath) { sink -> summary = BackupWriter.write(driver, sink, "2026-01-01T00:00:00Z", sealed) }
        driver.close()

        ZipFile(zipPath).use { z ->
            val names = z.entries().asSequence().map { it.name }.toSet()
            assertEquals(setOf("library.json", "credentials.enc", "profile/me.png", "covers/c1.jpg"), names)
            val json = z.getInputStream(z.getEntry("library.json")).readBytes().decodeToString()
            assertTrue("\"favorite\":true" in json && "\"chatId\":1" in json && "\"useBot\":true" in json, json) // Drift toJson shape
            assertFalse("user_session" in json) // no session in this backup
        }

        val dst = dir()
        val secrets = MemSecrets()
        val events = runBlocking { BackupRestorer().restore(fileBackupSource(zipPath), target(dst, secrets), RestoreOptions(password = "pw")).toList() }
        val result = (events.last() as RestoreEvent.Finished).result
        assertEquals(1, result.songs)
        assertTrue(result.of(Outcome.Failed).isEmpty())
        val db = "${dst.p()}/library.sqlite"
        assertEquals(listOf(listOf("5", "Song", "1", "${dst.p()}/covers/c1.jpg", "0", "3")), query(db, "select message_id, title, favorite, cover_path, cached_bytes, plays from songs"))
        assertEquals(listOf(listOf("1", "1")), query(db, "select id, use_bot from library_chats"))
        assertEquals(listOf(listOf("P", "abc", "5")), query(db, "select name, uuid, modified_at from playlists"))
        assertEquals(listOf(listOf("1", "5", "3")), query(db, "select playlist_id, message_id, added_at from playlist_songs"))
        val prefs = query(db, "select key, value from prefs").associate { it[0] to it[1] }
        assertEquals("dark", prefs["theme_mode"])
        assertEquals("5", prefs["cache_limit"])
        assertFalse("device_id" in prefs)
        assertEquals("${dst.p()}/profile/me.png", prefs["profile_photo"])
        assertContentEquals(byteArrayOf(9, 9, 9), File(dst, "covers/c1.jpg").readBytes())
        assertEquals("123:tok", secrets.get(SecretKeys.BOT_TOKEN)!!.decodeToString())
        assertEquals("42", secrets.get(SecretKeys.API_ID)!!.decodeToString())
        assertEquals(1, summary!!.songs)
        assertEquals(1, summary!!.covers)
    }

    @Test
    fun sealedSessionStreamsAndOpens() {
        val d = dir()
        val folder = File(d, "user_td").apply { mkdirs() }
        File(folder, "db.sqlite").writeBytes(ByteArray(300_000) { (it * 31).toByte() })
        File(folder, "sub/x.bin").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2)) }
        assertTrue(zipFolder(folder.p(), "__key", "thekey".toByteArray(), "${d.p()}/s.zip", 10_000_000))
        assertFalse(zipFolder(folder.p(), "__key", ByteArray(1), "${d.p()}/big.zip", 10))
        sealFile("${d.p()}/s.zip", "pw", "${d.p()}/s.enc")
        // Whole-string opening (what Flutter does) and the streaming opening both give the zip back.
        val plain = File(d, "s.zip").readBytes()
        assertContentEquals(plain, openSealed(File(d, "s.enc").readText(), "pw"))
        assertFailsWith<WrongPassword> { openSealed(File(d, "s.enc").readText(), "nope") }
        openSealedFile("${d.p()}/s.enc", "pw", "${d.p()}/back.zip")
        assertContentEquals(plain, File(d, "back.zip").readBytes())
        val key = extractSessionZip("${d.p()}/back.zip", "${d.p()}/out")
        assertContentEquals("thekey".toByteArray(), key)
        assertContentEquals(byteArrayOf(1, 2), File(d, "out/sub/x.bin").readBytes())
    }
}
