package com.difft.android.network.config







internal class RefreshGate(private val clock: () -> Long) {

    @Volatile
    var lastRefreshTime: Long = NEVER
        private set

    
    fun sinceLastMs(): Long? = if (lastRefreshTime == NEVER) null else clock() - lastRefreshTime

    fun isDue(intervalMs: Long): Boolean {
        val since = sinceLastMs() ?: return true
        return since >= intervalMs
    }

    fun markRefreshed() {
        lastRefreshTime = clock()
    }

    private companion object {
        
        const val NEVER = 0L
    }
}
