package io.max.paperplay.core.integrations

import io.max.paperplay.core.account.AccountApi
import io.max.paperplay.core.account.ChatIndexApi
import io.max.paperplay.core.account.IndexPrefs
import io.max.paperplay.core.account.asSong
import io.max.paperplay.core.app.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The default HTTP client of the platform. */
expect fun defaultHttp(): HttpFetcher

/**
 * Keeps imported Spotify playlists and the chat index fresh in the background.
 *
 * Prefs (library prefs):
 * - `spotify_auto_refresh`: "false" turns the refresh of imported playlists off. Default on.
 * - `index_auto`: "true" refreshes the chat index (newer posts only). Default off.
 * - `index_auto_copy`: "true" copies songs found in the index into the main chat. Default off.
 * - `rate_accountCopy`: copies a minute (default 20).
 */
class PlaylistAutoSync(
    private val library: LibraryRepository,
    private val store: ImportStore,
    private val http: HttpFetcher,
    private val account: AccountApi,
    private val index: ChatIndexApi,
    private val scope: CoroutineScope,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
    private val official: (suspend (String) -> FetchedPlaylist?)? = null,
    private val canRun: () -> Boolean = { true },
) {
    private val _progress = MutableStateFlow<String?>(null)

    /** Short text of what is going on now, or null. */
    val progress: StateFlow<String?> = _progress

    private var jobs: List<Job> = emptyList()

    /** Starts the schedule: shortly after the call, then every few hours. Safe to call more than once. */
    @Synchronized
    fun start() {
        if (jobs.any { it.isActive }) return
        jobs = listOf(
            scope.launch {
                pause(30_000)
                while (isActive) {
                    if (!canRun()) { pause(RETRY); continue }
                    runCatching { refreshPlaylists() }.onFailure { if (it is CancellationException) throw it; log("Spotify refresh: ${it.message}") }
                    runCatching { fetchFromIndex() }.onFailure { if (it is CancellationException) throw it; log("Fetch from index: ${it.message}") }
                    pause(SIX_HOURS)
                }
            },
            scope.launch {
                pause(45_000)
                while (isActive) {
                    if (!canRun()) { pause(RETRY); continue }
                    if (IndexPrefs.auto(library) && account.loggedIn.value) {
                        runCatching { index.refresh() }.onFailure { if (it is CancellationException) throw it; log("Index refresh: ${it.message}") }
                        if (library.pref("index_auto_add") == "true") runCatching { index.addMissingFromMainChat() }.onFailure { if (it is CancellationException) throw it; log("Add from index: ${it.message}") }
                        runCatching { fetchFromIndex() }.onFailure { if (it is CancellationException) throw it; log("Fetch from index: ${it.message}") }
                    }
                    pause(THREE_HOURS)
                }
            },
        )
    }

    /** Ends both loops; [start] begins them again. */
    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /** Re-reads every imported Spotify playlist, so tracks added there show up as missing here. Returns how many. */
    suspend fun refreshPlaylists(): Int {
        if (library.pref("spotify_auto_refresh") == "false") return 0
        val lists = store.livePlaylists().filter { "${it.source_url}".startsWith("https://open.spotify.com") }
        try {
            lists.forEachIndexed { i, p ->
                _progress.value = "Refreshing Spotify playlists ${i + 1} of ${lists.size}"
                runCatching { importPlaylist(store, "${p.source_url}", http, official) }.onFailure { log("${p.name}: ${it.message}") }
            }
        } finally {
            _progress.value = null
        }
        return lists.size
    }

    /**
     * Copies songs that the index has for tracks still missing from imported playlists, only when `index_auto_copy` is
     * on, the account is logged in and the match is a sure one. Paced, at most [MAX_COPIES] a run, no post twice.
     */
    suspend fun fetchFromIndex(): Int {
        if (!IndexPrefs.autoCopy(library) || !account.loggedIn.value) return 0
        val perMinute = (library.pref("rate_accountCopy")?.toIntOrNull() ?: 20).coerceIn(1, 120)
        val gap = 60_000L / perMinute
        val taken = mutableSetOf<Pair<Long, Long>>()
        var copied = 0
        try {
            for (p in store.livePlaylists().filter { "${it.source_url}".startsWith("https://open.spotify.com") }) {
                for (t in store.missing(p.id)) {
                    if (copied >= MAX_COPIES) return copied
                    val best = index.matchesFor(t.title, t.artist, t.duration_sec.toInt()).firstOrNull() ?: continue
                    if (matchKind(best.asSong(), t.title, t.artist, t.duration_sec.toInt()) != MatchKind.Sure) continue
                    if (!taken.add(best.chatId to best.messageId)) continue
                    _progress.value = "Fetching songs from your chats: ${copied + 1}"
                    try {
                        index.copyToMain(best)
                        copied++
                    } catch (e: Exception) {
                        log("Copy failed: ${e.message}")
                    }
                    pause(gap)
                }
            }
        } finally {
            _progress.value = null
        }
        return copied
    }

    private companion object {
        const val RETRY = 15L * 60 * 1000
        const val SIX_HOURS = 6L * 60 * 60 * 1000
        const val THREE_HOURS = 3L * 60 * 60 * 1000
        const val MAX_COPIES = 50
    }
}
