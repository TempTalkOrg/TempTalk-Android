package com.difft.android.p2p.android.client

import com.difft.android.p2p.engine.io.FileSource
import com.difft.android.p2p.engine.model.ReversePairDevice
import com.difft.android.p2p.engine.model.ReversePairListen
import com.difft.android.p2p.engine.model.ReversePairPrecheck
import com.difft.android.p2p.engine.model.ReversePairRequest
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







class SenderHttpClientCancelTest {

    @Test
    fun `coroutine cancel aborts the in-flight upload via Call cancel`() = runBlocking {
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
            sender.upload(
                sessionId = "s",
                fileId = "f",
                token = "t",
                source = EmptyFileSource,
            )
        }

        
        
        assertTrue(enqueued.await(5, TimeUnit.SECONDS), "upload must enqueue the call")
        job.cancel()

        
        assertTrue(
            cancelInvoked.await(5, TimeUnit.SECONDS),
            "coroutine cancellation must invoke Call.cancel() (the upload cancel bridge)",
        )
        job.join()
        verify { call.cancel() }
    }

    





    @Test
    fun `coroutine cancel hangs up a held reverse-pair via Call cancel`() = runBlocking {
        val enqueued = CountDownLatch(1)
        val cancelInvoked = CountDownLatch(1)

        val call = mockk<Call>(relaxed = true)
        every { call.enqueue(any()) } answers { enqueued.countDown() }
        every { call.cancel() } answers { cancelInvoked.countDown() }
        val okHttp = mockk<OkHttpClient>()
        every { okHttp.newCall(any<Request>()) } returns call

        val pairing = ReversePairClient(ip = "10.0.0.2", port = 53317, fingerprint = "fp")
        pairing.clientOverride = okHttp

        val job = launch(Dispatchers.IO) { pairing.reversePair(reversePairBody(), pin = "123456") }

        assertTrue(enqueued.await(5, TimeUnit.SECONDS), "reverse-pair must enqueue the call")
        job.cancel()
        assertTrue(cancelInvoked.await(5, TimeUnit.SECONDS), "cancelling the wait must hang up on the computer")
        job.join()
        verify { call.cancel() }
    }

    private fun reversePairBody() = ReversePairRequest(
        device = ReversePairDevice(alias = "phone", appVersion = "1.0.0", fingerprint = "fp"),
        listen = ReversePairListen(addresses = listOf("10.0.0.3"), port = 53317),
        pairToken = "token",
        precheck = ReversePairPrecheck(env = "test", formatVersion = "1.0", account = "u_a"),
    )

    
    private object EmptyFileSource : FileSource {
        override val fileName = "empty.bin"
        override val size = 0L
        override val mimeType = "application/octet-stream"
        override val sha256: String? = null
        override fun openRead(): Source = Buffer()
    }
}
