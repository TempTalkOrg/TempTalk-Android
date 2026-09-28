package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps a receiver listener alive past its transfer, so the sender can still ask `GET /status` how
 * the session ended — the answer that separates "the import succeeded" from "nobody knows". The
 * listener goes away when the window runs out, when a new receiver needs the port, or when the
 * process ends, whichever comes first; the window is an upper bound, never a promise. At most one
 * listener lingers at a time, and keeping a second one stops the first.
 */
class ReceiverLinger(
    private val scope: CoroutineScope,
    private val windowMs: Long = WINDOW_MS,
) {
    /** What a lingering listener must be able to do. */
    interface Listener {
        /** Keep answering `/status` and `/cancel`; refuse to start a new session. */
        fun closeToNewSessions()

        /** Tear the listener down. Idempotent. */
        fun stop()
    }

    private val lock = Any()
    private var current: Listener? = null
    private var expiry: Job? = null

    /** Keep [listener] answering for up to the window. Any listener kept before it is stopped now. */
    fun keep(listener: Listener) {
        val previous: Listener?
        synchronized(lock) {
            previous = current
            expiry?.cancel()
            current = listener
            listener.closeToNewSessions()
            expiry = scope.launch {
                delay(windowMs)
                stopIfCurrent(listener, "window elapsed")
            }
        }
        previous?.stop()
        L.i { "[MigrationV2][P2P][Rx] listener lingers for status queries windowMs=$windowMs" }
    }

    /** Stop whatever is lingering — a new receiver is about to need the port. */
    fun stopNow() {
        val listener: Listener?
        synchronized(lock) {
            listener = current
            current = null
            expiry?.cancel()
            expiry = null
        }
        listener?.let {
            it.stop()
            L.i { "[MigrationV2][P2P][Rx] lingering listener stopped: superseded" }
        }
    }

    private fun stopIfCurrent(listener: Listener, why: String) {
        synchronized(lock) {
            if (current !== listener) return
            current = null
            expiry = null
        }
        listener.stop()
        L.i { "[MigrationV2][P2P][Rx] lingering listener stopped: $why" }
    }

    companion object {
        /** The agreed upper bound of the queryable window after a session ends. */
        const val WINDOW_MS = 5L * 60 * 1000

        /** The process-wide instance: a lingering listener belongs to no service and no screen. */
        val shared: ReceiverLinger by lazy { ReceiverLinger(appScope) }
    }
}
