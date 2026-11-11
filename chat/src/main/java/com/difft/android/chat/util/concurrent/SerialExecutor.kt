package com.difft.android.chat.util.concurrent

import java.util.ArrayDeque
import java.util.concurrent.Executor






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
