package util

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

object ScreenLockUtil {
    private const val DEFAULT_EXEMPTION_MS = 60_000L

    
    @Volatile
    private var exemptUntilElapsed = 0L

    





    var temporarilyDisabled: Boolean
        get() = SystemClock.elapsedRealtime() < exemptUntilElapsed
        set(value) {
            exemptUntilElapsed = if (value) SystemClock.elapsedRealtime() + DEFAULT_EXEMPTION_MS else 0L
        }

    private const val RECENT_UNLOCK_WINDOW_MS = 5_000L
    private val recentlyUnlockedUntilElapsed = AtomicLong(0L)

    




    fun markRecentlyUnlocked() {
        recentlyUnlockedUntilElapsed.set(SystemClock.elapsedRealtime() + RECENT_UNLOCK_WINDOW_MS)
    }

    




    val recentlyUnlocked: Boolean
        get() = SystemClock.elapsedRealtime() < recentlyUnlockedUntilElapsed.getAndSet(0L)
}
