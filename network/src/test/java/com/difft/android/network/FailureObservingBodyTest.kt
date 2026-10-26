package com.difft.android.network

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [FailureObservingBody]: a failure while the body is read is reported, the bytes before it are
 * passed through untouched, and a failure caused by the caller closing the body is not a host
 * signal (#1396).
 */
class FailureObservingBodyTest {

    private fun bodyOf(source: Source): ResponseBody = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = source.buffer()
    }

    /** Yields "abc" once, then fails. */
    private class FailsAfterFirstRead(val error: IOException) : Source {
        private var reads = 0
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (reads++ > 0) throw error
            sink.writeUtf8("abc")
            return 3
        }

        override fun timeout(): Timeout = Timeout.NONE
        override fun close() = Unit
    }

    @Test
    fun `a read failure is reported once with the original exception and rethrown`() {
        val boom = IOException("boom")
        val reported = mutableListOf<IOException>()
        val body = FailureObservingBody(bodyOf(FailsAfterFirstRead(boom)), reported::add)

        val thrown = assertFailsWith<IOException> { body.string() }

        assertSame(boom, thrown)
        assertEquals(listOf(boom), reported)
    }

    @Test
    fun `bytes before the failure and the body metadata pass through`() {
        val body = FailureObservingBody(bodyOf(FailsAfterFirstRead(IOException("boom")))) { }

        val source = body.source()
        source.require(3)

        assertEquals("abc", source.readUtf8(3))
        assertEquals(-1L, body.contentLength())
    }

    @Test
    fun `a healthy body reports nothing`() {
        val reported = mutableListOf<IOException>()
        val healthy = Buffer().writeUtf8("hello")
        val body = FailureObservingBody(bodyOf(healthy), reported::add)

        assertEquals("hello", body.string())
        assertTrue(reported.isEmpty())
    }

    // A reader blocked in read() when the caller closes the body fails with "closed": not the host's fault.
    @Test
    fun `a failure caused by the caller closing the body is not reported`() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                blocked.countDown()
                release.await(5, TimeUnit.SECONDS)
                throw IOException("Socket closed")
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = release.countDown()
        }
        val reported = mutableListOf<IOException>()
        val body = FailureObservingBody(bodyOf(source), reported::add)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reader = executor.submit<Throwable?> { runCatching { body.source().readUtf8() }.exceptionOrNull() }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))

            body.close()

            assertTrue(reader.get(5, TimeUnit.SECONDS) is IOException)
            assertTrue(reported.isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }
}
