package io.max.paperplay.core.backup

import io.max.paperplay.core.db.libraryDriver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupRestorerTest {
    private val token = "123456:SECRET-bot-token-value"
    private val pw = "correct horse"

    private class MemSecrets : SecretStore {
        val map = mutableMapOf<String, ByteArray>()
        override fun put(key: String, value: ByteArray) { map[key] = value }
        override fun get(key: String) = map[key]
        override fun remove(key: String) { map.remove(key) }
    }

    private class Env {
        val dir: File = Files.createTempDirectory("restore").toFile().also { it.deleteOnExit() }
        val secrets = MemSecrets()
        val db = "${dir.path.replace('\\', '/')}/library.sqlite"
        val target = RestoreTarget(db, "${dir.path.replace('\\', '/')}/covers", "${dir.path.replace('\\', '/')}/profile", secrets, "${dir.path.replace('\\', '/')}/user_td") { libraryDriver(it) }
        fun query(sql: String): List<List<String?>> = DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) }
            }
        }
    }

    /** Mirrors Flutter's sealBytes: PBKDF2-HMAC-SHA256 (200000) -> AES-256-GCM, JSON of base64 parts. */
    private fun seal(plain: ByteArray, password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, 200_000, 256)).encoded
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        val out = c.doFinal(plain)
        val e = Base64.getEncoder()
        val (ct, mac) = out.copyOfRange(0, out.size - 16) to out.copyOfRange(out.size - 16, out.size)
        return """{"salt":"${e.encodeToString(salt)}","nonce":"${e.encodeToString(nonce)}","mac":"${e.encodeToString(mac)}","data":"${e.encodeToString(ct)}"}"""
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun source(bytes: ByteArray) = streamBackupSource { bytes.inputStream() }

    private fun song(i: Int, extra: String = "", title: String = "\"title\":\"T$i\",") =
        """{"chatId":1,"messageId":$i,"uniqueId":"u$i","remoteId":"r$i",$title"artist":"A","size":10,"mime":"audio/mpeg","fileName":"f$i.mp3","date":$i$extra}"""

    private fun manifest(
        songs: String = song(1, ""","coverPath":"/old/install/covers/c1.jpg","favorite":true,"cachedBytes":99"""),
        playlists: String = """{"id":1,"name":"P","uuid":"abc","modifiedAt":5}""",
        prefs: String = """{"key":"cache_limit","value":"5"},{"key":"device_id","value":"OTHER"},{"key":"theme_mode","value":"old"}""",
        layout: String = """"theme_mode":"dark","evil_key":"x"""",
        version: Int = 1,
    ) = """{"version":$version,"createdAt":"2026-01-01T00:00:00","chats":[{"id":1,"title":"Chat"}],"songs":[$songs],
        "playlists":[$playlists],"playlistSongs":[{"playlistId":1,"chatId":1,"messageId":1,"addedAt":3}],
        "forumTopics":[],"postCopies":[],"prefs":[$prefs],"layout":{$layout}}""".toByteArray()

    private fun creds() = seal(
        """{"apiId":42,"apiHash":"hash","botToken":"$token","extraBots":["b2"],"spotifyClientId":"cid","spotifyRefreshToken":"rt","listenBrainzToken":"lb"}""".toByteArray(),
        pw,
    ).toByteArray()

    private fun run(env: Env, bytes: ByteArray, options: RestoreOptions = RestoreOptions(password = pw)) = runBlocking {
        BackupRestorer().restore(source(bytes), env.target, options).toList()
    }

    private fun result(events: List<RestoreEvent>) = (events.last() as RestoreEvent.Finished).result

    @Test
    fun happyPath() {
        val env = Env()
        val session = zip(mapOf("__key" to "dbkey".toByteArray(), "db.sqlite" to byteArrayOf(1, 2, 3)))
        val z = zip(
            mapOf(
                "library.json" to manifest(),
                "credentials.enc" to creds(),
                "usersession.enc" to seal(session, pw).toByteArray(),
                "covers/c1.jpg" to byteArrayOf(9, 9),
                "covers/../evil.jpg" to byteArrayOf(1),
                "profile/me.png" to byteArrayOf(7),
                "mystery.bin" to byteArrayOf(0),
            ),
        )
        val events = run(env, z)
        assertTrue(events.first() is RestoreEvent.Progress)
        val r = result(events)
        assertEquals(1, r.songs)
        assertEquals(listOf(listOf("1", "audio/mpeg"[0].toString().let { "T1" }, "${env.target.coversDir}/c1.jpg", "1", "0")),
            env.query("select message_id, title, cover_path, favorite, cached_bytes from songs"))
        val prefs = env.query("select key, value from prefs").associate { it[0] to it[1] }
        assertEquals("5", prefs["cache_limit"])
        assertEquals("dark", prefs["theme_mode"]) // layout wins when requested
        assertFalse("device_id" in prefs) // another device's id is never taken
        assertFalse("evil_key" in prefs)
        assertEquals("${env.target.profileDir}/me.png", prefs["profile_photo"])
        assertTrue(File(env.dir, "covers/c1.jpg").exists())
        assertFalse(File(env.dir, "evil.jpg").exists())
        assertEquals(token, env.secrets.get(SecretKeys.BOT_TOKEN)!!.decodeToString())
        assertEquals("""["b2"]""", env.secrets.get(SecretKeys.EXTRA_BOTS)!!.decodeToString())
        assertEquals("rt", env.secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN)!!.decodeToString())
        // The account folder is unpacked on disk and its database key kept as a secret.
        assertContentEquals("dbkey".toByteArray(), env.secrets.get(SecretKeys.USER_SESSION_KEY))
        assertContentEquals(byteArrayOf(1, 2, 3), File(env.dir, "user_td/db.sqlite").readBytes())
        assertFalse(File(env.dir, "user_td/__key").exists())
        assertTrue(r.of(Outcome.Restored).any { it.name == "Account session" })
        assertEquals(listOf("Unrecognized entries"), r.of(Outcome.Skipped).map { it.name }.filter { it.startsWith("Unrec") })
        assertTrue(r.of(Outcome.Failed).isEmpty())
        assertFalse(File(env.db + ".new").exists() || File(env.db + ".restoring").exists())
    }

    @Test
    fun noPasswordSkipsSecretsButRestoresLibrary() {
        val env = Env()
        val r = result(run(env, zip(mapOf("library.json" to manifest(), "credentials.enc" to creds())), RestoreOptions()))
        assertEquals(1, r.songs)
        assertNull(env.secrets.get(SecretKeys.BOT_TOKEN))
        assertTrue(r.of(Outcome.Skipped).any { it.name == "Bot credentials" })
    }

    @Test
    fun layoutNotAppliedKeepsOwnPrefs() {
        val env = Env()
        result(run(env, zip(mapOf("library.json" to manifest())), RestoreOptions(applyLayout = false)))
        assertFalse(env.query("select value from prefs where key = 'theme_mode'").isNotEmpty())
    }

    @Test
    fun backgroundAndStandbyFollowTheLayoutChoice() {
        val prefs = """{"key":"bg_mode","value":"image"},{"key":"bg_image","value":"/other/device/pic.jpg"},{"key":"standby_style","value":"clock"},{"key":"standby_w_weather","value":"true"}"""
        val layout = """"bg_mode":"image","bg_image":"/other/device/pic.jpg","standby_style":"clock","standby_w_weather":"true""""
        val bytes = zip(mapOf("library.json" to manifest(prefs = prefs, layout = layout)))
        val kept = Env()
        result(run(kept, bytes, RestoreOptions(applyLayout = false)))
        assertEquals(emptyList(), kept.query("select key from prefs where key like 'bg\\_%' escape '\\' or key like 'standby\\_%' escape '\\'"))
        val applied = Env()
        result(run(applied, bytes, RestoreOptions(applyLayout = true)))
        val got = applied.query("select key, value from prefs").associate { it[0] to it[1] }
        assertEquals("image", got["bg_mode"])
        assertEquals("clock", got["standby_style"])
        assertEquals("true", got["standby_w_weather"])
        assertFalse("bg_image" in got) // a file path on another device
    }

    @Test
    fun corruptZip() {
        val env = Env()
        val good = zip(mapOf("library.json" to manifest(), "covers/c.jpg" to ByteArray(5000) { it.toByte() }))
        assertFailsWith<BackupException> { run(env, ByteArray(300) { (it * 7).toByte() }) }
        assertFailsWith<BackupException> { run(env, good.copyOf(good.size / 2)) }
        assertFalse(File(env.db).exists())
    }

    @Test
    fun missingLibrary() {
        val env = Env()
        val e = assertFailsWith<BackupException> { run(env, zip(mapOf("covers/c.jpg" to byteArrayOf(1)))) }
        assertTrue(e.message!!.contains("isn't a PaperPlay backup"))
        assertFailsWith<BackupException> { run(env, zip(mapOf("library.json" to "not json".toByteArray()))) }
        assertFailsWith<BackupException> { run(env, zip(mapOf("library.json" to manifest(version = 2)))) }
        assertFalse(File(env.db).exists())
    }

    @Test
    fun olderSchemaGetsDefaultsAndPreparedRows() {
        val env = Env()
        // No tagsAt/favoriteAt/plays/etc. on songs, no uuid/modifiedAt on playlists, no topics or mood data.
        val old = """{"version":1,"chats":[{"id":1,"title":"C"}],"songs":[${song(1, ""","favorite":true,"userEdited":true""")}],
            "playlists":[{"id":1,"name":"Old"}],"playlistSongs":[],"prefs":[]}""".toByteArray()
        run(env, zip(mapOf("library.json" to old)))
        assertEquals(listOf(listOf("1", "1", "0")), env.query("select tags_at, favorite_at, plays from songs"))
        assertEquals(listOf(listOf("legacy-1-Old")), env.query("select uuid from playlists"))
        assertEquals("15", env.query("pragma user_version")[0][0])
    }

    @Test
    fun secretsNeverLeakIntoErrors() {
        val env = Env()
        // A bad song row (no title) makes the restore fail; the zip also carries the sealed token.
        val bad = zip(mapOf("library.json" to manifest(songs = song(1, "", title = "")), "credentials.enc" to creds()))
        val e1 = assertFailsWith<BackupException> { run(env, bad) }
        val wrong = assertFailsWith<WrongPassword> { run(env, zip(mapOf("library.json" to manifest(), "credentials.enc" to creds())), RestoreOptions(password = "nope")) }
        for (e in listOf(e1, wrong)) {
            val text = e.toString() + e.stackTraceToString().lines().first()
            assertFalse(token in text || pw in text || "SECRET" in text)
        }
        assertTrue(env.secrets.map.isEmpty())
        assertFalse(File(env.db).exists()) // wrong password stopped everything before any change
    }

    @Test
    fun failedRestoreKeepsPreviousState() {
        val env = Env()
        run(env, zip(mapOf("library.json" to manifest(), "covers/c1.jpg" to byteArrayOf(9, 9))))
        val before = env.query("select message_id, title from songs")
        val coverBefore = File(env.dir, "covers/c1.jpg").readBytes()
        val bad = zip(mapOf("library.json" to manifest(songs = song(7, "", title = "")), "covers/new.jpg" to byteArrayOf(1)))
        assertFailsWith<BackupException> { run(env, bad) }
        assertEquals(before, env.query("select message_id, title from songs"))
        assertContentEquals(coverBefore, File(env.dir, "covers/c1.jpg").readBytes())
        assertFalse(File(env.dir, "covers/new.jpg").exists())
        assertEquals(listOf("covers", "library.sqlite"), env.dir.list()!!.sorted())
    }

    @Test
    fun restoreOverExistingKeepsDevicePrefsAndReplacesRest() {
        val env = Env()
        run(env, zip(mapOf("library.json" to manifest())))
        DriverManager.getConnection("jdbc:sqlite:${env.db}").use { it.createStatement().execute("insert into prefs values ('device_id','MINE')") }
        run(env, zip(mapOf("library.json" to manifest(songs = song(2)))))
        assertEquals(listOf(listOf("2")), env.query("select message_id from songs"))
        assertEquals(listOf(listOf("MINE")), env.query("select value from prefs where key = 'device_id'"))
        assertFalse(File(env.db + ".old").exists())
    }

    @Test
    fun dpapiRoundTripOnWindows() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val dir = Files.createTempDirectory("sec").toFile().also { it.deleteOnExit() }
        val store = desktopSecretStore(dir)
        store.put("botToken", token.toByteArray())
        assertFalse(File(dir, "botToken.sec").readBytes().decodeToString().contains(token))
        assertEquals(token, store.get("botToken")!!.decodeToString())
    }
}
