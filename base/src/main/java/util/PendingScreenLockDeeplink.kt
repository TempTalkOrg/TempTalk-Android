package util

import android.content.Intent










object PendingScreenLockDeeplink {
    @Volatile
    private var pending: Intent? = null

    fun offer(intent: Intent) {
        pending = intent
    }

    
    fun poll(): Intent? = pending.also { pending = null }

    fun clear() {
        pending = null
    }
}
