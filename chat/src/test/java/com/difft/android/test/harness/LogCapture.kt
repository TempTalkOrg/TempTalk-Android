package com.difft.android.test.harness

import com.difft.android.base.log.lumberjack.L
import org.junit.Assert.fail
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Captures what `L` actually emitted.
 *
 * `L.w` / `L.i` / `L.e` are `@JvmStatic`, so every call site is dispatched statically and an object
 * mock never observes them; planting a real tree is the only way to see the line. `L.log` also
 * returns immediately while no tree is planted, and delivery runs over L's own single-thread
 * channel — hence [awaitLine]'s bounded wait rather than a bare assertion right after the trigger.
 */
class LogCapture {

    /** Every message delivered while [recording], in arrival order. */
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

    /** Returns the first captured line matching [predicate], failing the test if none arrives. */
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
