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
                    // A connection that sends nothing (a browser's spare one) must not hold up the real redirect.
                    s.soTimeout = 5_000
                    val line = try { s.getInputStream().bufferedReader().readLine() } catch (_: SocketTimeoutException) { null } ?: return@use
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
                    // A page in the app's colours (light or dark by the browser's setting) instead of bare text.
                    val html = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Paper Play</title>
<style>
:root{color-scheme:light dark;--bg:#f4f2fb;--card:#ffffff;--ink:#1b1b22;--muted:#5d5d6b;--accent:ACCENT;--ring:rgba(37,99,235,.18)}
@media (prefers-color-scheme:dark){:root{--bg:#0d0d12;--card:#17171f;--ink:#ece9f6;--muted:#a19fb2;--ring:rgba(120,140,255,.22)}}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:grid;place-items:center;background:radial-gradient(1200px 600px at 50% -10%,var(--ring),transparent 60%),var(--bg);color:var(--ink);font-family:Nunito,"Segoe UI",system-ui,-apple-system,Roboto,sans-serif}
main{width:min(92vw,440px);padding:40px 32px 34px;border-radius:32px;background:var(--card);text-align:center;box-shadow:0 18px 60px rgba(0,0,0,.28);border:1px solid rgba(127,127,160,.16)}
svg.logo{width:88px;height:88px;filter:drop-shadow(0 8px 18px rgba(37,99,235,.35))}
.mark{display:inline-grid;place-items:center;width:44px;height:44px;margin:-22px 0 0 54px;border-radius:50%;background:var(--accent);color:#fff;font-size:24px;font-weight:800;border:4px solid var(--card)}
h1{margin:14px 0 8px;font-size:26px;font-weight:800;letter-spacing:-.01em}
p{margin:0;font-size:16px;line-height:1.5;color:var(--muted)}
small{display:block;margin-top:26px;font-size:13px;color:var(--muted);opacity:.8}
</style></head><body><main>
<svg class="logo" viewBox="0 0 96 96" aria-hidden="true"><rect width="96" height="96" rx="32" fill="#2563EB"/><circle cx="48" cy="48" r="33" fill="#5B3BB5"/><path d="M35.5 29.8 69.1 48 35.5 66.2 53.3 48Z" fill="#fff"/></svg><br>
<span class="mark" aria-hidden="true">MARK</span>
<h1>TITLE</h1>
<p>You can close this tab and go back to the app.</p>
<small>Paper Play</small>
</main></body></html>"""
                        .replace("ACCENT", if (ok) "#1FA463" else "#D64545")
                        .replace("MARK", if (ok) "\u2713" else "!")
                        .replace("TITLE", if (ok) "Connected to Paper Play" else "Spotify did not connect")
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
