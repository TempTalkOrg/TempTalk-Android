package com.difft.android.p2p.android.server

import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import java.io.EOFException
import java.io.IOException















internal class ChunkedSource(
    private val delegate: BufferedSource,
) : Source {

    
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
            
            
            throw EOFException("chunked stream truncated: expected $bytesRemainingInChunk more byte(s)")
        }
        bytesRemainingInChunk -= read
        return read
    }

    




    private fun readChunkSize() {
        
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

    
    private fun consumeTrailerAndEnd() {
        while (true) {
            val trailer = delegate.readUtf8LineStrict()
            if (trailer.isEmpty()) break 
        }
    }

    
    private fun expectCrlf() {
        val line = delegate.readUtf8LineStrict()
        if (line.isNotEmpty()) throw IOException("expected CRLF after chunk data, got '$line'")
    }

    override fun timeout(): Timeout = delegate.timeout()

    
    override fun close() = Unit

    private companion object {
        
        const val NO_CHUNK = -1L
    }
}
