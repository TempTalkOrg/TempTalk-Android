package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch








class ReceiverLinger(
    private val scope: CoroutineScope,
    private val windowMs: Long = WINDOW_MS,
) {
    
    interface Listener {
        
        fun closeToNewSessions()

        
        fun stop()
    }

    private val lock = Any()
    private var current: Listener? = null
    private var expiry: Job? = null

    
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
        
        const val WINDOW_MS = 5L * 60 * 1000

        
        val shared: ReceiverLinger by lazy { ReceiverLinger(appScope) }
    }
}
