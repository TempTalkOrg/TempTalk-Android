package com.difft.android.p2p.android.server

import com.difft.android.base.log.lumberjack.L
import okio.BufferedSink















internal class InterimResponseHeartbeat(
    private val sink: BufferedSink,
    private val intervalMs: Long = INTERVAL_MS,
) {
    private val lock = Object()
    private var thread: Thread? = null

    @Volatile
    private var running = false

    
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        thread = Thread {
            runCatching {
                while (true) {
                    
                    
                    synchronized(lock) {
                        if (!running) return@Thread
                        lock.wait(intervalMs)
                        if (!running) return@Thread
                        sink.writeUtf8(INTERIM_RESPONSE)
                        sink.flush()
                    }
                }
            }.onFailure {
                
                
                L.w { "[P2P][Receiver] heartbeat stopped: ${it.message}" }
            }
        }.apply { isDaemon = true; name = "p2p-upload-heartbeat"; start() }
    }

    






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
        
        private const val INTERIM_RESPONSE = "HTTP/1.1 102 Processing\r\n\r\n"

        
        const val INTERVAL_MS = 10_000L

        private const val JOIN_TIMEOUT_MS = 2_000L
    }
}
