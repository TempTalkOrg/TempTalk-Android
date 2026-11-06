package com.difft.android.network.config

/**
 * Throttle for [GlobalConfigsManager]'s periodic refresh: a refresh is due on the first check or
 * once [intervalMs] has elapsed since the last successful fetch. [clock] is monotonic
 * (`SystemClock.elapsedRealtime()` in production), so a wall-clock change cannot stall the
 * refresh; only [markRefreshed] after an adopted config moves the stamp (#1395).
 */
internal class RefreshGate(private val clock: () -> Long) {

    @Volatile
    var lastRefreshTime: Long = NEVER
        private set

    /** Milliseconds since the last successful refresh, or null before the first one. */
    fun sinceLastMs(): Long? = if (lastRefreshTime == NEVER) null else clock() - lastRefreshTime

    fun isDue(intervalMs: Long): Boolean {
        val since = sinceLastMs() ?: return true
        return since >= intervalMs
    }

    fun markRefreshed() {
        lastRefreshTime = clock()
    }

    private companion object {
        // elapsedRealtime() never reads 0 while the device runs, so 0 marks "never refreshed".
        const val NEVER = 0L
    }
}
