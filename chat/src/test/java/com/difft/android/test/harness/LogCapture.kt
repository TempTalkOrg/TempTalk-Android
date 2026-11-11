package com.difft.android.test.harness

import com.difft.android.base.log.lumberjack.L
import org.junit.Assert.fail
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList









class LogCapture {

    
    val messages: MutableList<String> = CopyOnWriteArrayList()

    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages += message
        }
    }

    fun <T> recording(block: LogCapture.() -> T): T {
        L.plant(tree)
        return try {
            block()
        } finally {
            L.uproot(tree)
        }
    }

    
    fun awaitLine(what: String, timeoutMs: Long = TIMEOUT_MS, predicate: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            messages.firstOrNull(predicate)?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("no log line for $what within ${timeoutMs}ms; captured=$messages")
        error("unreachable")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 10L
    }
}
