package org.difft.app.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateDefaults
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable DB-recovery flags backed by [AppStateDataStore] (`app_state.preferences_pb`).
 *
 * All public methods are non-suspend and use `runBlocking(Dispatchers.IO)` with a 2 s
 * timeout so the DataStore actor flushes to disk before the caller returns — safe to
 * call immediately before `Process.killProcess()`. The DataStore actor serializes
 * concurrent writes, making [incrementRecoveryFailureCount] atomic without an external lock.
 */
// `runBlocking` is intentional throughout this class — see class KDoc for
// the "flush before Process.killProcess()" requirement. All sites use a 2 s
// bounded timeout. Runs on the DB-recovery / startup decision path, never
// from steady-state Main.
@Suppress("BanRunBlockingOutsideTests")
@Singleton
class DatabaseRecoveryPreferences @Inject constructor(
    @param:AppStateDataStore private val appStateStore: DataStore<Preferences>,
) {

    /** Returns `true` if a DB recovery is queued. Defaults to `false` on 2 s timeout. */
    fun isRecoveryNeeded(): Boolean = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(2_000) {
            appStateStore.data.first()[AppStateKeys.NEED_RECOVERY_DATABASE]
        } ?: AppStateDefaults.NEED_RECOVERY_DATABASE
    }

    /** Sets the recovery-needed flag and resets the failure counter. Flushes to disk before returning. */
    fun setRecoveryNeeded() {
        runBlocking(Dispatchers.IO) {
            val ok = withTimeoutOrNull(2_000) {
                appStateStore.edit {
                    it[AppStateKeys.NEED_RECOVERY_DATABASE] = true
                    it[AppStateKeys.DATABASE_RECOVERY_FAILURE_COUNT] = 0
                }
                true
            }
            if (ok == null) {
                L.w { "[DBRecovery][Island3] setRecoveryNeeded timed out after 2s — flag may be lost on restart" }
            } else {
                L.i { "[DBRecovery][Island3] setRecoveryNeeded flushed (about to restartApp)" }
            }
        }
    }

    /** Clears the recovery flag and failure counter after a successful recovery. */
    fun clearRecoveryFlag() {
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(2_000) {
                appStateStore.edit {
                    it[AppStateKeys.NEED_RECOVERY_DATABASE] = false
                    it[AppStateKeys.DATABASE_RECOVERY_FAILURE_COUNT] = 0
                }
            } ?: L.w { "[DBRecovery][Island3] clearRecoveryFlag timed out" }
        }
    }

    /**
     * Atomically increments the failure counter and clears the recovery-needed flag.
     * The DataStore actor serializes the read-modify-write — no external lock needed.
     */
    fun incrementRecoveryFailureCount() {
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(2_000) {
                appStateStore.edit {
                    val current = it[AppStateKeys.DATABASE_RECOVERY_FAILURE_COUNT]
                        ?: AppStateDefaults.DATABASE_RECOVERY_FAILURE_COUNT
                    it[AppStateKeys.NEED_RECOVERY_DATABASE] = false
                    it[AppStateKeys.DATABASE_RECOVERY_FAILURE_COUNT] = current + 1
                }
            } ?: L.w { "[DBRecovery][Island3] incrementRecoveryFailureCount timed out" }
        }
    }

    fun getRecoveryFailureCount(): Int = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(2_000) {
            appStateStore.data.first()[AppStateKeys.DATABASE_RECOVERY_FAILURE_COUNT]
        } ?: AppStateDefaults.DATABASE_RECOVERY_FAILURE_COUNT
    }

    fun isMaxRetriesReached(maxRetries: Int = 3): Boolean =
        getRecoveryFailureCount() >= maxRetries
}
