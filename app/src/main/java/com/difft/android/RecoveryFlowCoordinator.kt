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









class RecoveryFlowCoordinator(
    private val host: Host,
    private val wcdb: WCDB,
    private val recoveryState: DatabaseRecoveryState,
    private val userManager: UserManager,
    private val logoutManager: LogoutManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    
    
    private val backupRetrieve: () -> Double = { wcdb.db.retrieve(null) },
    private val dbSmokeCheck: () -> Unit = { wcdb.db.execute("SELECT 1") },
    private val dbClose: () -> Unit = { wcdb.db.close() },
) {
    
    interface Host {
        
        val scope: CoroutineScope

        
        fun databaseFileExists(): Boolean

        
        fun renderRecoveryScreen()

        



        fun renderKeyUnavailableScreen()

        
        fun showToast(messageResId: Int)

        



        fun restartApp()
    }

    




    @Volatile
    var recoveryInProgress = false
        private set

    




    suspend fun routeOnDatabaseHealth(): Boolean {
        return when (checkDatabaseIntegrity()) {
            DbHealth.CORRUPT -> {
                withContext(Dispatchers.Main) { showRecoveryUI(DbHealth.CORRUPT) }
                false
            }
            DbHealth.KEY_UNAVAILABLE -> {
                
                
                
                
                
                
                
                
                
                
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

    






    @VisibleForTesting
    internal suspend fun checkDatabaseIntegrity(): DbHealth = withContext(ioDispatcher) {
        if (!host.databaseFileExists()) {
            L.i { "[MainActivity][DBRecovery] no DB file present, treating as healthy (→ normal routing / login)" }
            return@withContext DbHealth.HEALTHY
        }
        wcdb.probeHealthy()
    }

    



    @VisibleForTesting
    internal fun showRecoveryUI(health: DbHealth) {
        
        
        if (recoveryInProgress) {
            L.w { "[MainActivity][DBRecovery] showRecoveryUI ignored: recovery already in progress" }
            return
        }
        recoveryInProgress = true
        L.i { "[MainActivity][DBRecovery] showing recovery UI health=$health" }
        host.renderRecoveryScreen()
        lastRecoveryJob = performRecovery(health)
    }

    
    @VisibleForTesting
    internal var lastRecoveryJob: Job? = null
        private set

    






    private suspend fun abortRecoveryToFailSoft(reason: String) {
        L.w { "[MainActivity][DBRecovery] $reason, aborting recovery without wipe, showing fail-soft retry screen" }
        recoveryInProgress = false
        withContext(Dispatchers.Main) { host.renderKeyUnavailableScreen() }
    }

    





    @VisibleForTesting
    internal fun performRecovery(health: DbHealth): Job = host.scope.launch(ioDispatcher) {
        
        
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
        
        L.w { "[MainActivity][DBRecovery] key unavailable during backup retrieve, propagating: ${e.message}" }
        throw e
    } catch (e: Exception) {
        L.w { "[MainActivity][DBRecovery] backup recovery (retrieve) failed: ${e.message}" }
        false
    }

    






    private suspend fun resetDatabaseAndResync() {
        wcdb.markCorrupted()
        runCatching { dbClose() }
            .onFailure { L.w { "[MainActivity][DBRecovery] db close failed (continuing to delete): ${it.message}" } }
        wcdb.deleteDatabaseFile()
        try {
            userManager.update {
                syncedContactsV5 = false
                syncedGroupAndMembers = false
                directoryVersionForContactors = 0  
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
