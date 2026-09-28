package com.difft.android.p2p.android.client

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Can the receiver keep the sender waiting through a long import by emitting interim `102 Processing`
 * responses, instead of the sender having to hold one enormous read deadline?
 *
 * The receiver goes silent for minutes while it verifies, imports and promotes, and the only reason
 * that needs a special deadline at all is that nothing is on the wire during it. If interim responses
 * pass through, the ordinary idle deadline covers the import too and all three platforms get to
 * remember ONE rule — no bytes for N seconds means the peer is gone — instead of that rule plus a
 * separate "waiting for the verdict" exception.
 *
 * RFC 2518's `102 Processing` says exactly this: received, still working, no final response yet. It
 * may be sent repeatedly, and a client that does not understand it is supposed to ignore it. This
 * pins whether OkHttp — the Android sender's stack — actually behaves that way.
 *
 * Plain HTTP on purpose: 1xx handling sits in the HTTP layer, so TLS would only add noise.
 *
 * NOTE: this covers the ANDROID sender only. iOS/macOS must run the same check against URLSession
 * before the contract can rely on it.
 */
class InterimResponsePassthroughTest {

    private var server: ServerSocket? = null

    @After fun tearDown() { server?.close() }

    @Test
    fun `interim 102 responses reach OkHttp as one final response`() {
        val port = serveThen(interimCount = 3)

        val response = client().newCall(post(port)).execute()

        response.use {
            assertEquals("the final status must win, not the interim one", 200, it.code)
            assertEquals("verdict", it.body?.string())
        }
    }

    @Test
    fun `a slow verdict punctuated by interim responses does not trip the read deadline`() {
        // Three 400ms gaps, each ended by a 102 — 1.2s of total silence-free waiting under a 700ms
        // deadline. This only passes if the interim bytes restart the clock.
        val port = serveThen(interimCount = 3, gapMs = 400)

        val response = client(readTimeoutMs = 700).newCall(post(port)).execute()

        response.use { assertEquals(200, it.code) }
    }

    @Test
    fun `the same wait with no interim responses does trip it`() {
        // The control. Without it, the test above would also pass if the deadline were never applied.
        val port = serveThen(interimCount = 0, gapMs = 1200)

        assertThrows(IOException::class.java) {
            client(readTimeoutMs = 700).newCall(post(port)).execute()
        }
    }

    private fun client(readTimeoutMs: Long = 5_000) = OkHttpClient.Builder()
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private fun post(port: Int) = Request.Builder()
        .url("http://127.0.0.1:$port/api/localsend/v2/upload")
        .post("body".toByteArray().toRequestBody())
        .build()

    /**
     * A one-shot receiver that reads the request, then stalls for [gapMs] before each of
     * [interimCount] interim responses, and finally answers the verdict.
     */
    private fun serveThen(interimCount: Int, gapMs: Long = 50): Int {
        val socket = ServerSocket(0)
        server = socket
        thread(isDaemon = true) {
            runCatching {
                socket.accept().use { client ->
                    val input = client.getInputStream()
                    val out = client.getOutputStream()
                    // Drain the head; the body follows it and we do not care about either.
                    val buf = ByteArray(4096)
                    input.read(buf)
                    repeat(interimCount) {
                        Thread.sleep(gapMs)
                        out.write("HTTP/1.1 102 Processing\r\n\r\n".toByteArray())
                        out.flush()
                    }
                    if (interimCount == 0) Thread.sleep(gapMs)
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nverdict")
                            .toByteArray()
                    )
                    out.flush()
                }
            }.onFailure { if (it !is SocketException) throw it }
        }
        return socket.localPort
    }
}
