package com.difft.android.chat.group

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Shares one in-flight run per key: callers arriving while [run] is active for the same key
 * await the leader's result instead of starting a duplicate or being told nothing happened.
 * A cancelled or failed leader yields `null` to its followers; followers are never cancelled by it.
 *
 * A caller that needs a result observed after some event passes a [mark] taken at that event as
 * `startedAfter`: a leader that started earlier is waited out and a fresh run follows it.
 */
internal class SingleFlight<K : Any, V : Any> {
    private class Entry<V>(val start: Long, val tag: Any?, val result: CompletableDeferred<V?>)

    private val inFlight = ConcurrentHashMap<K, Entry<V>>()
    private val ticks = AtomicLong()

    /** A point in this flight's order; runs started later report `start > mark`. */
    fun mark(): Long = ticks.incrementAndGet()

    /**
     * Runs [block] as the leader for [key], or awaits the active leader after calling [onJoin] with
     * the leader's [tag]; with [startedAfter], a leader that began at or before that mark is only waited for.
     */
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
