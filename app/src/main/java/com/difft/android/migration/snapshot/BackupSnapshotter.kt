package com.difft.android.migration.snapshot

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.MigrationException
import com.tencent.wcdb.core.Database
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.difft.app.database.WCDB
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton









interface BackupSnapshotter {
    






    suspend fun takeSnapshot(): File

    companion object {
        
        const val TMP_DIR_NAME = "migration_temp"

        
        const val TMP_FILE_NAME = "chat.db.tmp"

        



        fun tmpFilePath(context: Context): File = File(File(context.filesDir, TMP_DIR_NAME), TMP_FILE_NAME)
    }
}






@VisibleForTesting
internal interface SnapshotDbOps {
    fun execute(sql: String)
    fun passiveCheckpoint()
    fun truncateCheckpoint()
}

private class WcdbSnapshotDbOps(private val db: Database) : SnapshotDbOps {
    override fun execute(sql: String) {
        db.execute(sql)
    }
    override fun passiveCheckpoint() {
        db.passiveCheckpoint()
    }
    override fun truncateCheckpoint() {
        db.truncateCheckpoint()
    }
}

@Singleton
class BackupSnapshotterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wcdb: WCDB,
) : BackupSnapshotter {

    





    @Volatile
    @VisibleForTesting
    internal var overrideOps: SnapshotDbOps? = null

    private companion object {
        const val TRUNCATE_TIMEOUT_MS = 5_000L
        const val TRUNCATE_RETRY_DELAY_MS = 500L
        const val TRUNCATE_MAX_ATTEMPTS = 3
        const val PRAGMA_DISABLE_AUTOCHECKPOINT = "PRAGMA wal_autocheckpoint = 0"
        const val PRAGMA_RESTORE_AUTOCHECKPOINT = "PRAGMA wal_autocheckpoint = 1000"
    }

    override suspend fun takeSnapshot(): File = withContext(Dispatchers.IO) {
        val tmpFile = BackupSnapshotter.tmpFilePath(context)
        tmpFile.parentFile?.mkdirs()
        if (tmpFile.exists()) tmpFile.delete()

        val ops: SnapshotDbOps = overrideOps ?: WcdbSnapshotDbOps(wcdb.db)
        ops.execute(PRAGMA_DISABLE_AUTOCHECKPOINT)
        try {
            runCatching { ops.passiveCheckpoint() }
                .onFailure { L.w { "[Migration] passiveCheckpoint failed (non-blocking): ${it.message}" } }

            try {
                withTimeout(TRUNCATE_TIMEOUT_MS) {
                    var lastError: Throwable? = null
                    repeat(TRUNCATE_MAX_ATTEMPTS) { attempt ->
                        val result = runCatching { ops.truncateCheckpoint() }
                        if (result.isSuccess) return@withTimeout
                        lastError = result.exceptionOrNull()
                        L.w { "[Migration] truncateCheckpoint attempt=${attempt + 1} failed: ${result.exceptionOrNull()?.message}" }
                        delay(TRUNCATE_RETRY_DELAY_MS)
                    }
                    throw MigrationException.SnapshotFailed("truncateCheckpoint exhausted retries", lastError)
                }
            } catch (e: TimeoutCancellationException) {
                
                
                
                throw MigrationException.SnapshotFailed(
                    "truncateCheckpoint timed out after ${TRUNCATE_TIMEOUT_MS}ms",
                    e,
                )
            }

            val dbPath = context.getDatabasePath(WCDB.DATABASE_NAME)
            L.i { "[Migration] snapshot copyTo sourceSize=${dbPath.length()} target=${tmpFile.absolutePath}" }
            dbPath.copyTo(tmpFile, overwrite = true)
        } finally {
            runCatching { ops.execute(PRAGMA_RESTORE_AUTOCHECKPOINT) }
                .onFailure { L.w { "[Migration] restore autoCheckpoint failed: ${it.message}" } }
        }
        tmpFile
    }
}
