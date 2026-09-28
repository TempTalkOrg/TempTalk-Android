package com.difft.android.p2p.engine.upload

import com.difft.android.base.log.lumberjack.L
import okio.Buffer
import okio.BufferedSink
import okio.Source

/**
 * Measures an inbound transfer while it streams, so a "the transfer is slow" report can be assigned
 * to a side from the receiver's log alone.
 *
 * The receiver cannot see inside the sender, but it can tell apart the two shapes slowness takes:
 *
 *  - **The link is slow.** Bytes arrive continuously at a low rate: `stalled` stays a small share of
 *    the wall time and the per-window rates are flat. Combined with the peer address logged at accept
 *    time this also settles whether the traffic even took the LAN (a peer outside the local subnet
 *    means it was routed through a tunnel and left the network).
 *  - **The sender cannot keep up.** Each read returns quickly but the socket then sits idle: `stalled`
 *    dominates the wall time and `maxStallMs` is large. The bytes that do arrive move fast.
 *
 * `avgRead` separates a third case: a sender that writes in tiny pieces pays per-write overhead and
 * shows a low rate with neither a slow link nor long stalls.
 *
 * Sampling is time-based rather than per-read so the log stays short on a fast transfer and still
 * describes the shape of a slow one.
 */
class TransferMeter(private val label: String) {

    private var bytes = 0L
    private var reads = 0L
    private var stalledMs = 0L
    private var maxStallMs = 0L
    private var windowBytes = 0L
    private var windowStartMs = 0L
    private var startMs = 0L

    /** Drain [source] into [sink], measuring as it goes. Returns the total byte count. */
    fun pump(source: Source, sink: BufferedSink): Long {
        val buffer = Buffer()
        startMs = System.currentTimeMillis()
        windowStartMs = startMs
        while (true) {
            val readStart = System.currentTimeMillis()
            val n = source.read(buffer, SEGMENT_BYTES)
            val waited = System.currentTimeMillis() - readStart
            if (n == -1L) break

            stalledMs += waited
            if (waited > maxStallMs) maxStallMs = waited
            reads++
            bytes += n
            windowBytes += n
            sink.write(buffer, n)

            val now = System.currentTimeMillis()
            if (now - windowStartMs >= WINDOW_MS) {
                val kbps = windowBytes * 1000 / (now - windowStartMs) / 1024
                L.i { "[P2P][Meter][$label] window ${kbps}KB/s total=${bytes / 1024}KB" }
                windowBytes = 0
                windowStartMs = now
            }
        }
        summarize()
        return bytes
    }

    private fun summarize() {
        val wallMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
        val kbps = bytes * 1000 / wallMs / 1024
        val stalledPct = stalledMs * 100 / wallMs
        val avgRead = if (reads > 0) bytes / reads else 0
        L.i {
            "[P2P][Meter][$label] done bytes=$bytes wallMs=$wallMs avg=${kbps}KB/s " +
                "stalled=${stalledPct}% maxStallMs=$maxStallMs reads=$reads avgRead=${avgRead}B"
        }
    }

    private companion object {
        /** Okio's own segment size — reading in its native unit keeps the measurement honest. */
        const val SEGMENT_BYTES = 8192L

        /** Rate-sample window. Long enough to stay quiet on a fast transfer. */
        const val WINDOW_MS = 3000L
    }
}
