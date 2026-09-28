package com.difft.android.migration.v2.transfer

import com.difft.android.p2p.engine.model.FileMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for the APPROXIMATE receive-progress added to [P2pContainerReceiveSink]: the write side
 * counts the body bytes pushed into the pipe and reports `bytesWritten / meta.size` (the sender's
 * advisory estimate) via the `onProgress` callback, throttled, always coerced into `[0,1]`. The consume
 * side is driven to EOF on a background scope so the bridge completes.
 *
 * No sockets: writes go straight into the sink returned by `openWrite`, then `finalizeFile` closes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class P2pContainerReceiveSinkProgressTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun meta(size: Long) =
        FileMetadata(id = "f1", fileName = "c.ttmig", size = size, fileType = "application/octet-stream")

    @Test
    fun `reports progress proportional to bytes written against the advertised estimate`() = runBlocking {
        val reported = CopyOnWriteArrayList<Float>()
        val sink = P2pContainerReceiveSink<Long>(
            scope = scope,
            onProgress = { reported.add(it) },
        ) { input -> input.readBytes().size.toLong() }

        // Estimate = 1000 bytes; push 1000 bytes in 100-byte chunks → progress crosses 0.1 steps.
        val writer = sink.openWrite(meta(1000))
        repeat(10) {
            writer.write(Buffer().apply { write(ByteArray(100) { 7 }) }, 100)
        }
        sink.finalizeFile(meta(1000), null)

        assertTrue(reported.isNotEmpty(), "progress must be reported for a positive estimate")
        // Monotonic non-decreasing, all in [0,1], final reaches ~1.0.
        assertTrue(reported.all { it in 0f..1f }, "all progress values coerced into [0,1]")
        assertEquals(reported, reported.sorted(), "progress is monotonic non-decreasing")
        assertEquals(1f, reported.last(), 0.001f)
    }

    @Test
    fun `over-shooting the estimate never exceeds 1_0`() = runBlocking {
        val reported = CopyOnWriteArrayList<Float>()
        val sink = P2pContainerReceiveSink<Long>(
            scope = scope,
            onProgress = { reported.add(it) },
        ) { input -> input.readBytes().size.toLong() }

        // Estimate UNDER-shoots (100) but we push 500 → ratio must clamp to 1.0, never above.
        val writer = sink.openWrite(meta(100))
        repeat(5) {
            writer.write(Buffer().apply { write(ByteArray(100) { 1 }) }, 100)
        }
        sink.finalizeFile(meta(100), null)

        assertTrue(reported.all { it <= 1f }, "an under-estimate must clamp progress at 1.0, never above")
        assertEquals(1f, reported.last(), 0.001f)
    }

    @Test
    fun `no estimate (size 0) emits no progress (indeterminate UI)`() = runBlocking {
        val reported = CopyOnWriteArrayList<Float>()
        val sink = P2pContainerReceiveSink<Long>(
            scope = scope,
            onProgress = { reported.add(it) },
        ) { input -> input.readBytes().size.toLong() }

        val writer = sink.openWrite(meta(0))
        repeat(3) {
            writer.write(Buffer().apply { write(ByteArray(100) { 2 }) }, 100)
        }
        sink.finalizeFile(meta(0), null)

        assertTrue(reported.isEmpty(), "size<=0 → no progress emitted (UI stays indeterminate)")
    }

    /**
     * The receive bar reaches 100% while the import tail and the promote still run, so the screen has
     * to move on at body-complete rather than at the verdict. The consumer here withholds its verdict
     * until the callback has fired: a signal raised after the await would time out, not pass.
     */
    @Test
    fun `body-complete fires exactly once, before the consumer's verdict is awaited`() = runBlocking {
        val bodyComplete = CountDownLatch(1)
        val calls = AtomicInteger()
        val sink = P2pContainerReceiveSink<Long>(
            scope = scope,
            onBodyComplete = {
                calls.incrementAndGet()
                bodyComplete.countDown()
            },
        ) { input ->
            check(bodyComplete.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "onBodyComplete never fired" }
            input.readBytes().size.toLong()
        }

        val writer = sink.openWrite(meta(100))
        writer.write(Buffer().apply { write(ByteArray(100) { 3 }) }, 100)
        val outcome = sink.finalizeFile(meta(100), null)

        assertTrue(outcome.isSuccess, "the verdict must resolve once the body-complete signal is in")
        assertEquals(1, calls.get(), "body-complete fires exactly once")
    }

    /** A UI callback that throws must not turn a fully received body into a non-200. */
    @Test
    fun `a failing body-complete callback leaves the verdict intact`() = runBlocking {
        val sink = P2pContainerReceiveSink<Long>(
            scope = scope,
            onBodyComplete = { error("callback boom") },
        ) { input -> input.readBytes().size.toLong() }

        val writer = sink.openWrite(meta(100))
        writer.write(Buffer().apply { write(ByteArray(100) { 4 }) }, 100)

        assertTrue(sink.finalizeFile(meta(100), null).isSuccess)
    }

    private companion object {
        const val CALLBACK_TIMEOUT_SECONDS = 5L
    }
}
