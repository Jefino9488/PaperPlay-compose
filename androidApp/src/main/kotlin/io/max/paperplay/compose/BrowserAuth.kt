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
        val w = waiting
        if (w != null) { w.complete(q); return }
        // Nobody is waiting: the app was ended during the browser step. Hand it on once services exist.
        val h = lateHandler
        if (h != null) h(q) else late = q
    }

    @Volatile private var late: Map<String, String>? = null
    @Volatile private var lateHandler: ((Map<String, String>) -> Unit)? = null

    /** Sets who finishes a login that returns after the app was ended; a reply that came earlier is passed now. */
    fun onLateReturn(handler: (Map<String, String>) -> Unit) {
        lateHandler = handler
        late?.let { late = null; handler(it) }
    }
}
