package io.max.paperplay.core.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

enum class TaskKind { Scan, Index, Sync, Covers, Info, Upload, Other }
enum class TaskState { Running, Done, Failed, Stopped }

/** One background job. [total] 0 means unknown; [detail] is a short status line. */
data class TaskItem(
    val id: Long,
    val kind: TaskKind,
    val title: String,
    val detail: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val fraction: Float? = null,
    val startedAt: Long,
    val endedAt: Long = 0,
    val state: TaskState = TaskState.Running,
    val stoppable: Boolean = false,
)

/**
 * What the app is doing in the background, and what it did lately. Any feature can [start] a task,
 * [update] it and [finish] it; the Tasks page reads [running] and [history].
 */
class TaskQueue(private val scope: CoroutineScope, private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val _running = MutableStateFlow<List<TaskItem>>(emptyList())
    private val _history = MutableStateFlow<List<TaskItem>>(emptyList())
    private val stops = HashMap<Long, () -> Unit>()
    private var next = 1L

    val running: StateFlow<List<TaskItem>> = _running
    val history: StateFlow<List<TaskItem>> = _history

    @Synchronized
    fun start(kind: TaskKind, title: String, total: Int = 0, detail: String = "", onStop: (() -> Unit)? = null): Long {
        val id = next++
        if (onStop != null) stops[id] = onStop
        _running.update { it + TaskItem(id, kind, title, detail, 0, total, null, clock(), stoppable = onStop != null) }
        return id
    }

    fun update(id: Long, done: Int? = null, total: Int? = null, detail: String? = null, fraction: Float? = null) {
        _running.update { list ->
            list.map { t ->
                if (t.id != id) t else t.copy(
                    done = done ?: t.done, total = total ?: t.total, detail = detail ?: t.detail, fraction = fraction ?: t.fraction,
                )
            }
        }
    }

    @Synchronized
    fun finish(id: Long, state: TaskState = TaskState.Done, detail: String? = null) {
        val t = _running.value.firstOrNull { it.id == id } ?: return
        stops.remove(id)
        _running.update { l -> l.filter { it.id != id } }
        val ended = t.copy(state = state, endedAt = clock(), detail = detail ?: t.detail, stoppable = false)
        _history.update { (listOf(ended) + it).take(200) }
    }

    /** Asks the job to stop and moves it to the history as stopped. */
    fun stop(id: Long) {
        val s = synchronized(this) { stops[id] }
        runCatching { s?.invoke() }
        finish(id, TaskState.Stopped)
    }

    fun removeFromHistory(id: Long) = _history.update { l -> l.filter { it.id != id } }
    fun clearHistory() { _history.value = emptyList() }

    /** Runs [block] as a task: done when it returns, failed when it throws. */
    suspend fun <T> track(kind: TaskKind, title: String, total: Int = 0, block: suspend (id: Long) -> T): T {
        val id = start(kind, title, total)
        try {
            return block(id).also { finish(id) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            finish(id, TaskState.Stopped); throw e
        } catch (e: Throwable) {
            finish(id, TaskState.Failed, e.message.orEmpty()); throw e
        }
    }

    /** Turns a progress flow into a task: one starts when a value appears and ends when it goes back to null. */
    fun <T : Any> watch(kind: TaskKind, title: String, flow: StateFlow<T?>, detail: (T) -> String, fraction: (T) -> Float? = { null }) {
        scope.launch {
            var id = 0L
            flow.collect { v ->
                if (v == null) {
                    if (id != 0L) finish(id)
                    id = 0L
                } else {
                    if (id == 0L) id = start(kind, title)
                    update(id, detail = detail(v), fraction = fraction(v))
                }
            }
        }
    }

    /** Scan, index and playlist sync progress become tasks. */
    fun watchServices(services: AppServices) {
        watch(TaskKind.Scan, "Reading chats", services.scanner.progress, { it.text }, { it.fraction?.toFloat() })
        watch(TaskKind.Index, "Indexing chats", services.chatIndex.progress, { "${it.chatTitle}: ${it.found} songs" })
        watch(TaskKind.Sync, "Refreshing playlists", services.playlistSync.progress, { it })
        watchEnricher(services.enricher)
    }

    /** Song-info lookups become one stoppable task with a done/total count. */
    fun watchEnricher(enricher: MetadataEnricher) {
        scope.launch {
            var id = 0L
            enricher.progress.collect { p ->
                if (p == null) {
                    if (id != 0L) finish(id, detail = enricher.failure.value)
                    id = 0L
                } else {
                    if (id == 0L) id = start(TaskKind.Info, "Looking up song info", onStop = { enricher.stop() })
                    update(id, done = p.done, total = p.total, detail = p.current, fraction = if (p.total > 0) p.done.toFloat() / p.total else null)
                }
            }
        }
    }
}
