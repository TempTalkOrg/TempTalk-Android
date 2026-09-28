package com.difft.android.p2p.android.server

import okio.Buffer
import okio.Source
import okio.Timeout

/**
 * Wraps a [Source] to expose exactly [limit] bytes, then signals EOF. The upload body is
 * `Content-Length`-framed; without this bound the engine's `writeAll` would block waiting for a
 * socket EOF that never comes on a framed request. Does not close the delegate — the connection
 * handler owns the socket lifecycle.
 */
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

    /** No-op: the socket source is closed by the connection handler, not by the engine. */
    override fun close() = Unit
}
