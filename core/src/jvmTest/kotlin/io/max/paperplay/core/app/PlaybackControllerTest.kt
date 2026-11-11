package io.max.paperplay.core.app

import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.Song
import io.max.paperplay.player.PlayerEngine
import io.max.paperplay.player.PlayerState
import io.max.paperplay.player.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakePlayerEngine : PlayerEngine {
    val mutableState = MutableStateFlow(PlayerState.Idle)
    val mutablePositionMs = MutableStateFlow(0L)
    val mutableDurationMs = MutableStateFlow(0L)
    val mutableBufferedMs = MutableStateFlow(0L)
    val mutableCurrentIndex = MutableStateFlow(-1)

    override val state: StateFlow<PlayerState> = mutableState
    override val positionMs: StateFlow<Long> = mutablePositionMs
    override val durationMs: StateFlow<Long> = mutableDurationMs
    override val bufferedMs: StateFlow<Long> = mutableBufferedMs
    override val currentIndex: StateFlow<Int> = mutableCurrentIndex

    var lastQueue: List<QueueItem> = emptyList()
    var lastStartIndex: Int = -1
    var playCalls = 0
    var pauseCalls = 0
    var lastEqualizer: List<Float>? = emptyList()
    var released = false

    override fun setQueue(items: List<QueueItem>, startIndex: Int) {
        lastQueue = items
        lastStartIndex = startIndex
        mutableCurrentIndex.value = if (items.isEmpty()) -1 else startIndex
        mutableState.value = if (items.isEmpty()) PlayerState.Idle else PlayerState.Playing
    }

    override fun play() {
        playCalls++
        if (lastQueue.isNotEmpty()) mutableState.value = PlayerState.Playing
    }

    override fun pause() {
        pauseCalls++
        mutableState.value = PlayerState.Paused
    }

    override fun next() {
        if (mutableCurrentIndex.value + 1 < lastQueue.size) mutableCurrentIndex.value += 1
    }

    override fun previous() {
        if (mutableCurrentIndex.value > 0) mutableCurrentIndex.value -= 1
    }

    override fun seek(positionMs: Long) {
        mutablePositionMs.value = positionMs
    }

    override fun setVolume(volume: Float) = Unit

    override fun setEqualizer(gainsDb: List<Float>?) {
        lastEqualizer = gainsDb
    }

    override fun release() {
        released = true
    }
}

class PlaybackControllerTest {
    private fun song(id: Long) = Song(
        1L, id, "u$id", "r$id", "Title $id", "Artist", "Album", 200, 10_000, "audio/flac", "t$id.flac", id,
        null, null, null, null, null, null, false, false, false, 0, null, 0, null, false, 0, 0, 0, false, 0, 0, 0,
        null, null, null, null,
    )

    private suspend fun await(cond: () -> Boolean) {
        withTimeout(5_000) {
            while (!cond()) delay(10)
        }
    }

    @Test
    fun nowPlayingPropagatesBufferedMsAndHandlesRepeatOne() = runBlocking {
        var now = 1_000L
        val db = LibraryDatabase(libraryDriver())
        val repo = LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
        val s1 = song(1)
        val s2 = song(2)
        db.songsQueries.insertSong(s1)
        db.songsQueries.insertSong(s2)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = FakePlayerEngine()
        val controller = PlaybackController(engine, TrackResolver { null }, repo, scope)
        try {
            controller.setQueue(listOf(s1, s2), startIndex = 0)
            await { engine.lastQueue.size == 2 && controller.nowPlaying.value.song?.unique_id == "u1" }

            engine.mutableDurationMs.value = 200_000L
            engine.mutablePositionMs.value = 45_000L
            engine.mutableBufferedMs.value = 120_000L

            await {
                val np = controller.nowPlaying.value
                np.durationMs == 200_000L && np.positionMs == 45_000L && np.bufferedMs == 120_000L && np.isPlaying
            }

            controller.setRepeat(Repeat.ONE)
            val beforePlays = engine.playCalls
            engine.mutableState.value = PlayerState.Ended
            await { engine.playCalls > beforePlays }

            val gains = listOf(4f, 2f, 0f, -1f, 3f)
            controller.setEqualizer(gains)
            assertEquals(gains, engine.lastEqualizer)
            controller.setEqualizer(null)
            assertNull(engine.lastEqualizer)

            controller.release()
            assertTrue(engine.released)
        } finally {
            scope.cancel()
        }
    }
}
