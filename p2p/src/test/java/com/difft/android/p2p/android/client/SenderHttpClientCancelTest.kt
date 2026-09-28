package com.difft.android.p2p.android.client

import com.difft.android.p2p.engine.io.FileSource
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.MigrationHandshake
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.Source
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * I5 F4 — deterministic test for the coroutine-cancel → `Call.cancel()` bridge every [SenderHttpClient]
 * call goes through (`Call.await()`), pinned on [SenderHttpClient.upload] and [SenderHttpClient.prepareDownload]
 * (`suspendCancellableCoroutine { cont.invokeOnCancellation { call.cancel() } ; call.enqueue(...) }`).
 *
 * The bridge exists because a cancelled coroutine (the FGS tearing down on user-cancel, I5 §5 sender
 * row) must ABORT the in-flight upload. The removed loopback test was flaky because it depended on
 * real-socket timing; this test is socket-free and timing-deterministic:
 *   - inject a mocked [OkHttpClient] (via the `clientOverride` test seam) whose `newCall` returns a
 *     mocked [Call] whose `enqueue` NEVER invokes the callback — i.e. the upload is "in flight forever",
 *   - launch `upload(...)` on a real coroutine, wait until `enqueue` is reached (the coroutine is now
 *     suspended in `suspendCancellableCoroutine`),
 *   - cancel the job → `invokeOnCancellation` fires immediately (while suspended),
 *   - assert `call.cancel()` was invoked (the bridge fired) within a bound.
 *
 * This also pins the FIX for the latent defect F4 uncovered: the previous bridge used the DEFAULT
 * `invokeOnCompletion` (onCancelling=false) over a BLOCKING `execute()`, which fires only AFTER the body
 * returns — so `call.cancel()` ran too late to abort a truly in-flight upload (a no-op). The
 * enqueue + invokeOnCancellation form fires the cancel the moment cancellation begins.
 */
class SenderHttpClientCancelTest {

    @Test
    fun `coroutine cancel aborts the in-flight upload via Call cancel`() = runBlocking {
        val enqueued = CountDownLatch(1)
        val cancelInvoked = CountDownLatch(1)

        val call = mockk<Call>(relaxed = true)
        // enqueue() accepts the callback but NEVER completes it — models an upload stuck in flight, so
        // the coroutine stays suspended inside suspendCancellableCoroutine until it is cancelled.
        every { call.enqueue(any()) } answers { enqueued.countDown() }
        // Record the cancel bridge firing so the test can await it deterministically.
        every { call.cancel() } answers { cancelInvoked.countDown() }

        val okHttp = mockk<OkHttpClient>()
        every { okHttp.newCall(any<Request>()) } returns call

        val sender = SenderHttpClient(ip = "10.0.0.2", port = 53317, fingerprint = "fp")
        sender.clientOverride = okHttp

        val job = launch(Dispatchers.IO) {
            sender.upload(
                sessionId = "s",
                fileId = "f",
                token = "t",
                source = EmptyFileSource,
            )
        }

        // Wait until enqueue() is reached (coroutine suspended in suspendCancellableCoroutine), THEN
        // cancel — guarantees invokeOnCancellation is registered on a live, suspended continuation.
        assertTrue(enqueued.await(5, TimeUnit.SECONDS), "upload must enqueue the call")
        job.cancel()

        // invokeOnCancellation must fire Call.cancel() the moment cancellation begins — the F4 contract.
        assertTrue(
            cancelInvoked.await(5, TimeUnit.SECONDS),
            "coroutine cancellation must invoke Call.cancel() (the upload cancel bridge)",
        )
        job.join()
        verify { call.cancel() }
    }

    /**
     * The displayer holds `prepare-download` open until the person at the Desktop confirms, and learns
     * that the phone left ONLY from that connection closing. A cancel on the waiting page must
     * therefore reach `Call.cancel()` at once — a blocking `execute()` would keep the connection up
     * until the 15-minute read deadline, with the Desktop still showing the phone as waiting.
     */
    @Test
    fun `coroutine cancel hangs up a held prepare-download via Call cancel`() = runBlocking {
        val enqueued = CountDownLatch(1)
        val cancelInvoked = CountDownLatch(1)

        val call = mockk<Call>(relaxed = true)
        every { call.enqueue(any()) } answers { enqueued.countDown() }
        every { call.cancel() } answers { cancelInvoked.countDown() }
        val okHttp = mockk<OkHttpClient>()
        every { okHttp.newCall(any<Request>()) } returns call

        val sender = SenderHttpClient(ip = "10.0.0.2", port = 53317, fingerprint = "fp")
        sender.clientOverride = okHttp

        val job = launch(Dispatchers.IO) {
            sender.prepareDownload(
                DeviceInfo(alias = "phone", fingerprint = "fp"),
                pin = "123456",
                handshake = MigrationHandshake(
                    sourceEnvironment = "test", sourceAccount = "u_a", sourceAppVersion = "1.0.0", sourceFormatVersion = "1.0",
                ),
            )
        }

        assertTrue(enqueued.await(5, TimeUnit.SECONDS), "prepare-download must enqueue the call")
        job.cancel()
        assertTrue(cancelInvoked.await(5, TimeUnit.SECONDS), "cancelling the wait must hang up on the displayer")
        job.join()
        verify { call.cancel() }
    }

    /** Minimal known-length source — `writeTo` is never reached (the call never completes). */
    private object EmptyFileSource : FileSource {
        override val fileName = "empty.bin"
        override val size = 0L
        override val mimeType = "application/octet-stream"
        override val sha256: String? = null
        override fun openRead(): Source = Buffer()
    }
}
