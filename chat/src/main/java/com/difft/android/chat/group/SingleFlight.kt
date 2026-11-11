package com.difft.android.chat.group

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong









internal class SingleFlight<K : Any, V : Any> {
    private class Entry<V>(val start: Long, val tag: Any?, val result: CompletableDeferred<V?>)

    private val inFlight = ConcurrentHashMap<K, Entry<V>>()
    private val ticks = AtomicLong()

    
    fun mark(): Long = ticks.incrementAndGet()

    



    suspend fun run(
        key: K,
        startedAfter: Long? = null,
        tag: Any? = null,
        onJoin: (leaderTag: Any?) -> Unit = {},
        block: suspend () -> V?,
    ): V? {
        while (true) {
            val entry = Entry(ticks.incrementAndGet(), tag, CompletableDeferred<V?>())
            val leader = inFlight.putIfAbsent(key, entry)
            if (leader == null) {
                try {
                    return block().also { entry.result.complete(it) }
                } finally {
                    entry.result.complete(null)
                    inFlight.remove(key, entry)
                }
            }
            if (startedAfter == null || leader.start > startedAfter) {
                onJoin(leader.tag)
                return leader.result.await()
            }
            leader.result.join()
        }
    }
}
