package com.difft.android.p2p.android.server

import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import java.io.EOFException
import java.io.IOException

/**
 * De-chunks an HTTP/1.1 `Transfer-Encoding: chunked` request body into a forward-only [Source] of
 * the payload bytes. [ReceiverHttpServer] uses it for the migration container upload only; the
 * generic file-transfer paths stay on `Content-Length` framing via [LimitedSource].
 *
 * Wire grammar (RFC 7230 §4.1): each chunk is `<hexlen>[;ext]CRLF<data>CRLF`, and the terminating
 * chunk is `0CRLF` followed by optional trailer lines and a final CRLF. Chunk extensions are never
 * produced but are tolerated on read; trailer lines are ignored.
 *
 * Decoded bytes are yielded incrementally, so an arbitrarily large body is never buffered whole.
 * Like [LimitedSource], [close] is a no-op: the connection handler owns the socket lifecycle.
 *
 * @param delegate the buffered socket source positioned at the first chunk-size line.
 */
internal class ChunkedSource(
    private val delegate: BufferedSource,
) : Source {

    /** Bytes still to be emitted from the chunk currently being read; -1 once the 0-chunk is seen. */
    private var bytesRemainingInChunk = NO_CHUNK
    private var finished = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (finished) return -1L

        if (bytesRemainingInChunk == NO_CHUNK || bytesRemainingInChunk == 0L) {
            readChunkSize()
            if (finished) return -1L
        }

        val toRead = minOf(byteCount, bytesRemainingInChunk)
        val read = delegate.read(sink, toRead)
        if (read == -1L) {
            // Socket ended mid-chunk — the body is truncated; surface it as an IO error so the
            // upload fails (the container's own trailing HMAC would also fail, but fail fast here).
            throw EOFException("chunked stream truncated: expected $bytesRemainingInChunk more byte(s)")
        }
        bytesRemainingInChunk -= read
        return read
    }

    /**
     * Read the next `<hexlen>[;ext]CRLF` chunk-size line. On a 0-size chunk, consume the optional
     * trailer lines (ignored, per RFC 7230 §4.1.2) up to the terminating blank line, then mark
     * [finished]. Between data chunks, the per-chunk trailing CRLF is consumed before the next size.
     */
    private fun readChunkSize() {
        // After a fully-read data chunk, swallow its trailing CRLF before the next size line.
        if (bytesRemainingInChunk == 0L) {
            expectCrlf()
        }
        val line = delegate.readUtf8LineStrict()
        val sizeToken = line.substringBefore(';').trim()
        val size = sizeToken.toLongOrNull(radix = 16)
            ?: throw IOException("malformed chunk size: '$line'")
        if (size < 0L) throw IOException("negative chunk size: $size")
        if (size == 0L) {
            consumeTrailerAndEnd()
            finished = true
            bytesRemainingInChunk = NO_CHUNK
        } else {
            bytesRemainingInChunk = size
        }
    }

    /** Read the optional trailer header lines after the last chunk, stopping at the blank line. */
    private fun consumeTrailerAndEnd() {
        while (true) {
            val trailer = delegate.readUtf8LineStrict()
            if (trailer.isEmpty()) break // blank line terminates the chunked body
        }
    }

    /** Consume a CRLF (the data terminator after a chunk's bytes). */
    private fun expectCrlf() {
        val line = delegate.readUtf8LineStrict()
        if (line.isNotEmpty()) throw IOException("expected CRLF after chunk data, got '$line'")
    }

    override fun timeout(): Timeout = delegate.timeout()

    /** No-op: the socket source is closed by the connection handler, not by the engine. */
    override fun close() = Unit

    private companion object {
        /** Sentinel: no chunk-size line has been read yet (distinct from a 0-size last-chunk). */
        const val NO_CHUNK = -1L
    }
}
