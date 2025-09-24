package com.difft.android.base.utils

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Variant with [predicate] that decides which element should bypass the sampling window and be emitted immediately
 * the first time the predicate returns true. All elements (including ones that don't satisfy the predicate or the
 * subsequent ones after the first immediate emission) will be sampled with the usual [periodMillis] logic.
 *
 * If the sampling period passes without any new elements, the *next* element that arrives
 * will be emitted immediately, and the sampling window resets.
 *
 * The two-parameter overload (periodMillis only) behaves exactly like the old `sampleAfterFirst`: the very first
 * element is emitted right away regardless of its content.
 */
fun <T> Flow<T>.sampleAfterFirst(
    periodMillis: Long,
    predicate: (T) -> Boolean = { true }
): Flow<T> {
    require(periodMillis > 0) { "periodMillis should be positive" }

    return channelFlow {
        var firstEmitted = false
        var beforeFirst: T? = null      // cache before first predicate-true element
        var latest: T? = null           // cache after first emission
        val mutex = Mutex()
        var isAfterSilentPeriod = false // New: Tracks if the last ticker run was silent

        var tickerJob: Job? = null

        suspend fun startTicker() {
            // Cancel existing ticker if running, to reset the timing
            tickerJob?.cancel()
            tickerJob = launch {
                while (isActive) {
                    delay(periodMillis)
                    val toSend: T? = mutex.withLock {
                        if (!firstEmitted) { // Ticker fired before the first *actual* emission by predicate
                            val cached = beforeFirst
                            beforeFirst = null
                            if (cached != null) {
                                firstEmitted = true // Mark as first emitted (by ticker, due to predicate false initially)
                                cached
                            } else {
                                // This case implies predicate was true immediately, or beforeFirst was already consumed.
                                // If beforeFirst is null, and firstEmitted is false, it means the very first element
                                // satisfied the predicate and was sent immediately by the collector.
                                // Or, predicate was false, beforeFirst was cached, then ticker fired and sent it.
                                // If ticker fires again and firstEmitted is *still* false, something is odd,
                                // but practically, we check `latest` for subsequent emissions.
                                // For this branch, if `cached` is null, we effectively had a silent tick for `beforeFirst`.
                                isAfterSilentPeriod = true // Mark as silent if no `beforeFirst` was pending
                                null
                            }
                        } else { // Ticker fired after the first *actual* emission
                            val cached = latest
                            latest = null
                            if (cached == null) { // No new value arrived during the sampling window
                                isAfterSilentPeriod = true
                            }
                            cached
                        }
                    }
                    toSend?.let {
                        mutex.withLock { isAfterSilentPeriod = false } // Reset on actual send by ticker
                        trySend(it)
                    }
                }
            }
        }

        val upstreamJob: Job = launch {
            collect { value ->
                if (!firstEmitted) {
                    if (predicate(value)) {
                        firstEmitted = true
                        beforeFirst = null // clear cache
                        mutex.withLock { isAfterSilentPeriod = false } // Reset on this immediate send
                        trySend(value)
                        startTicker() // Start sampling window relative to this first emission
                    } else {
                        mutex.withLock { beforeFirst = value }
                        // Ensure ticker is started so cached `beforeFirst` can be emitted later
                        // if it's the only thing, or if predicate-true item never arrives.
                        if (tickerJob?.isActive != true) {
                            startTicker()
                        }
                    }
                } else { // firstEmitted is true
                    val emitImmediatelyDueToSilence = mutex.withLock {
                        if (isAfterSilentPeriod) {
                            latest = null // Current value is being sent, so no 'latest' to hold
                            isAfterSilentPeriod = false // Reset: we are consuming the post-silent value
                            true
                        } else {
                            latest = value // Cache for normal sampling by ticker
                            false
                        }
                    }

                    if (emitImmediatelyDueToSilence) {
                        trySend(value)
                        startTicker() // Restart ticker relative to this new immediate emission
                    }
                    // If not emitting immediately, the value is in 'latest' and ticker is already running.
                    // Ensure ticker is running if it somehow wasn't (e.g. very first element was via predicate)
                    else if (tickerJob?.isActive != true) {
                         startTicker()
                    }
                }
            }
        }

        awaitClose {
            upstreamJob.cancel()
            tickerJob?.cancel()
        }
    }
} 