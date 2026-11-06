package com.difft.android.chat.focus

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs [fetch] with at most one in flight. A [request] that arrives while one runs is not dropped:
 * it is remembered once and a single trailing run starts when the current one finishes, so the
 * state read last is never older than the last trigger.
 */
internal class FocusFetchCoalescer(
    private val scope: CoroutineScope,
    private val fetch: suspend () -> Unit,
) {
    private val lock = Any()
    private var running = false
    private var trailing = false

    fun request() {
        synchronized(lock) {
            if (running) {
                trailing = true
                return
            }
            running = true
        }
        scope.launch {
            try {
                do {
                    synchronized(lock) { trailing = false }
                    try {
                        fetch()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        L.w { "[GroupFocus] fetch failed: ${e.javaClass.simpleName} ${e.message}" }
                    }
                    // Check and release in one critical section so a request arriving now is never lost.
                } while (synchronized(lock) { trailing.also { if (!it) running = false } })
            } catch (e: CancellationException) {
                synchronized(lock) { running = false }
                throw e
            }
        }
    }
}
