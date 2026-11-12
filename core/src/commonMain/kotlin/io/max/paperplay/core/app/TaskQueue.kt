package io.max.paperplay.core.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
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
class TaskQueue(
    private val scope: CoroutineScope,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Saved history text (null when none) and where to save it; history survives a restart. */
    private val load: () -> String? = { null },
    private val save: (String) -> Unit = {},
) {
    private val _running = MutableStateFlow<List<TaskItem>>(emptyList())
    private val _history = MutableStateFlow<List<TaskItem>>(restore(load))
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

    private class Patch(var done: Int? = null, var total: Int? = null, var detail: String? = null, var fraction: Float? = null)

    private val lastEmit = HashMap<Long, Long>()
    private val patches = HashMap<Long, Patch>()

    /** Applies at most one change per task each 500 ms; the newest values wait and are applied shortly after. */
    fun update(id: Long, done: Int? = null, total: Int? = null, detail: String? = null, fraction: Float? = null) {
        val wait: Long
        synchronized(this) {
            val p = patches.getOrPut(id) { Patch() }
            done?.let { p.done = it }; total?.let { p.total = it }; detail?.let { p.detail = it }; fraction?.let { p.fraction = it }
            val since = clock() - (lastEmit[id] ?: 0L)
            if (since >= THROTTLE_MS) { flush(id); return }
            wait = THROTTLE_MS - since
            if (patches[id]?.let { scheduled.add(id) } != true) return
        }
        scope.launch {
            kotlinx.coroutines.delay(wait)
            synchronized(this@TaskQueue) { scheduled.remove(id); flush(id) }
        }
    }

    private val scheduled = HashSet<Long>()

    /** Caller holds the lock. */
    private fun flush(id: Long) {
        val p = patches.remove(id) ?: return
        lastEmit[id] = clock()
        _running.update { list ->
            list.map { t ->
                if (t.id != id) t else t.copy(
                    done = p.done ?: t.done, total = p.total ?: t.total, detail = p.detail ?: t.detail, fraction = p.fraction ?: t.fraction,
                )
            }
        }
    }

    @Synchronized
    fun finish(id: Long, state: TaskState = TaskState.Done, detail: String? = null) {
        flush(id)
        lastEmit.remove(id)
        val t = _running.value.firstOrNull { it.id == id } ?: return
        stops.remove(id)
        _running.update { l -> l.filter { it.id != id } }
        val ended = t.copy(state = state, endedAt = clock(), detail = detail ?: t.detail, stoppable = false)
        _history.update { (listOf(ended) + it).take(200) }
        persist()
    }

    private fun persist() {
        runCatching {
            save(buildJsonArray {
                for (t in _history.value.take(100)) add(buildJsonObject {
                    put("k", t.kind.name); put("t", t.title); put("d", t.detail); put("n", t.done); put("o", t.total)
                    put("s", t.startedAt); put("e", t.endedAt); put("st", t.state.name)
                })
            }.toString())
        }
    }

    /** Asks the job to stop and moves it to the history as stopped. */
    fun stop(id: Long) {
        val s = synchronized(this) { stops[id] }
        runCatching { s?.invoke() }
        finish(id, TaskState.Stopped)
    }

    fun removeFromHistory(id: Long) { _history.update { l -> l.filter { it.id != id } }; persist() }
    fun clearHistory() { _history.value = emptyList(); persist() }

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
    fun <T : Any> watch(
        kind: TaskKind, title: String, flow: StateFlow<T?>, detail: (T) -> String, fraction: (T) -> Float? = { null }, onStop: (() -> Unit)? = null,
    ) {
        scope.launch {
            var id = 0L
            flow.collect { v ->
                if (v == null) {
                    if (id != 0L) finish(id)
                    id = 0L
                } else {
                    if (id == 0L) id = start(kind, title, onStop = onStop)
                    update(id, detail = detail(v), fraction = fraction(v))
                }
            }
        }
    }

    /** Scan, index and playlist sync progress become tasks. */
    fun watchServices(services: AppServices) {
        watch(TaskKind.Scan, "Reading chats", services.scanner.progress, { it.text }, { it.fraction?.toFloat() }, onStop = { services.scanner.requestStop() })
        watch(TaskKind.Index, "Indexing chats", services.chatIndex.progress, { "${it.chatTitle}: ${it.found} songs" }, onStop = { services.chatIndex.stopRun() })
        watch(TaskKind.Sync, "Refreshing playlists", services.playlistSync.progress, { it }, onStop = { services.playlistSync.stopRun() })
        watchEnricher(services.enricher)
        watchUploads(services.uploader)
    }

    /** The upload queue becomes one task while files wait or send; stopping it clears the files not yet started. */
    fun watchUploads(uploader: Uploader) {
        scope.launch {
            var id = 0L
            uploader.jobs.collect { jobs ->
                val open = jobs.count { it.status == UploadStatus.Queued || it.status == UploadStatus.Uploading }
                if (open == 0) {
                    if (id != 0L) {
                        val failed = jobs.count { it.status == UploadStatus.Failed }
                        finish(id, detail = if (failed > 0) "$failed failed" else null)
                    }
                    id = 0L
                } else {
                    if (id == 0L) id = start(TaskKind.Upload, "Uploading songs", onStop = { uploader.cancelWaiting() })
                    val done = jobs.count { it.status == UploadStatus.Done }
                    val now = jobs.firstOrNull { it.status == UploadStatus.Uploading }
                    update(id, done = done, total = jobs.size, detail = now?.let { "${it.name} to ${it.chatTitle}" } ?: "Waiting",
                        fraction = now?.progress?.let { (done + it) / jobs.size })
                }
            }
        }
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

private const val THROTTLE_MS = 500L

private fun restore(load: () -> String?): List<TaskItem> = runCatching {
    val arr = Json.parseToJsonElement(load() ?: return emptyList()).jsonArray
    arr.mapIndexed { i, e ->
        val o = e.jsonObject
        fun str(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
        fun num(k: String) = o[k]?.jsonPrimitive?.longOrNull ?: 0L
        TaskItem(
            -(i + 1).toLong(), TaskKind.entries.firstOrNull { it.name == str("k") } ?: TaskKind.Other, str("t"), str("d"),
            num("n").toInt(), num("o").toInt(), null, num("s"), num("e"),
            TaskState.entries.firstOrNull { it.name == str("st") } ?: TaskState.Done,
        )
    }
}.getOrDefault(emptyList())
