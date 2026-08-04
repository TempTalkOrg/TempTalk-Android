package util

import android.content.Intent

/**
 * In-memory single-slot queue for a notification-popup deeplink captured while the app lock was
 * required. In :base so both :app (MainActivity) and :login (ScreenLockActivity) can reach it.
 *
 * Holds DATA (the intent to replay), never lock STATE — it never participates in the "is it locked?"
 * decision. offer()/poll() are Main-thread-only, so the read-then-clear in poll() is not a
 * concurrency hazard; @Volatile is for visibility. In-memory only: on process death the pending
 * intent is lost (user lands on the chat list after unlock) — no security regression.
 */
object PendingScreenLockDeeplink {
    @Volatile
    private var pending: Intent? = null

    fun offer(intent: Intent) {
        pending = intent
    }

    /** Returns the queued intent (if any) and clears the slot, so it can never replay twice. */
    fun poll(): Intent? = pending.also { pending = null }

    fun clear() {
        pending = null
    }
}
