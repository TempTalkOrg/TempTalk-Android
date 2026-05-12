package com.difft.android.chat.util.concurrent

import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * Wraps an [Executor] and runs submitted [Runnable]s one-at-a-time on it, draining a FIFO queue.
 *
 * Pattern from https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/Executor.html.
 */
class SerialExecutor(private val executor: Executor) : Executor {

    private val tasks = ArrayDeque<Runnable>()
    private var active: Runnable? = null

    @Synchronized
    override fun execute(r: Runnable) {
        tasks.offer(Runnable {
            try {
                r.run()
            } finally {
                scheduleNext()
            }
        })
        if (active == null) {
            scheduleNext()
        }
    }

    @Synchronized
    private fun scheduleNext() {
        active = tasks.poll()
        active?.let(executor::execute)
    }
}
