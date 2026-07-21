package com.difft.android

import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.DatabaseRecoveryState
import org.difft.app.database.DbHealth
import org.difft.app.database.WCDB
import org.difft.app.database.probeHealthy

/**
 * Owns the WCDB cold-start recovery / key-loss routing (extracted from [MainActivity] so it's
 * unit-testable without hitting `Runtime.exit`). Android-coupled seams go through [Host]; DB/data
 * dependencies are constructor params so tests can supply mocks/`TestDispatcher`.
 *
 * Invariants:
 * - destructive wipe ([resetDatabaseAndResync]) reachable ONLY via [DbHealth.CORRUPT]
 * - [DbHealth.KEY_UNAVAILABLE] is fail-soft: never wipes, never touches the corruption
 *   circuit-breaker's attempt count, never opens the DB. For an existing user (DB file present)
 *   whose cipher key can't be resolved, the local data is intact on disk but unreadable — so we
 *   render the minimal retry-only [com.difft.android.ui.KeyUnavailableScreen] (retry = restart →
 *   re-attempt the Keystore read in a fresh process). We never delete data on this path.
 * - DB FILE ABSENT (fresh install / logged-out) is treated as HEALTHY: the login path is WCDB-free
 *   and any background DB touch is covered by the Layer-3 CEH/guards, so a transient startup
 *   Keystore glitch must NOT poison a fresh login. The key is not probed on this path.
 */
