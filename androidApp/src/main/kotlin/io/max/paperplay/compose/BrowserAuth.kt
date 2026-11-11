package io.max.paperplay.compose

import android.content.Intent
import android.net.Uri
import io.max.paperplay.core.integrations.AuthRedirect
import io.max.paperplay.core.integrations.SpotifyLoginError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

const val SPOTIFY_REDIRECT = "paperplay://spotify"

/** Opens the login page in the browser; the return link arrives in MainActivity.onNewIntent and goes to [deliver]. */
class BrowserAuth(private val open: (Intent) -> Unit) : AuthRedirect {
    @Volatile private var waiting: CompletableDeferred<Map<String, String>>? = null

    override suspend fun authorize(authorizeUrl: String, redirectUri: String): Map<String, String> {
        val wait = CompletableDeferred<Map<String, String>>()
        waiting = wait
        try {
            open(Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            waiting = null
            throw SpotifyLoginError("Could not open the browser.")
        }
        return try {
            withTimeout(5 * 60_000L) { wait.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw SpotifyLoginError("Timed out waiting for Spotify. Try again.")
        } finally {
            waiting = null
        }
    }

    fun deliver(data: Uri?) {
        if (data == null || data.scheme != "paperplay" || data.host != "spotify") return
        val q = data.queryParameterNames.associateWith { data.getQueryParameter(it).orEmpty() }
        waiting?.complete(q)
    }
}
