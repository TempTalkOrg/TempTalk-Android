package com.difft.android.p2p.android.server

import com.difft.android.base.log.lumberjack.L
import okio.BufferedSink

/**
 * Keeps the upload connection audibly alive while the receiver decides the verdict.
 *
 * The `/upload` response is withheld until the container has been verified, imported and promoted —
 * minutes of work with nothing on the wire, which a sender cannot tell apart from a dead receiver.
 * `102 Processing` (RFC 2518) is exactly that signal: received, still working, no final response
 * yet. It may be sent repeatedly, a client that does not understand it ignores it, and OkHttp
 * restarts its read deadline on each one.
 *
 * It carries no payload and cannot be made to — OkHttp discards interim responses before the caller
 * sees them, headers included. This is a liveness signal, never a progress channel. Beats only
 * during the verdict, not during the body: the sender is still writing then and may not be reading
 * its side yet.
 */
internal class InterimResponseHeartbeat(
    private val sink: BufferedSink,
    private val intervalMs: Long = INTERVAL_MS,
) {
    private val lock = Object()
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Begin beating. Safe to call once; a second call is ignored. */
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        thread = Thread {
            runCatching {
                while (true) {
                    // The write happens under the lock: stop() takes the same lock, so by the time it
                    // has flipped [running] no beat is mid-write and none can start.
                    synchronized(lock) {
                        if (!running) return@Thread
                        lock.wait(intervalMs)
                        if (!running) return@Thread
                        sink.writeUtf8(INTERIM_RESPONSE)
                        sink.flush()
                    }
                }
            }.onFailure {
                // The peer hung up, or the socket died under us. Nothing to salvage — the upload
                // itself will surface the failure; this thread just stops.
                L.w { "[P2P][Receiver] heartbeat stopped: ${it.message}" }
            }
        }.apply { isDaemon = true; name = "p2p-upload-heartbeat"; start() }
    }

    /**
     * Stop beating. Taking the lock waits out a beat in flight, so the caller owns the sink outright
     * afterwards and the final response cannot interleave with an interim one; the join only lets
     * the thread wind down. A beat stuck on a peer that stopped reading holds the lock until the
     * socket is closed (idle timeout, teardown, peer reset) — the caller's own final write would
     * block on that socket just the same, so nothing is lost by waiting here.
     */
    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            lock.notifyAll()
        }
        runCatching { thread?.join(JOIN_TIMEOUT_MS) }
        thread = null
    }

    companion object {
        /** Header-only by definition — 1xx responses carry no body. */
        private const val INTERIM_RESPONSE = "HTTP/1.1 102 Processing\r\n\r\n"

        /** Comfortably inside the 30s idle deadline both ends run, with room for a missed beat. */
        const val INTERVAL_MS = 10_000L

        private const val JOIN_TIMEOUT_MS = 2_000L
    }
}
