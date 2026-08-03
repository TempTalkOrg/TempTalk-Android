package com.difft.android.chat.common

import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [readBoundedBytes] — the shared 10 MB avatar-download failsafe.
 */
class AvatarDownloadTest {

    /** Body with a known Content-Length (buffered response). */
    private fun knownLengthBody(bytes: ByteArray): ResponseBody =
        bytes.toResponseBody(null)

    /** Body without Content-Length (chunked response, contentLength() == -1). */
    private fun chunkedBody(bytes: ByteArray): ResponseBody = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = Buffer().apply { write(bytes) }
    }

    @Test
    fun `under limit returns full bytes`() {
        val data = ByteArray(1024) { it.toByte() }
        val result = knownLengthBody(data).readBoundedBytes(maxBytes = 4096)
        assertArrayEquals(data, result)
    }

    @Test
    fun `over limit via content length throws`() {
        val data = ByteArray(4096)
        assertThrows(IOException::class.java) {
            knownLengthBody(data).readBoundedBytes(maxBytes = 1024)
        }
    }

    @Test
    fun `missing content length is still bounded by stream read`() {
        val data = ByteArray(4096)
        val body = chunkedBody(data)
        // Precondition: no declared length, so the header pre-check cannot catch it.
        check(body.contentLength() == -1L)
        assertThrows(IOException::class.java) {
            body.readBoundedBytes(maxBytes = 1024)
        }
    }

    @Test
    fun `missing content length under limit returns bytes`() {
        val data = ByteArray(512) { (it % 7).toByte() }
        val result = chunkedBody(data).readBoundedBytes(maxBytes = 1024)
        assertArrayEquals(data, result)
    }
}
