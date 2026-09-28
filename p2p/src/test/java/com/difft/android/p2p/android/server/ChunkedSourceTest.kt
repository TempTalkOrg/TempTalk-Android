package com.difft.android.p2p.android.server

import okio.Buffer
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import kotlin.random.Random

/**
 * I2 chunked de-framing unit test. [ChunkedSource] de-chunks an HTTP/1.1 `Transfer-Encoding: chunked`
 * body back into the original payload bytes. Covers single-chunk, multi-chunk, chunk-extensions,
 * trailers, the empty body, and the 1-byte-per-segment fragmentation stress (a dribbling peer) — the
 * exact framing the migration container upload relies on (integration-design I2 §2 receiver).
 */
class ChunkedSourceTest {

    /** Encode [payload] into chunked framing with the given [chunkSizes] (must sum to payload size). */
    private fun chunked(payload: ByteArray, chunkSizes: List<Int>): ByteArray {
        require(chunkSizes.sum() == payload.size) { "chunk sizes must sum to payload size" }
        val out = Buffer()
        var off = 0
        for (size in chunkSizes) {
            out.writeUtf8(size.toString(16)).writeUtf8("\r\n")
            out.write(payload, off, size)
            out.writeUtf8("\r\n")
            off += size
        }
        out.writeUtf8("0\r\n\r\n") // last-chunk + (empty) trailer + terminating CRLF
        return out.readByteArray()
    }

    /** Read the whole de-chunked stream into a byte array (drains until EOF). */
    private fun deChunk(wire: ByteArray): ByteArray {
        val source = Buffer().apply { write(wire) }
        val out = Buffer()
        ChunkedSource(source).buffer().use { out.writeAll(it) }
        return out.readByteArray()
    }

    @Test
    fun `single chunk round-trips`() {
        val payload = "the quick brown fox".toByteArray()
        assertArrayEquals(payload, deChunk(chunked(payload, listOf(payload.size))))
    }

    @Test
    fun `multi-chunk round-trips to the original bytes`() {
        val payload = Random(42).nextBytes(10_000)
        // Uneven chunk boundaries (must sum to 10_000).
        val sizes = listOf(1, 100, 4096, 4096, 1707)
        assertArrayEquals(payload, deChunk(chunked(payload, sizes)))
    }

    @Test
    fun `empty body (only the terminating zero chunk) yields zero bytes`() {
        val wire = Buffer().writeUtf8("0\r\n\r\n").readByteArray()
        assertArrayEquals(ByteArray(0), deChunk(wire))
    }

    @Test
    fun `chunk extensions and trailers are tolerated and ignored`() {
        val payload = "payload-with-ext".toByteArray()
        val wire = Buffer().apply {
            writeUtf8(payload.size.toString(16)).writeUtf8(";ext=value\r\n") // chunk-ext after size
            write(payload)
            writeUtf8("\r\n")
            writeUtf8("0\r\n")
            writeUtf8("X-Checksum: abc123\r\n") // trailer header — ignored
            writeUtf8("\r\n")
        }.readByteArray()
        assertArrayEquals(payload, deChunk(wire))
    }

    @Test
    fun `1-byte-per-segment fragmentation stress still re-assembles exactly`() {
        // The dribbling-peer stress: the buffered source delivers ONE byte at a time, so every
        // readUtf8LineStrict / read must block-and-accumulate across segments. ChunkedSource must
        // re-assemble the original bytes regardless of how the wire is fragmented.
        val payload = Random(7).nextBytes(3000)
        val wire = chunked(payload, listOf(500, 500, 500, 500, 1000))
        val dribbling = object : okio.ForwardingSource(Buffer().apply { write(wire) }) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, 1L) // cap at 1
        }
        val out = Buffer()
        ChunkedSource(dribbling.buffer()).buffer().use { out.writeAll(it) }
        assertArrayEquals(payload, out.readByteArray())
    }

    @Test
    fun `de-chunked size equals the original payload size for a large body`() {
        val payload = Random(99).nextBytes(1_000_000) // 1 MB across many chunks
        val sizes = (0 until 1_000_000 step 8192).map { minOf(8192, 1_000_000 - it) }
        val result = deChunk(chunked(payload, sizes))
        assertEquals(payload.size, result.size)
        assertArrayEquals(payload, result)
    }

    @Test
    fun `malformed chunk size throws`() {
        val wire = Buffer().writeUtf8("zz\r\ndata\r\n0\r\n\r\n").readByteArray()
        assertThrows(IOException::class.java) { deChunk(wire) }
    }

    @Test
    fun `truncated chunk data (socket ends mid-chunk) throws`() {
        // Declare 10 bytes but provide only 3, then the stream ends — must surface as truncation.
        val wire = Buffer().writeUtf8("a\r\nabc").readByteArray() // 0xa = 10 declared, 3 given, no end
        assertThrows(EOFException::class.java) { deChunk(wire) }
    }
}
