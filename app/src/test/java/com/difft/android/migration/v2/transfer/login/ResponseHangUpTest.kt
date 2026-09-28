package com.difft.android.migration.v2.transfer.login

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A pull cancelled mid-download must hang up on the Desktop at once. The reader sits in a blocking
 * socket read on the body, which by itself notices cancellation only when the next frame arrives;
 * closing the body on cancellation is what ends the read and closes the socket.
 */
class ResponseHangUpTest {

    @Test
    fun `cancelling the reader closes the body and ends a blocked socket read`() = runBlocking {
        // A peer that accepts and then never sends: a read on the body blocks until the socket closes.
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val peer = server.accept()
        try {
            val response = Response.Builder()
                .request(Request.Builder().url("https://127.0.0.1/download").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(socket.getInputStream().source().buffer().asResponseBody("application/octet-stream".toMediaType(), -1L))
                .build()
            val reading = CountDownLatch(1)

            val reader = launch(Dispatchers.IO) {
                response.useHangingUpOnCancel { r ->
                    reading.countDown()
                    r.body.source().readByteArray()
                }
            }

            assertTrue("reader never reached the body", reading.await(5, TimeUnit.SECONDS))
            reader.cancel()
            // Without the hang-up the read would block for as long as the peer keeps the socket open.
            withTimeout(5_000) { reader.join() }
            assertTrue("cancellation must surface as cancellation", reader.isCancelled)
        } finally {
            peer.close()
            socket.close()
            server.close()
        }
    }
}
