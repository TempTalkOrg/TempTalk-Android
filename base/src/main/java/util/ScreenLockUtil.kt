package util

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

object ScreenLockUtil {
    private const val DEFAULT_EXEMPTION_MS = 60_000L

    // Monotonic deadline; single @Volatile Long so get/set never form a check-then-act race.
    @Volatile
    private var exemptUntilElapsed = 0L

    /**
     * Temporary screen-lock exemption for file/image selection, share, APK update, etc.
     * Auto-expires after [DEFAULT_EXEMPTION_MS]; in-process selectors also clear it in their
     * result/cancel callback so the lock re-engages immediately. Boolean get/set semantics are
     * unchanged, so existing `= true` / `= false` call sites keep compiling.
     */
    var temporarilyDisabled: Boolean
        get() = SystemClock.elapsedRealtime() < exemptUntilElapsed
        set(value) {
            exemptUntilElapsed = if (value) SystemClock.elapsedRealtime() + DEFAULT_EXEMPTION_MS else 0L
        }

    private const val RECENT_UNLOCK_WINDOW_MS = 5_000L
    private val recentlyUnlockedUntilElapsed = AtomicLong(0L)

    /**
     * Marks a real unlock so the popup gate lets the immediate replay through. Consulted ONLY by
     * the popup gate, never by shouldShowScreenLock — the foreground lock check and lock-immediately
     * re-lock are untouched. Call only when a queued deeplink is actually being replayed.
     */
    fun markRecentlyUnlocked() {
        recentlyUnlockedUntilElapsed.set(SystemClock.elapsedRealtime() + RECENT_UNLOCK_WINDOW_MS)
    }

    /**
     * One-shot AND time-boxed: consumed on read (getAndSet 0L) so only the immediate replay bypasses,
     * and auto-expires after the window so a replay that never reaches the gate cannot leave the
     * bypass armed for a later, unrelated popup.
     */
    val recentlyUnlocked: Boolean
        get() = SystemClock.elapsedRealtime() < recentlyUnlockedUntilElapsed.getAndSet(0L)
}
