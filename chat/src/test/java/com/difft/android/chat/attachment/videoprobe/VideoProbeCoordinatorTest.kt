package com.difft.android.chat.attachment.videoprobe

import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.VideoProbeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger






class VideoProbeCoordinatorTest {

    private class FakeStore : VideoProbeStore {
        val rows = ConcurrentHashMap<String, VideoProbeRow>()
        val writes = CopyOnWriteArrayList<String>()

        fun put(key: String, databaseId: Int, width: Int = 0, height: Int = 0, state: Int = VideoProbeState.NONE) {
            rows[key] = VideoProbeRow(databaseId, width, height, state)
        }

        fun stateOf(key: String) = rows.getValue(key).videoProbeState

        override fun resolve(key: String, fileName: String): VideoProbeRow? = rows[key]

        override fun read(databaseId: Int): VideoProbeRow? = rows.values.firstOrNull { it.databaseId == databaseId }

        override fun writeState(databaseId: Int, state: Int) {
            writes += "state:$databaseId:$state"
            update(databaseId) { it.copy(videoProbeState = state) }
        }

        override fun writeFailedIfState(databaseId: Int, expectedState: Int) {
            writes += "failed-if:$databaseId:$expectedState"
            update(databaseId) { if (it.videoProbeState == expectedState) it.copy(videoProbeState = VideoProbeState.FAILED) else it }
        }

        override fun writeSize(databaseId: Int, width: Int, height: Int) {
            writes += "size:$databaseId:${width}x$height"
            update(databaseId) { it.copy(width = width, height = height, videoProbeState = VideoProbeState.NONE) }
        }

        private fun update(databaseId: Int, change: (VideoProbeRow) -> VideoProbeRow) {
            val key = rows.entries.first { it.value.databaseId == databaseId }.key
            rows[key] = change(rows.getValue(key))
        }
    }

    private val store = FakeStore()
    private val probeCalls = CopyOnWriteArrayList<String>()
    private val release = CountDownLatch(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        release.countDown()
        scope.cancel()
    }

    
    private val prober = VideoFrameProber { path ->
        probeCalls += path
        when {
            "block" in path -> {
                release.await(30, TimeUnit.SECONDS)
                VideoSize(1, 1)
            }
            "bad" in path -> null
            "oom" in path -> throw OutOfMemoryError("frame")
            else -> VideoSize(640, 360)
        }
    }

    private fun coordinator(timeoutMs: Long = 5_000L, prober: VideoFrameProber = this.prober) =
        VideoProbeCoordinator(store, prober, scope, timeoutMs)

    private fun attachment(localId: String) = Attachment(
        id = "",
        authorityId = 0L,
        contentType = "video/quicktime",
        key = null,
        size = 0,
        thumbnail = null,
        digest = null,
        fileName = "clip.mov",
        flags = 0,
        width = 0,
        height = 0,
        path = null,
        status = 3,
        localId = localId,
    )

    
    private val subscribed = CompletableDeferred<Unit>()

    @Test
    fun `T2 unsized row is marked in progress, sized, reset to NONE and announced`() = runBlocking {
        store.put("a", databaseId = 1)
        val coordinator = coordinator()
        val result = async(Dispatchers.Default) {
            withTimeout(5_000) { coordinator.results.onSubscription { subscribed.complete(Unit) }.take(1).toList() }
        }
        subscribed.await()

        coordinator.probe("a", "clip.mov", "/files/a/clip.mov")

        assertEquals(listOf("state:1:${VideoProbeState.IN_PROGRESS_1}", "size:1:640x360"), store.writes)
        assertEquals(VideoProbeState.NONE, store.stateOf("a"))
        assertEquals(listOf(VideoProbeResult("a", 1)), result.await())
    }

    @Test
    fun `T3 undecodable row fails and is never probed again`() = runBlocking {
        store.put("a", databaseId = 1)
        val coordinator = coordinator()

        coordinator.probe("a", "clip.mov", "/files/a/bad.mov")
        coordinator.probe("a", "clip.mov", "/files/a/bad.mov")

        assertEquals(VideoProbeState.FAILED, store.stateOf("a"))
        assertEquals(1, probeCalls.size)
    }

    @Test
    fun `out of memory during decode is recorded as FAILED`() = runBlocking {
        store.put("a", databaseId = 1)

        coordinator().probe("a", "clip.mov", "/files/a/oom.mov")

        assertEquals(VideoProbeState.FAILED, store.stateOf("a"))
    }

    @Test
    fun `T13 stale IN_PROGRESS_1 from a dead process is retried as attempt 2`() = runBlocking {
        store.put("a", databaseId = 1, state = VideoProbeState.IN_PROGRESS_1)

        coordinator().probe("a", "clip.mov", "/files/a/clip.mov")

        assertEquals(listOf("state:1:${VideoProbeState.IN_PROGRESS_2}", "size:1:640x360"), store.writes)
    }

    @Test
    fun `T13b stale IN_PROGRESS_2 fails without a third decode`() = runBlocking {
        store.put("a", databaseId = 1, state = VideoProbeState.IN_PROGRESS_2)

        coordinator().probe("a", "clip.mov", "/files/a/clip.mov")

        assertEquals(listOf("failed-if:1:${VideoProbeState.IN_PROGRESS_2}"), store.writes)
        assertEquals(VideoProbeState.FAILED, store.stateOf("a"))
        assertTrue(probeCalls.isEmpty())
    }

