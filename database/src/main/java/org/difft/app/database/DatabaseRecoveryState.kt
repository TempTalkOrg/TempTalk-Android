package org.difft.app.database

import android.content.Context
import com.difft.android.base.log.lumberjack.L

/**
 * Lightweight, synchronous recovery circuit-breaker backed by a *dedicated*
 * [android.content.SharedPreferences] file (NOT the WCDB main DB, NOT the
 * encrypted DataStore).
 *
 * Why a standalone SharedPreferences and not the deleted `DatabaseRecoveryPreferences`
 * (DataStore) approach:
 * - The whole point is to bound recovery attempts even when the WCDB main DB is
 *   corrupt or the Keystore-backed cipher is permanently dead. The counter store
 *   therefore must NOT depend on either of those subsystems.
 * - SharedPreferences with `commit = true` writes synchronously to a plain XML
 *   file under the app's private dir — no Keystore, no SQLCipher, no coroutine.
 *
 * Contract:
 * - [incrementAndGet] is called BEFORE each recovery attempt. If the returned
 *   count exceeds [MAX_RECOVERY_ATTEMPTS], the caller must NOT loop further and
 *   instead route to a terminal state (logout + user-visible message).
 * - [reset] is called when the DB probes HEALTHY, so a normal launch never lets
 *   the counter accumulate across unrelated cold starts.
 *
 * This re-introduces the escape hatch that the prior redesign removed (the old
 * `failureCount >= 3 -> logout` fallback), WITHOUT re-introducing the per-corruption
 * notification reset bug: the only reset path is an explicit HEALTHY probe, and
 * the open path no longer touches this state at all.
 */
class DatabaseRecoveryState(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Increment the persisted attempt count synchronously and return the NEW value.
     * Must be called immediately before a recovery attempt begins.
     */
    fun incrementAndGet(): Int {
        val next = prefs.getInt(KEY_ATTEMPT_COUNT, 0) + 1
        prefs.edit().putInt(KEY_ATTEMPT_COUNT, next).commit()
        L.i { "[DBRecoveryState] attempt count incremented to $next" }
        return next
    }

    /** Clear the attempt count. Called when the DB probes HEALTHY. */
    fun reset() {
        if (prefs.getInt(KEY_ATTEMPT_COUNT, 0) == 0) return
        prefs.edit().clear().commit()
        L.i { "[DBRecoveryState] attempt count cleared (db healthy)" }
    }

    companion object {
        private const val PREFS_NAME = "db_recovery_state"
        private const val KEY_ATTEMPT_COUNT = "recovery_attempt_count"

        /**
         * Maximum number of consecutive recovery attempts before giving up and
         * forcing a logout. Mirrors the old `failureCount >= 3` escape hatch.
         */
        const val MAX_RECOVERY_ATTEMPTS = 3
    }
}