class RecoveryFlowCoordinator(
    private val host: Host,
    private val wcdb: WCDB,
    private val recoveryState: DatabaseRecoveryState,
    private val userManager: UserManager,
    private val logoutManager: LogoutManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Raw DB-handle ops behind seams: production defaults call `wcdb.db.*`; tests override them so
    // recovery/wipe paths are JVM-testable without loading the native WCDB `Database` class.
    private val backupRetrieve: () -> Double = { wcdb.db.retrieve(null) },
    private val dbSmokeCheck: () -> Unit = { wcdb.db.execute("SELECT 1") },
    private val dbClose: () -> Unit = { wcdb.db.close() },
) {
    /** Activity-coupled operations the coordinator delegates to (implemented by [MainActivity]). */
    interface Host {
        /** Coroutine scope tied to the Activity lifecycle (`lifecycleScope` in production). */
        val scope: CoroutineScope

        /** Whether the WCDB main DB file exists (`getDatabasePath(...).exists()`). */
        fun databaseFileExists(): Boolean

        /** Render the corruption-recovery screen (`setContent { DatabaseRecoveryScreen() }`). */
        fun renderRecoveryScreen()

        /**
         * Render the fail-soft key-unavailable screen (`setContent { KeyUnavailableScreen(...) }`).
         * Retry-only: the screen's retry action restarts the process to re-attempt the Keystore read.
         */
        fun renderKeyUnavailableScreen()

        /** Show a long toast for the given string resource. */
        fun showToast(messageResId: Int)

        /**
         * Restart the process. The seam that keeps recovery testable — production kills the
         * process (`Runtime.exit`); tests record the call.
         */
        fun restartApp()
    }

    /**
     * Re-entry guard: a second [processIntent][MainActivity.processIntent] (e.g. from
     * `onNewIntent`) must NOT launch a second concurrent recovery — two coroutines racing
     * `retrieve`/`close`/`delete` on the same handle can crash natively.
     */
    @Volatile
    var recoveryInProgress = false
        private set

    /**
     * Route the cold-start flow on DB health. Returns `true` iff the DB is HEALTHY and the caller
     * should proceed with normal app routing; `false` if a recovery / fail-soft screen has taken
     * over (the caller must stop).
     */
    suspend fun routeOnDatabaseHealth(): Boolean {
        return when (checkDatabaseIntegrity()) {
            DbHealth.CORRUPT -> {
                withContext(Dispatchers.Main) { showRecoveryUI(DbHealth.CORRUPT) }
                false
            }
            DbHealth.KEY_UNAVAILABLE -> {
                // Fail-soft: never runs corruption recovery, never wipes, never opens the DB. The
                // local data is intact on disk but unreadable behind the missing key, so we render
                // the retry-only fail-soft screen instead of dropping an existing user into a
                // broken IndexActivity. Deliberately DOES NOT touch the corruption attempt count:
                // that counter tracks consecutive CORRUPT recovery attempts and is reset ONLY on a
                // HEALTHY probe. A key-failure launch neither resets nor increments it — resetting
                // here could indefinitely postpone the give-up/logout escape under a compound fault
                // (persistent corruption + intermittent Keystore), and a stale count is already
                // covered because any later HEALTHY probe resets it while an unresolved corruption
                // episode SHOULD keep counting.
                L.w { "[MainActivity][DBRecovery] key unavailable (DB present), showing fail-soft retry screen" }
                withContext(Dispatchers.Main) { host.renderKeyUnavailableScreen() }
                false
            }
            DbHealth.HEALTHY -> {
                recoveryState.reset()
                true
            }
        }
    }

    /**
     * Probe DB integrity off the main thread, returning the tri-state [DbHealth].
     *
     * DB present → [WCDB.probeHealthy]. DB absent (fresh install / logged-out) → [DbHealth.HEALTHY]:
     * the login path is WCDB-free and any background DB touch is CEH/guard-covered, so a transient
     * startup Keystore glitch must not poison a fresh login. The key is NOT probed here.
     */
    @VisibleForTesting
    internal suspend fun checkDatabaseIntegrity(): DbHealth = withContext(ioDispatcher) {
        if (!host.databaseFileExists()) {
            L.i { "[MainActivity][DBRecovery] no DB file present, treating as healthy (→ normal routing / login)" }
            return@withContext DbHealth.HEALTHY
        }
        wcdb.probeHealthy()
    }

    /**
     * Render the recovery UI and kick off recovery. Reached ONLY for [DbHealth.CORRUPT]; must run
     * on the main thread (renders Compose content).
     */
    @VisibleForTesting
    internal fun showRecoveryUI(health: DbHealth) {
        // Authoritative re-entry guard: two concurrent processIntent() coroutines both serialize
        // here — only the first starts recovery.
        if (recoveryInProgress) {
            L.w { "[MainActivity][DBRecovery] showRecoveryUI ignored: recovery already in progress" }
            return
        }
        recoveryInProgress = true
        L.i { "[MainActivity][DBRecovery] showing recovery UI health=$health" }
        host.renderRecoveryScreen()
        lastRecoveryJob = performRecovery(health)
    }

    /** Last recovery Job started by [showRecoveryUI], exposed for deterministic test joins. */
    @VisibleForTesting
    internal var lastRecoveryJob: Job? = null
        private set

    /**
     * Shared abort path for [performRecovery] on a mid-recovery cipher-key failure: clears
     * [recoveryInProgress] (NOT the destructive path, so release the re-entry guard) and renders
     * the fail-soft [com.difft.android.ui.KeyUnavailableScreen] WITHOUT wiping. A key failure never
     * sets `dbCorrupted`, so the wipe path stays unreachable; the screen's retry restarts and the
     * next cold-start gate re-lands here (or on the screen directly).
     */
    private suspend fun abortRecoveryToFailSoft(reason: String) {
        L.w { "[MainActivity][DBRecovery] $reason, aborting recovery without wipe, showing fail-soft retry screen" }
        recoveryInProgress = false
        withContext(Dispatchers.Main) { host.renderKeyUnavailableScreen() }
    }

    /**
     * Try backup-restore first; restart on success. On failure, fall through to a destructive
     * reset + resync + restart. Circuit-breaker: attempt count incremented BEFORE any work; once
     * it exceeds [DatabaseRecoveryState.MAX_RECOVERY_ATTEMPTS] the DB is treated as permanently
     * unrecoverable and we terminate with a logout instead of looping forever.
     */
    @VisibleForTesting
    internal fun performRecovery(health: DbHealth): Job = host.scope.launch(ioDispatcher) {
        // Destructive recovery is legitimate ONLY for file corruption — the wipe trigger reads
        // dbCorrupted only, so any other health (e.g. a mid-flow flip to KEY_UNAVAILABLE) aborts.
        if (health != DbHealth.CORRUPT) {
            abortRecoveryToFailSoft("performRecovery called with health=$health")
            return@launch
        }
        val attempt = recoveryState.incrementAndGet()
        if (attempt > DatabaseRecoveryState.MAX_RECOVERY_ATTEMPTS) {
            L.e { "[MainActivity][DBRecovery] giving up after $attempt attempts, forcing logout" }
            wcdb.markCorrupted()
            withContext(Dispatchers.Main) {
                host.showToast(R.string.db_recovery_unrecoverable_message)
                logoutManager.doLogout()
            }
            return@launch
        }

        L.i { "[MainActivity][DBRecovery] starting recovery attempt=$attempt" }
        // A cipher-key failure surfacing mid-recovery aborts to fail-soft instead of wiping.
        val recovered = try {
            tryBackupRecovery()
        } catch (e: WCDBKeyUnavailableException) {
            abortRecoveryToFailSoft("key unavailable mid-recovery: ${e.message}")
            return@launch
        }
        if (recovered) {
            withContext(Dispatchers.Main) { host.restartApp() }
            return@launch
        }
        resetDatabaseAndResync()
    }

    /**
     * Attempt WCDB auto-backup recovery. `retrieve()` returning score > 0 is the sole success
     * criterion; the post-retrieve `SELECT 1` is a diagnostic-only smoke check — a throw there
     * must NOT downgrade a successful restore to "failed" (which would trigger the wipe).
     */
    @VisibleForTesting
    internal fun tryBackupRecovery(): Boolean = try {
        val score = backupRetrieve()
        if (score > 0) {
            runCatching { dbSmokeCheck() }
                .onFailure { L.w { "[MainActivity][DBRecovery] post-retrieve smoke check threw (non-fatal, restore kept): ${it.message}" } }
            L.i { "[MainActivity][DBRecovery] backup recovery ok score=$score" }
            true
        } else {
            L.w { "[MainActivity][DBRecovery] no backup material (score=$score)" }
            false
        }
    } catch (e: WCDBKeyUnavailableException) {
        // Propagate so performRecovery aborts to fail-soft; must not fall into the catch below.
        L.w { "[MainActivity][DBRecovery] key unavailable during backup retrieve, propagating: ${e.message}" }
        throw e
    } catch (e: Exception) {
        L.w { "[MainActivity][DBRecovery] backup recovery (retrieve) failed: ${e.message}" }
        false
    }

    /**
     * Delete the corrupt DB and reset the server-resync gates, then restart.
     *
     * [WCDB.markCorrupted] runs BEFORE `close()` so any straggler background consumer fast-skips
     * the closing handle; `close()` is decoupled from delete because a dead handle can make
     * `close()` throw, while `deleteDatabaseFile()` goes through [Context] and must run regardless.
     */
    private suspend fun resetDatabaseAndResync() {
        wcdb.markCorrupted() // flip flag before close()
        runCatching { dbClose() }
            .onFailure { L.w { "[MainActivity][DBRecovery] db close failed (continuing to delete): ${it.message}" } }
        wcdb.deleteDatabaseFile()
        try {
            userManager.update {
                syncedContactsV5 = false          // force ContactorUtil.fetchAndSaveContactors re-pull
                syncedGroupAndMembers = false      // force GroupUtil.syncAllGroupAndAllGroupMembers re-pull
                directoryVersionForContactors = 0  // reset the directory cursor so the version gate can't skip the pull
            }
            L.i { "[MainActivity][DBRecovery] reset done; sync flags cleared (contacts/groups/dirVersion)" }
        } catch (e: Exception) {
            L.e { "[MainActivity][DBRecovery] reset failed: ${e.stackTraceToString()}" }
        }
        withContext(Dispatchers.Main) {
            host.showToast(R.string.db_recovery_resync_message)
            host.restartApp()
        }
    }
}