    @Test
    fun `T29 an already sized row is re-announced without decoding`() = runBlocking {
        store.put("a", databaseId = 9, width = 640, height = 360)
        val coordinator = coordinator()
        val result = async(Dispatchers.Default) {
            withTimeout(5_000) { coordinator.results.onSubscription { subscribed.complete(Unit) }.take(1).toList() }
        }
        subscribed.await()

        coordinator.probe("a", "clip.mov", "/files/a/clip.mov")

        assertEquals(listOf(VideoProbeResult("a", 9)), result.await())
        assertTrue(probeCalls.isEmpty())
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `an unresolvable row is skipped without decode, write or result`() = runBlocking {
        val coordinator = coordinator()
        val emitted = AtomicInteger()
        val collector = async(Dispatchers.Default) {
            coordinator.results.onSubscription { subscribed.complete(Unit) }.collect { emitted.incrementAndGet() }
        }
        subscribed.await()

        coordinator.probe("missing", "clip.mov", "/files/missing/clip.mov")
        Thread.sleep(50)

        collector.cancel()
        assertEquals(0, emitted.get())
        assertTrue(probeCalls.isEmpty())
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `T11 a timed-out decode fails only its own row and frees the permit for the queued one`() = runBlocking {
        store.put("slow", databaseId = 1)
        store.put("next", databaseId = 2)
        val coordinator = coordinator(timeoutMs = 300)
        val results = async(Dispatchers.Default) {
            withTimeout(10_000) { coordinator.results.onSubscription { subscribed.complete(Unit) }.take(2).toList() }
        }
        subscribed.await()

        coordinator.request(attachment("slow"), "/files/slow/block.mov")
        coordinator.request(attachment("next"), "/files/next/clip.mov")

        assertEquals(setOf(VideoProbeResult("slow", 1), VideoProbeResult("next", 2)), results.await().toSet())
        assertEquals(VideoProbeState.FAILED, store.stateOf("slow"))
        assertEquals(VideoProbeRow(2, 640, 360, VideoProbeState.NONE), store.rows.getValue("next"))
    }

    @Test
    fun `T12 two abandoned decodes still running stop probing for the process`() = runBlocking {
        store.put("a", databaseId = 1)
        store.put("b", databaseId = 2)
        store.put("c", databaseId = 3)
        val coordinator = coordinator(timeoutMs = 100)

        coordinator.probe("a", "clip.mov", "/files/a/block.mov")
        coordinator.probe("b", "clip.mov", "/files/b/block.mov")
        coordinator.probe("c", "clip.mov", "/files/c/clip.mov")

        assertEquals(2, probeCalls.size)
        assertEquals(VideoProbeState.FAILED, store.stateOf("a"))
        assertEquals(VideoProbeState.FAILED, store.stateOf("b"))
        
        assertEquals(VideoProbeState.NONE, store.stateOf("c"))
    }

    @Test
    fun `T14 decodes never overlap and repeated binds of one bubble collapse into one request`() = runBlocking {
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val countingProber = VideoFrameProber { path ->
            probeCalls += path
            maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            Thread.sleep(20)
            running.decrementAndGet()
            VideoSize(640, 360)
        }
        val keys = (1..10).map { "k$it" }
        keys.forEachIndexed { i, key -> store.put(key, databaseId = i + 1) }
        val coordinator = coordinator(prober = countingProber)
        val results = async(Dispatchers.Default) {
            withTimeout(10_000) { coordinator.results.onSubscription { subscribed.complete(Unit) }.take(keys.size).toList() }
        }
        subscribed.await()

        keys.forEach { key -> repeat(5) { coordinator.request(attachment(key), "/files/$key/clip.mov") } }

        assertEquals(keys.toSet(), results.await().map { it.attachmentKey }.toSet())
        assertEquals(1, maxRunning.get())
        assertEquals(keys.size, probeCalls.size)
    }

    @Test
    fun `bubble gate needs a readable whitelisted video without a size`() {
        val unsized = attachment("a")
        assertTrue(VideoProbeCoordinator.needsProbe(unsized, isFileReadable = true))
        assertFalse(VideoProbeCoordinator.needsProbe(unsized, isFileReadable = false))
        assertFalse(VideoProbeCoordinator.needsProbe(unsized.copy(width = 640, height = 360), isFileReadable = true))
        assertTrue(VideoProbeCoordinator.needsProbe(unsized.copy(width = 640), isFileReadable = true))
        assertFalse(VideoProbeCoordinator.needsProbe(unsized.copy(contentType = "video/webm"), isFileReadable = true))
        
        assertTrue(VideoProbeCoordinator.needsProbe(unsized.copy(videoProbeState = VideoProbeState.FAILED), isFileReadable = true))
    }

    @Test
    fun `a download reset during the decode keeps the failed verdict off the new file`() = runBlocking {
        store.put("a", databaseId = 1)
        val resettingProber = VideoFrameProber { path ->
            probeCalls += path
            
            store.put("a", databaseId = 1, state = VideoProbeState.NONE)
            null
        }
        val coordinator = coordinator(prober = resettingProber)

        coordinator.probe("a", "clip.mov", "/files/a/clip.mov")

        assertEquals(VideoProbeState.NONE, store.stateOf("a"))
    }
}
