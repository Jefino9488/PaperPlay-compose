package io.max.paperplay.desktop

import io.max.paperplay.core.integrations.AuthRedirect
import io.max.paperplay.core.integrations.SpotifyLoginError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder

const val SPOTIFY_REDIRECT = "http://127.0.0.1:8888/callback"

/** Opens the login page in the browser and waits for the redirect on a short-lived loopback server. */
class LoopbackAuth : AuthRedirect {
    override suspend fun authorize(authorizeUrl: String, redirectUri: String): Map<String, String> = withContext(Dispatchers.IO) {
        val uri = URI(redirectUri)
        val server = try {
            ServerSocket(uri.port, 4, InetAddress.getByName("127.0.0.1"))
        } catch (_: Exception) {
            throw SpotifyLoginError("Port ${uri.port} is in use by another program. Close it and try again.")
        }
        server.use {
            server.soTimeout = 1000
            try {
                java.awt.Desktop.getDesktop().browse(URI(authorizeUrl))
            } catch (_: Exception) {
                throw SpotifyLoginError("Could not open the browser.")
            }
            val deadline = System.currentTimeMillis() + 5 * 60_000
            while (System.currentTimeMillis() < deadline) {
                ensureActive()
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use { s ->
                    val line = s.getInputStream().bufferedReader().readLine() ?: return@use
                    val target = line.split(' ').getOrNull(1) ?: return@use
                    val u = URI(target)
                    val out = s.getOutputStream()
                    if (u.path != uri.path) {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        return@use
                    }
                    val q = (u.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
                        val kv = it.split('=', limit = 2)
                        URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
                    }
                    val ok = q["code"] != null
                    val html = "<!doctype html><meta charset=\"utf-8\"><title>Paper Play</title>" +
                        "<body style=\"font-family:sans-serif;padding:3em\"><h2>${if (ok) "Connected to Paper Play" else "Spotify did not connect"}</h2>" +
                        "<p>You can close this tab and go back to the app.</p>"
                    val bytes = html.toByteArray()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                    return@withContext q
                }
            }
            throw SpotifyLoginError("Timed out waiting for Spotify. Try again.")
        }
    }
}
