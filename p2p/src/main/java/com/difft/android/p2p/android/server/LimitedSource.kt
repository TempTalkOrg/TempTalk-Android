package com.difft.android.p2p.android.server

import okio.Buffer
import okio.Source
import okio.Timeout







internal class LimitedSource(
    private val delegate: Source,
    private val limit: Long,
) : Source {
    private var remaining = limit

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (remaining == 0L) return -1L
        val toRead = minOf(byteCount, remaining)
        val read = delegate.read(sink, toRead)
        if (read == -1L) {
            remaining = 0L
            return -1L
        }
        remaining -= read
        return read
    }

    override fun timeout(): Timeout = delegate.timeout()

    
    override fun close() = Unit
}
