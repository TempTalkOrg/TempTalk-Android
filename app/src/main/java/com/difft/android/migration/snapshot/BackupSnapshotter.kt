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

/**
 * Takes a consistent on-disk snapshot of the main WCDB database file.
 *
 * WAL auto-checkpoint is disabled for the duration and restored in a `finally` even on failure.
 * `truncateCheckpoint` merges the WAL into the main file and is retried because a long-running
 * reader can make it report `SQLITE_BUSY`; the 5s timeout is a protective ceiling, not an
 * expected wait. The caller deletes the tmp file once it has consumed it.
 */
interface BackupSnapshotter {
    /**
     * Takes a consistent snapshot of the main WCDB, returning the tmp file path.
     * Caller is responsible for deleting the tmp file after consumption.
     *
     * @throws MigrationException.SnapshotFailed if `truncateCheckpoint` cannot complete
     *   within the retry budget.
     */
    suspend fun takeSnapshot(): File

    companion object {
        /** Tmp directory under `filesDir` where snapshot copies live. */
        const val TMP_DIR_NAME = "migration_temp"

        /** Filename of the plaintext WCDB snapshot copy. */
        const val TMP_FILE_NAME = "chat.db.tmp"

        /**
         * Canonical absolute path of the snapshot tmp file — shared by the snapshotter and by any
         * startup cleanup that removes a leftover snapshot.
         */
        fun tmpFilePath(context: Context): File = File(File(context.filesDir, TMP_DIR_NAME), TMP_FILE_NAME)
    }
}

/**
 * Test seam over the three WCDB [Database] calls the snapshotter needs. [Database] loads a native
 * library in its `<clinit>`, which throws `UnsatisfiedLinkError` on the JVM test classpath even
 * under mocking, so tests inject a pure-JVM fake here instead.
 */
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

    /**
     * Test seam: `null` in production (resolves from `wcdb.db`). Tests set this directly
     * to inject a pure-JVM fake, bypassing the `Database` native static-init.
     *
     * `@Volatile` so the test-thread write is visible to the IO-dispatched coroutine.
     */
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
                // Translate the timeout here, or the outer `catch (e: CancellationException)`
                // would report it to the user as "cancelled". User-initiated cancellation throws a
                // plain `CancellationException`, so it bypasses this catch and keeps propagating.
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
