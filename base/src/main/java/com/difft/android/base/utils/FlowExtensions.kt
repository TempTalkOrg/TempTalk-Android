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












fun <T> Flow<T>.sampleAfterFirst(
    periodMillis: Long,
    predicate: (T) -> Boolean = { true }
): Flow<T> {
    require(periodMillis > 0) { "periodMillis should be positive" }

    return channelFlow {
        var firstEmitted = false
        var beforeFirst: T? = null      
        var latest: T? = null           
        val mutex = Mutex()
        var isAfterSilentPeriod = false 

        var tickerJob: Job? = null

        suspend fun startTicker() {
            
            tickerJob?.cancel()
            tickerJob = launch {
                while (isActive) {
                    delay(periodMillis)
                    val toSend: T? = mutex.withLock {
                        if (!firstEmitted) { 
                            val cached = beforeFirst
                            beforeFirst = null
                            if (cached != null) {
                                firstEmitted = true 
                                cached
                            } else {
                                
                                
                                
                                
                                
                                
                                
                                isAfterSilentPeriod = true 
                                null
                            }
                        } else { 
                            val cached = latest
                            latest = null
                            if (cached == null) { 
                                isAfterSilentPeriod = true
                            }
                            cached
                        }
                    }
                    toSend?.let {
                        mutex.withLock { isAfterSilentPeriod = false } 
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
                        beforeFirst = null 
                        mutex.withLock { isAfterSilentPeriod = false } 
                        trySend(value)
                        startTicker() 
                    } else {
                        mutex.withLock { beforeFirst = value }
                        
                        
                        if (tickerJob?.isActive != true) {
                            startTicker()
                        }
                    }
                } else { 
                    val emitImmediatelyDueToSilence = mutex.withLock {
                        if (isAfterSilentPeriod) {
                            latest = null 
                            isAfterSilentPeriod = false 
                            true
                        } else {
                            latest = value 
                            false
                        }
                    }

                    if (emitImmediatelyDueToSilence) {
                        trySend(value)
                        startTicker() 
                    }
                    
                    
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