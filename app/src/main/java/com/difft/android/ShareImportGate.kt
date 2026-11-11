package com.difft.android

import kotlinx.coroutines.sync.Mutex
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger


class ShareImportSupersededException : IOException("superseded by a newer share")






object ShareImportGate {
    val mutex = Mutex()
    private val arrivals = AtomicInteger()

    
    private var generation = 0
    private var active: Closeable? = null

    
    fun arrive(): Int = arrivals.incrementAndGet()

    



    @Synchronized
    fun begin(arrival: Int): Boolean {
        if (arrival < generation) return false
        generation = arrival
        active?.let { runCatching { it.close() } }
        active = null
        return true
    }

    @Synchronized
    fun isCurrent(ticket: Int): Boolean = generation == ticket

    
    fun <S : Closeable, R> copying(ticket: Int, stream: S, copy: (S) -> R): R {
        synchronized(this) {
            if (generation != ticket) throw ShareImportSupersededException()
            active = stream
        }
        try {
            return copy(stream)
        } catch (e: IOException) {
            
            throw if (isCurrent(ticket)) e else ShareImportSupersededException()
        } finally {
            synchronized(this) { if (active === stream) active = null }
        }
    }
}
