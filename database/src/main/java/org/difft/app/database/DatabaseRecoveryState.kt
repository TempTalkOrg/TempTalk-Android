package org.difft.app.database

import android.content.Context
import com.difft.android.base.log.lumberjack.L









class DatabaseRecoveryState(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    



    fun incrementAndGet(): Int {
        val next = prefs.getInt(KEY_ATTEMPT_COUNT, 0) + 1
        prefs.edit().putInt(KEY_ATTEMPT_COUNT, next).commit()
        L.i { "[DBRecoveryState] attempt count incremented to $next" }
        return next
    }

    
    fun reset() {
        if (prefs.getInt(KEY_ATTEMPT_COUNT, 0) == 0) return
        prefs.edit().clear().commit()
        L.i { "[DBRecoveryState] attempt count cleared (db healthy)" }
    }

    companion object {
        private const val PREFS_NAME = "db_recovery_state"
        private const val KEY_ATTEMPT_COUNT = "recovery_attempt_count"

        
        const val MAX_RECOVERY_ATTEMPTS = 3
    }
}
