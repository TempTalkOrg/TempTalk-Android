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
        
        
        val port = serveThen(interimCount = 3, gapMs = 400)

        val response = client(readTimeoutMs = 700).newCall(post(port)).execute()

        response.use { assertEquals(200, it.code) }
    }

    @Test
    fun `the same wait with no interim responses does trip it`() {
        
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

    



    private fun serveThen(interimCount: Int, gapMs: Long = 50): Int {
        val socket = ServerSocket(0)
        server = socket
        thread(isDaemon = true) {
            runCatching {
                socket.accept().use { client ->
                    val input = client.getInputStream()
                    val out = client.getOutputStream()
                    
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
