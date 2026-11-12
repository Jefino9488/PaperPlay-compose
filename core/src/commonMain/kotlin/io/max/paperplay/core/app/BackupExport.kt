package io.max.paperplay.core.app

import io.max.paperplay.core.account.UserSession
import io.max.paperplay.core.backup.BackupSummary
import io.max.paperplay.core.backup.BackupWriter
import io.max.paperplay.core.backup.Fs
import io.max.paperplay.core.backup.SecretKeys
import io.max.paperplay.core.backup.sealBytes
import io.max.paperplay.core.backup.sealFile
import io.max.paperplay.core.backup.writeZip
import io.max.paperplay.core.backup.zipFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlin.time.Clock

/** A runaway account folder would make a huge backup; the account can sign in again instead (Flutter's limit). */
private const val MAX_SESSION_BYTES = 300L * 1024 * 1024

/**
 * Writes a full library backup zip to [outPath], in the Flutter app's format. With a [password] it also holds, sealed
 * with it, the API id and hash, the bot token, extra bots, Spotify, the ListenBrainz token and the account session.
 * The account session client is stopped for a moment so its files are whole. Throws on failure and leaves no file.
 */
suspend fun AppServices.createBackup(
    outPath: String,
    password: String?,
    onProgress: (stage: String, fraction: Double?) -> Unit = { _, _ -> },
): BackupSummary = withContext(Dispatchers.Default) {
    onProgress("Starting", 0.0)
    val sessionZip = "$outPath.session.zip"
    val sessionEnc = "$outPath.session.enc"
    try {
        var sealed: String? = null
        val token = platform.secrets.get(Keys.BOT_TOKEN)
        val id = platform.settings.get(Keys.API_ID)?.toIntOrNull()
        val hash = platform.settings.get(Keys.API_HASH)
        if (password != null && !token.isNullOrEmpty() && id != null && !hash.isNullOrEmpty()) {
            onProgress("Encrypting your keys", 0.01)
            val spotifyRefresh = platform.secrets.get(SecretKeys.SPOTIFY_REFRESH_TOKEN)
            val extra: JsonArray? = platform.secrets.get(SecretKeys.EXTRA_BOTS)
                ?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }?.takeIf { it.isNotEmpty() }
            val json = buildJsonObject {
                put("apiId", id)
                put("apiHash", hash)
                put("botToken", token)
                extra?.let { put("extraBots", it as JsonElement) }
                if (spotifyRefresh != null) {
                    platform.secrets.get(SecretKeys.SPOTIFY_CLIENT_ID)?.let { put("spotifyClientId", it) }
                    put("spotifyRefreshToken", spotifyRefresh)
                }
                platform.secrets.get(SecretKeys.LISTENBRAINZ_TOKEN)?.let { put("listenBrainzToken", it) }
            }
            sealed = sealBytes(json.toString().encodeToByteArray(), password)
        }
        var session: String? = null
        val user = account as? UserSession
        if (password != null && user != null && user.loggedIn.value) {
            onProgress("Saving your account session", 0.01)
            user.closeForBackup()
            val key = platform.secrets.get(SecretKeys.USER_SESSION_KEY)
            if (key != null && zipFolder("${platform.dataDir}/user_td", "__key", key.encodeToByteArray(), sessionZip, MAX_SESSION_BYTES)) {
                sealFile(sessionZip, password, sessionEnc)
                session = sessionEnc
            }
        }
        var summary: BackupSummary? = null
        writeZip(outPath) { sink ->
            summary = BackupWriter.write(driver, sink, Clock.System.now().toString(), sealed, session, onProgress)
        }
        summary!!
    } catch (e: Throwable) {
        Fs.deleteRecursively(outPath)
        throw e
    } finally {
        Fs.deleteRecursively(sessionZip)
        Fs.deleteRecursively(sessionEnc)
    }
}
