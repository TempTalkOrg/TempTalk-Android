package com.difft.android.base.utils

import com.google.firebase.perf.FirebasePerformance

/**
 * Fail-safe Firebase Performance custom-trace wrapper.
 *
 * Wraps [block] in a named custom trace, attaching [attrs] as numeric metrics on
 * completion. Every Firebase call is swallowed (try/catch) so monitoring can NEVER
 * throw into business logic — if Performance is unavailable the block still runs and
 * its result is returned unchanged. Apply at batch granularity only (never per-message
 * or per-SQL): one start/stop per trace keeps the hot path observer-effect-free.
 */
inline fun <T> tracedPerf(name: String, attrs: Map<String, Long> = emptyMap(), block: () -> T): T {
    val trace = try { FirebasePerformance.getInstance().newTrace(name).apply { start() } } catch (_: Throwable) { null }
    return try {
        block()
    } finally {
        try {
            attrs.forEach { (k, v) -> trace?.putMetric(k, v) }
            trace?.stop()
        } catch (_: Throwable) {
        }
    }
}
